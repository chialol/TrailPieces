from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from trailpublish import CATALOG_CACHE_CONTROL, IMMUTABLE_CACHE_CONTROL, SCHEMA_VERSION
from trailpublish.config import PublishConfig
from trailpublish.errors import ConflictError, PreconditionFailed, PublishError
from trailpublish.images import render_webp, write_cover
from trailpublish.model import (
    content_key,
    dump_json,
    editorial_photo,
    is_safe_relative,
    load_json,
    sha256_bytes,
    sha256_file,
)
from trailpublish.store import ObjectStore
from trailpublish.validate import remote_issues
from trailpublish.registry import mark_published
from trailpublish.workspace import approval_problems

JSON_TYPE = "application/json"
WEBP_TYPE = "image/webp"


@dataclass
class Diff:
    added_photos: list[str] = field(default_factory=list)
    removed_photos: list[str] = field(default_factory=list)
    modified_photos: list[str] = field(default_factory=list)
    identity_photos: list[str] = field(default_factory=list)
    added_trails: list[str] = field(default_factory=list)
    removed_trails: list[str] = field(default_factory=list)
    modified_trails: list[str] = field(default_factory=list)

    def text(self) -> str:
        lines = []
        for label, values in (
            ("Added photos", self.added_photos),
            ("Removed photos", self.removed_photos),
            ("Modified photos", self.modified_photos),
            ("Replaced images", self.identity_photos),
            ("Added trails", self.added_trails),
            ("Removed trails", self.removed_trails),
            ("Modified trails", self.modified_trails),
        ):
            lines.append(f"{label}: {', '.join(values) if values else 'none'}")
        return "\n".join(lines)


@dataclass
class PublishReport:
    package_id: str
    release: str | None
    unchanged: bool
    dry_run: bool
    uploaded: list[str]
    skipped: list[str]
    upload_bytes: int
    diff: Diff
    catalog_revision: str | None
    note: str

    def text(self) -> str:
        if self.unchanged:
            return f"{self.package_id} matches release {self.release}. Nothing to upload."
        action = "Would publish" if self.dry_run else "Published"
        lines = [
            f"{action} {self.package_id} release {self.release}.",
            self.diff.text(),
            f"Upload: {len(self.uploaded)} objects, {self.upload_bytes} bytes.",
            f"Already on the origin: {len(self.skipped)}.",
        ]
        if self.note:
            lines.append(self.note)
        return "\n".join(lines)


def list_packages(store: ObjectStore, config: PublishConfig) -> list[dict]:
    catalog, _etag = read_catalog(store, config)
    packages = catalog.get("packages") or []
    return sorted(packages, key=lambda item: (item.get("order", 0), item.get("id", "")))


def publish_package(
    folder: Path,
    store: ObjectStore,
    config: PublishConfig,
    *,
    dry_run: bool = False,
    confirmed: bool = False,
    allow_removals: bool = False,
    allow_identity_change: bool = False,
    share_bundled: bool = False,
    invalidate: bool = False,
    invalidator=None,
    cdn_get=None,
) -> PublishReport:
    folder = folder.resolve()
    built = _build_local(folder)
    catalog, etag = read_catalog(store, config)
    others = _other_packages(store, config, catalog, built["package"]["id"])
    issues = remote_issues(
        folder,
        other_photos=others["photos"],
        other_unlocks=others["unlocks"],
        published_photo_ids=set(others["photos"]),
        share_bundled=share_bundled,
    )
    errors = [issue.message for issue in issues if issue.level == "error"]
    if errors:
        raise PublishError("Validation failed:\n" + "\n".join(f"- {item}" for item in errors))
    problems = approval_problems(folder)
    if problems:
        raise PublishError("\n".join(problems))
    for photo_id, digest in built["shas"].items():
        published = others["photos"].get(photo_id)
        if published and published != digest and not allow_identity_change:
            raise PublishError(
                f"Photo {photo_id} is already published with different image bytes. "
                "That would change its identity. Pass --allow-identity-change to do it on purpose."
            )

    current = _find(catalog, built["package"]["id"])
    previous = _load_release(store, config, current) if current else None
    diff = _diff(previous, built)
    if diff.removed_photos or diff.removed_trails:
        if not dry_run and not allow_removals:
            removed = ", ".join(diff.removed_photos + diff.removed_trails)
            raise PublishError(
                f"Refusing to remove {removed}. Pass --allow-removals to confirm."
            )
    if diff.identity_photos and not dry_run and not allow_identity_change:
        raise PublishError(
            "Refusing to replace image bytes for "
            + ", ".join(diff.identity_photos)
            + ". Pass --allow-identity-change to confirm."
        )

    release, reused = _choose_release(store, config, built, current, previous, diff)
    if release is None:
        if current and not dry_run:
            mark_published(folder, str(current["release"]))
        return PublishReport(
            package_id=built["package"]["id"],
            release=str(current["release"]) if current else None,
            unchanged=True,
            dry_run=dry_run,
            uploaded=[],
            skipped=[],
            upload_bytes=0,
            diff=diff,
            catalog_revision=catalog.get("revision"),
            note="",
        )

    document = _document(built, release)
    body = dump_json(document)
    prefix = config.content_prefix
    metadata_key = content_key(prefix, document["metadataPath"])
    uploads: list[tuple[str, bytes, str, str]] = []
    skipped: list[str] = []

    def queue(key: str, data: bytes, content_type: str, digest: str) -> None:
        if _same_object(store, key, digest):
            skipped.append(key)
            return
        uploads.append((key, data, content_type, digest))

    for asset in built["assets"]:
        queue(content_key(prefix, asset["path"]), asset["_bytes"], WEBP_TYPE, asset["sha256"])
        scene = asset.get("scene")
        if scene:
            for file_info in scene["_files"]:
                queue(
                    content_key(prefix, f"{scene['path']}/{file_info['name']}"),
                    file_info["bytes"],
                    file_info["type"],
                    file_info["sha256"],
                )
    queue(content_key(prefix, document["cover"]["path"]), built["cover_bytes"], WEBP_TYPE, document["cover"]["sha256"])
    if not reused:
        queue(metadata_key, body, JSON_TYPE, sha256_bytes(body))
    else:
        skipped.append(metadata_key)

    upload_bytes = sum(len(item[1]) for item in uploads)
    if dry_run:
        return PublishReport(
            package_id=built["package"]["id"],
            release=str(release),
            unchanged=False,
            dry_run=True,
            uploaded=[item[0] for item in uploads],
            skipped=skipped,
            upload_bytes=upload_bytes,
            diff=diff,
            catalog_revision=None,
            note="Dry run. No remote objects were written.",
        )
    if not confirmed:
        raise PublishError("Publish cancelled. Re-run with --yes after reviewing the plan.")

    uploaded_keys = []
    for key, data, content_type, digest in uploads:
        _upload_new(store, key, data, content_type=content_type, sha256=digest)
        uploaded_keys.append(key)

    referenced = [item[0] for item in uploads] + skipped
    missing = [key for key in referenced if store.head(key) is None]
    if missing:
        raise PublishError(
            "Origin is missing "
            + missing[0]
            + ". The catalog was not changed."
        )

    entry = _catalog_entry(document)
    _replace_package(catalog, entry)
    catalog["revision"] = _stamp()
    catalog["schemaVersion"] = SCHEMA_VERSION
    try:
        _commit_catalog(store, config.catalog_key(), dump_json(catalog), etag)
    except PreconditionFailed as error:
        raise ConflictError(
            "The catalog changed after it was read. Nothing in the catalog was overwritten. "
            "Run publish again."
        ) from error

    note = "Origin objects are in place and the catalog points at this release."
    if invalidate:
        actor = invalidator or _default_invalidator
        invalidation = actor(config)
        note += f" Catalog invalidation {invalidation}."
    note += " " + _verify_cdn(config, catalog["revision"], cdn_get)
    mark_published(folder, str(release))
    return PublishReport(
        package_id=built["package"]["id"],
        release=str(release),
        unchanged=False,
        dry_run=False,
        uploaded=uploaded_keys,
        skipped=skipped,
        upload_bytes=upload_bytes,
        diff=diff,
        catalog_revision=catalog["revision"],
        note=note.strip(),
    )


def rollback_package(
    store: ObjectStore,
    config: PublishConfig,
    package_id: str,
    version: str,
    *,
    dry_run: bool = False,
    confirmed: bool = False,
    invalidate: bool = False,
    invalidator=None,
    cdn_get=None,
) -> PublishReport:
    catalog, etag = read_catalog(store, config)
    current = _find(catalog, package_id)
    if current is None:
        raise PublishError(f"Package {package_id} is not in the catalog.")
    if str(current.get("release")) == str(version):
        return PublishReport(
            package_id=package_id,
            release=str(version),
            unchanged=True,
            dry_run=dry_run,
            uploaded=[],
            skipped=[],
            upload_bytes=0,
            diff=Diff(),
            catalog_revision=catalog.get("revision"),
            note="The catalog already points at that release.",
        )
    key = content_key(config.content_prefix, f"packages/{package_id}/releases/{version}/metadata.json")
    stored = store.get(key)
    if stored is None:
        raise PublishError(f"Release {version} is not on the origin. Nothing was changed.")
    document = json.loads(stored.body)
    for relative in _referenced_paths(document):
        if store.head(content_key(config.content_prefix, relative)) is None:
            raise PublishError(f"Release {version} is missing {relative}. The catalog was not changed.")
    if dry_run:
        return PublishReport(
            package_id=package_id,
            release=str(version),
            unchanged=False,
            dry_run=True,
            uploaded=[config.catalog_key()],
            skipped=[],
            upload_bytes=len(stored.body),
            diff=Diff(),
            catalog_revision=None,
            note=f"Would point {package_id} from release {current.get('release')} to {version}.",
        )
    if not confirmed:
        raise PublishError("Rollback cancelled. Re-run with --yes to update the catalog.")
    entry = _catalog_entry(document)
    _replace_package(catalog, entry)
    catalog["revision"] = _stamp()
    try:
        _commit_catalog(store, config.catalog_key(), dump_json(catalog), etag)
    except PreconditionFailed as error:
        raise ConflictError(
            "The catalog changed after it was read. The rollback did not overwrite it."
        ) from error
    note = f"Catalog points at {package_id} release {version}. Older objects were left in place."
    if invalidate:
        actor = invalidator or _default_invalidator
        note += f" Catalog invalidation {actor(config)}."
    note += " " + _verify_cdn(config, catalog["revision"], cdn_get)
    return PublishReport(
        package_id=package_id,
        release=str(version),
        unchanged=False,
        dry_run=False,
        uploaded=[config.catalog_key()],
        skipped=[],
        upload_bytes=0,
        diff=Diff(),
        catalog_revision=catalog["revision"],
        note=note.strip(),
    )


def read_catalog(store: ObjectStore, config: PublishConfig) -> tuple[dict, str | None]:
    stored = store.get(config.catalog_key())
    if stored is None:
        return {"schemaVersion": SCHEMA_VERSION, "revision": "0", "packages": []}, None
    try:
        catalog = json.loads(stored.body)
    except json.JSONDecodeError as error:
        raise PublishError(f"Remote catalog is not valid JSON: {error}") from error
    if catalog.get("schemaVersion") != SCHEMA_VERSION:
        raise PublishError(
            f"Remote catalog schemaVersion {catalog.get('schemaVersion')!r} is unsupported. "
            "Refusing to replace it."
        )
    if not isinstance(catalog.get("packages"), list):
        raise PublishError("Remote catalog has no packages array. Refusing to replace it.")
    return catalog, stored.etag


def _build_local(folder: Path) -> dict:
    package = load_json(folder / "package.json")
    metadata = load_json(folder / "metadata.json")
    assets = []
    shas = {}
    for photo in metadata["photos"]:
        source = folder / photo["sourceFile"]
        rendered_path = folder / "build" / "images" / f"{photo['id']}.webp"
        rendered = render_webp(source, rendered_path)
        data = rendered_path.read_bytes()
        short = rendered.sha256[:16]
        asset = {
            "photoId": photo["id"],
            "path": f"assets/{photo['id']}.{short}.webp",
            "sha256": rendered.sha256,
            "width": rendered.width,
            "height": rendered.height,
            "bytes": rendered.byte_count,
            "_bytes": data,
        }
        scene = _scene(folder, photo["id"])
        if scene is not None:
            asset["scene"] = scene
        assets.append(asset)
        shas[photo["id"]] = rendered.sha256
    cover_id = package["coverPhotoId"]
    cover_source = next(item["_bytes"] for item in assets if item["photoId"] == cover_id)
    cover_file = folder / "build" / "cover.webp"
    cover_file.parent.mkdir(parents=True, exist_ok=True)
    cover_file.write_bytes(cover_source)
    # write_cover reads an image; give it the rendered puzzle image.
    puzzle = folder / "build" / "images" / f"{cover_id}.webp"
    cover_bytes = write_cover(puzzle, cover_file)
    return {
        "package": package,
        "metadata": metadata,
        "assets": assets,
        "shas": shas,
        "cover_bytes": cover_bytes,
        "cover_sha": sha256_bytes(cover_bytes),
    }


def _scene(folder: Path, photo_id: str) -> dict | None:
    root = folder / "scenes" / photo_id
    path = root / "scene.json"
    if not path.is_file():
        return None
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise PublishError(f"scenes/{photo_id}/scene.json is not valid JSON: {error}") from error
    names = ["scene.json", raw.get("plateFile"), raw.get("aliveFile"), raw.get("coverFile")]
    for piece in raw.get("pieces") or []:
        if isinstance(piece, dict):
            names.extend([piece.get("cropFile"), piece.get("liftFile"), piece.get("iconFile")])
    files = []
    seen = set()
    for name in names:
        if not isinstance(name, str) or not is_safe_relative(name) or name in seen:
            raise PublishError(f"Scene for {photo_id} has an unsafe file name.")
        seen.add(name)
        file = root / name
        if not file.is_file():
            raise PublishError(f"Scene for {photo_id} is missing {name}.")
        payload = file.read_bytes()
        files.append(
            {
                "name": name,
                "bytes": payload,
                "sha256": sha256_bytes(payload),
                "type": JSON_TYPE if name.endswith(".json") else WEBP_TYPE,
            }
        )
    tree = sha256_bytes("".join(f"{item['name']}\n{item['sha256']}\n" for item in files).encode("utf-8"))
    return {
        "path": f"assets/scenes/{photo_id}/{tree[:16]}",
        "sha256": tree,
        "files": [item["name"] for item in files],
        "bytes": sum(len(item["bytes"]) for item in files),
        "_files": files,
    }


def _document(built: dict, release: int) -> dict:
    package = built["package"]
    metadata = built["metadata"]
    editorial = {
        "version": 1,
        "moods": [
            {"id": item["id"], "title": item["title"], "summary": item.get("summary", "")}
            for item in metadata["moods"]
        ],
        "sceneries": [
            {
                "id": item["id"],
                "title": item["title"],
                "summary": item.get("summary", ""),
                "story": item.get("story", ""),
            }
            for item in metadata["sceneries"]
        ],
        "photos": [editorial_photo(photo, include_source=False) for photo in metadata["photos"]],
        "trails": [
            {
                "id": trail["id"],
                "title": trail["title"],
                "summary": trail.get("summary", ""),
                "story": trail.get("story", ""),
                "photoIds": list(trail.get("photoIds") or []),
            }
            for trail in metadata["trails"]
        ],
    }
    assets = []
    for asset in built["assets"]:
        published = {
            "photoId": asset["photoId"],
            "path": asset["path"],
            "sha256": asset["sha256"],
            "width": asset["width"],
            "height": asset["height"],
            "bytes": asset["bytes"],
        }
        if asset.get("scene"):
            scene = asset["scene"]
            published["scene"] = {
                "path": scene["path"],
                "sha256": scene["sha256"],
                "files": scene["files"],
                "bytes": scene["bytes"],
            }
        assets.append(published)
    package_block = {
        "title": package["title"],
        "description": package["description"],
        "access": package["access"],
        "unlock": package["unlock"],
        "order": package.get("order", 0),
        "coverPhotoId": package["coverPhotoId"],
    }
    if package.get("minAppVersion"):
        package_block["minAppVersion"] = package["minAppVersion"]
    cover_path = f"packages/{package['id']}/releases/{release}/previews/cover.webp"
    metadata_path = f"packages/{package['id']}/releases/{release}/metadata.json"
    download = sum(asset["bytes"] for asset in assets)
    download += sum(asset["scene"]["bytes"] for asset in assets if asset.get("scene"))
    download += len(built["cover_bytes"])
    return {
        "schemaVersion": SCHEMA_VERSION,
        "packageId": package["id"],
        "release": str(release),
        "package": package_block,
        "editorial": editorial,
        "assets": assets,
        "cover": {"path": cover_path, "sha256": built["cover_sha"], "bytes": len(built["cover_bytes"])},
        "metadataPath": metadata_path,
        "downloadBytes": download + 0,
    }


def _catalog_entry(document: dict) -> dict:
    package = document["package"]
    entry = {
        "id": document["packageId"],
        "title": package["title"],
        "description": package["description"],
        "release": document["release"],
        "cover": document["cover"]["path"],
        "metadata": document["metadataPath"],
        "photoCount": len(document["editorial"]["photos"]),
        "downloadBytes": document["downloadBytes"] + len(dump_json(document)),
        "access": package["access"],
        "unlock": package["unlock"],
        "order": package.get("order", 0),
    }
    if package.get("minAppVersion"):
        entry["minAppVersion"] = package["minAppVersion"]
    return entry


def _choose_release(store, config, built, current, previous, diff: Diff):
    if current and previous and _same_release(current, previous, built, diff):
        return None, False
    start = 1 if current is None else int(current["release"]) + 1
    for release in range(start, start + 20):
        key = content_key(
            config.content_prefix,
            f"packages/{built['package']['id']}/releases/{release}/metadata.json",
        )
        existing = store.get(key)
        if existing is None:
            return release, False
        try:
            remote = json.loads(existing.body)
        except json.JSONDecodeError:
            continue
        if _document_signature(remote) == _document_signature(_document(built, release)):
            return release, True
    raise PublishError("Could not find a free release number. Nothing was changed in the catalog.")


def _same_release(current: dict, previous: dict, built: dict, diff: Diff) -> bool:
    if diff.added_photos or diff.removed_photos or diff.modified_photos or diff.identity_photos:
        return False
    if diff.added_trails or diff.removed_trails or diff.modified_trails:
        return False
    package = previous.get("package") or {}
    local = built["package"]
    if package.get("title") != local.get("title") or package.get("description") != local.get("description"):
        return False
    if package.get("unlock") != local.get("unlock") or package.get("access") != local.get("access"):
        return False
    if package.get("order", 0) != local.get("order", 0):
        return False
    if (package.get("minAppVersion") or None) != (local.get("minAppVersion") or None):
        return False
    if previous.get("cover", {}).get("sha256") != built["cover_sha"]:
        return False
    return str(current.get("release")) == str(previous.get("release"))


def _document_signature(document: dict) -> str:
    payload = {
        "package": document.get("package"),
        "editorial": document.get("editorial"),
        "assets": [
            {
                "photoId": asset.get("photoId"),
                "sha256": asset.get("sha256"),
                "scene": (asset.get("scene") or {}).get("sha256"),
            }
            for asset in document.get("assets") or []
        ],
        "cover": (document.get("cover") or {}).get("sha256"),
    }
    return sha256_bytes(dump_json(payload))


def _diff(previous: dict | None, built: dict) -> Diff:
    new_photos = {photo["id"]: editorial_photo(photo, include_source=False) for photo in built["metadata"]["photos"]}
    new_shas = built["shas"]
    new_trails = {trail["id"]: trail for trail in built["metadata"]["trails"]}
    diff = Diff()
    if previous is None:
        diff.added_photos = list(new_photos)
        diff.added_trails = list(new_trails)
        return diff
    old_photos = {photo["id"]: photo for photo in previous["editorial"]["photos"]}
    old_shas = {asset["photoId"]: asset["sha256"] for asset in previous["assets"]}
    old_trails = {trail["id"]: trail for trail in previous["editorial"]["trails"]}
    for photo_id in new_photos:
        if photo_id not in old_photos:
            diff.added_photos.append(photo_id)
        else:
            if old_shas.get(photo_id) != new_shas.get(photo_id):
                diff.identity_photos.append(photo_id)
            elif _photo_text(old_photos[photo_id]) != _photo_text(new_photos[photo_id]):
                diff.modified_photos.append(photo_id)
    diff.removed_photos = [photo_id for photo_id in old_photos if photo_id not in new_photos]
    for trail_id, trail in new_trails.items():
        if trail_id not in old_trails:
            diff.added_trails.append(trail_id)
        elif _trail_text(old_trails[trail_id]) != _trail_text(trail):
            diff.modified_trails.append(trail_id)
    diff.removed_trails = [trail_id for trail_id in old_trails if trail_id not in new_trails]
    return diff


def _photo_text(photo: dict) -> tuple:
    return (
        photo.get("title"),
        photo.get("sceneryId"),
        tuple(photo.get("moodIds") or []),
        photo.get("revealId"),
        photo.get("blurb"),
        photo.get("story", ""),
    )


def _trail_text(trail: dict) -> tuple:
    return (
        trail.get("title"),
        trail.get("summary", ""),
        trail.get("story", ""),
        tuple(trail.get("photoIds") or []),
    )


def _other_packages(store, config, catalog, package_id: str) -> dict:
    photos = {}
    unlocks = {}
    for entry in catalog.get("packages") or []:
        if not isinstance(entry, dict):
            continue
        unlocks[entry.get("id")] = entry.get("unlock") or {}
        if entry.get("id") == package_id:
            continue
        relative = entry.get("metadata")
        if not isinstance(relative, str):
            raise PublishError(f"Package {entry.get('id')} has no metadata path. Refusing to publish.")
        stored = store.get(content_key(config.content_prefix, relative))
        if stored is None:
            raise PublishError(
                f"Package {entry.get('id')} metadata is not on the origin. Refusing to publish."
            )
        document = json.loads(stored.body)
        for asset in document.get("assets") or []:
            photos[asset["photoId"]] = asset["sha256"]
    return {"photos": photos, "unlocks": unlocks}


def _load_release(store, config, entry: dict) -> dict | None:
    stored = store.get(content_key(config.content_prefix, entry["metadata"]))
    if stored is None:
        raise PublishError(
            f"The catalog points at {entry['metadata']}, which is not on the origin. "
            "Refusing to publish a new release over a broken catalog."
        )
    return json.loads(stored.body)


def _find(catalog: dict, package_id: str) -> dict | None:
    for entry in catalog.get("packages") or []:
        if isinstance(entry, dict) and entry.get("id") == package_id:
            return entry
    return None


def _replace_package(catalog: dict, entry: dict) -> None:
    packages = [item for item in catalog.get("packages") or [] if item.get("id") != entry["id"]]
    packages.append(entry)
    packages.sort(key=lambda item: (item.get("order", 0), item.get("id", "")))
    catalog["packages"] = packages


def _same_object(store: ObjectStore, key: str, digest: str) -> bool:
    existing = store.head(key)
    if existing is None:
        return False
    remote = existing.metadata.get("sha256")
    if remote == digest:
        return True
    if existing.body and sha256_bytes(existing.body) == digest:
        return True
    raise PublishError(
        f"{key} already exists with different content. Immutable objects are not overwritten."
    )


def _upload_new(store: ObjectStore, key: str, body: bytes, *, content_type: str, sha256: str) -> None:
    if _same_object(store, key, sha256):
        return
    cache = CATALOG_CACHE_CONTROL if key.endswith("catalog.json") else IMMUTABLE_CACHE_CONTROL
    try:
        store.put(
            key,
            body,
            content_type=content_type,
            cache_control=cache,
            metadata={"sha256": sha256},
            if_none_match="*",
        )
    except PreconditionFailed:
        if _same_object(store, key, sha256):
            return
        raise PublishError(f"Concurrent write blocked {key}. The catalog was not changed.") from None


def _commit_catalog(store: ObjectStore, key: str, body: bytes, etag: str | None) -> None:
    kwargs = {
        "content_type": JSON_TYPE,
        "cache_control": CATALOG_CACHE_CONTROL,
        "metadata": {"sha256": sha256_bytes(body)},
    }
    if etag is None:
        store.put(key, body, if_none_match="*", **kwargs)
    else:
        store.put(key, body, if_match=etag, **kwargs)


def _referenced_paths(document: dict) -> list[str]:
    paths = [document["metadataPath"], document["cover"]["path"]]
    for asset in document.get("assets") or []:
        paths.append(asset["path"])
        scene = asset.get("scene")
        if scene:
            for name in scene.get("files") or []:
                paths.append(f"{scene['path']}/{name}")
    return paths


def _stamp() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _verify_cdn(config: PublishConfig, revision: str, cdn_get) -> str:
    if not config.cdn_base_url:
        return "CloudFront was not checked because cdn_base_url is empty."
    getter = cdn_get or _http_get
    try:
        body = getter(config.catalog_url())
        remote = json.loads(body)
    except Exception as error:
        return (
            "The origin catalog is updated. CloudFront could not be read yet "
            f"({error}). It may keep the previous catalog for up to 60 seconds."
        )
    if remote.get("revision") == revision:
        return "CloudFront is serving this catalog revision."
    return (
        "The origin catalog is updated. CloudFront is still serving an older revision, "
        "which its cache lifetime allows for about 60 seconds."
    )


def _http_get(url: str) -> bytes:
    import urllib.request

    with urllib.request.urlopen(url, timeout=20) as response:
        return response.read()


def _default_invalidator(config: PublishConfig) -> str:
    from trailpublish.store import invalidate_catalog

    return invalidate_catalog(config)
