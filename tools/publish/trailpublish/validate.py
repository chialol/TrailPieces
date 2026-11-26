from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from trailpublish import SCHEMA_VERSION
from trailpublish.errors import PublishError
from trailpublish.model import (
    VERSION_RE,
    bundled_photo_ids,
    editorial_photo,
    load_json,
    mood_signature,
    scenery_signature,
    trail_signature,
    valid_id,
)
from trailpublish.registry import assemble_registry


@dataclass(frozen=True)
class Issue:
    level: str
    message: str


def local_issues(folder: Path, extra_photo_ids: set[str] | None = None) -> list[Issue]:
    folder = folder.resolve()
    issues: list[Issue] = []
    package, metadata = _read_pair(folder, issues)
    if package is None or metadata is None:
        return issues
    _package_shape(package, issues)
    _metadata_shape(folder, package, metadata, issues)
    _classifications(metadata, issues)
    _photos(folder, package, metadata, issues)
    known = bundled_photo_ids() | set(extra_photo_ids or ())
    _trails(metadata, issues, extra_photo_ids=known)
    _unlock_cycle_self(package, issues)
    if metadata.get("uncertainties") or metadata.get("reviewNotes"):
        issues.append(
            Issue(
                "warning",
                "Research notes in metadata.json are kept locally and are not published. "
                "Prefer review-notes.json.",
            )
        )
    return issues


def remote_issues(
    folder: Path,
    *,
    other_photos: dict[str, str],
    other_unlocks: dict[str, dict],
    published_photo_ids: set[str],
    share_bundled: bool,
) -> list[Issue]:
    """Checks that need the current catalog. [other_photos] maps photo id to sha256."""
    folder = folder.resolve()
    issues = local_issues(folder, extra_photo_ids=published_photo_ids)
    package = load_json(folder / "package.json")
    metadata = load_json(folder / "metadata.json")
    bundled = bundled_photo_ids()
    for photo in metadata.get("photos") or []:
        photo_id = photo["id"]
        if photo_id in bundled and not share_bundled:
            issues.append(
                Issue(
                    "error",
                    f"Photo {photo_id} is already in the bundled catalog. "
                    "Use a new id, or pass --share-bundled-id to share completion on purpose. "
                    "The installed app keeps the bundled wording for that photo.",
                )
            )
        if photo_id in other_photos:
            issues.append(
                Issue(
                    "warning",
                    f"Photo {photo_id} is already published in another package and will share completion progress.",
                )
            )
    unlock = package.get("unlock") or {}
    if unlock.get("type") == "packageCompleted":
        prerequisite = unlock.get("packageId")
        if prerequisite == package.get("id"):
            issues.append(Issue("error", "A package cannot require itself."))
        elif prerequisite not in other_unlocks:
            issues.append(Issue("error", f"Unlock requires package {prerequisite}, which is not published."))
        elif _cycle(package["id"], {**other_unlocks, package["id"]: unlock}):
            issues.append(Issue("error", "Package unlock requirements form a cycle."))
    return issues


def _dedupe(issues: list[Issue]) -> list[Issue]:
    seen = set()
    kept = []
    for issue in issues:
        key = (issue.level, issue.message)
        if key in seen:
            continue
        seen.add(key)
        kept.append(issue)
    return kept


def _read_pair(folder: Path, issues: list[Issue]):
    package = metadata = None
    for name in ("package.json", "metadata.json"):
        path = folder / name
        if not path.exists():
            issues.append(Issue("error", f"Missing {name}. Run prepare first."))
            continue
        try:
            loaded = load_json(path)
        except PublishError as error:
            issues.append(Issue("error", str(error)))
            continue
        if name == "package.json":
            package = loaded
        else:
            metadata = loaded
    return package, metadata


def _package_shape(package: dict, issues: list[Issue]) -> None:
    if not valid_id(str(package.get("id", ""))):
        issues.append(Issue("error", "Package id must be lowercase letters, numbers, and hyphens."))
    if not str(package.get("title", "")).strip():
        issues.append(Issue("error", "Package title is empty."))
    if not str(package.get("description", "")).strip():
        issues.append(Issue("error", "Package description is empty."))
    if package.get("access") != "public":
        issues.append(Issue("error", f"Access policy {package.get('access')!r} is not supported. Refusing to publish it."))
    unlock = package.get("unlock")
    if not isinstance(unlock, dict):
        issues.append(Issue("error", "Package unlock is missing."))
    else:
        kind = unlock.get("type")
        if kind == "immediate":
            pass
        elif kind == "puzzlesCompleted":
            count = unlock.get("count")
            if not isinstance(count, int) or isinstance(count, bool) or count < 1:
                issues.append(Issue("error", "puzzlesCompleted count must be an integer of at least 1."))
        elif kind == "packageCompleted":
            if not valid_id(str(unlock.get("packageId", ""))):
                issues.append(Issue("error", "packageCompleted needs a package id."))
        else:
            issues.append(Issue("error", f"Unlock type {kind!r} is not supported."))
    minimum = package.get("minAppVersion")
    if minimum not in (None, "") and (not isinstance(minimum, str) or not VERSION_RE.fullmatch(minimum)):
        issues.append(Issue("error", "minAppVersion must look like 0.1.0."))
    if not isinstance(package.get("order", 0), int) or isinstance(package.get("order"), bool):
        issues.append(Issue("error", "Package order must be an integer."))
    if not isinstance(package.get("photos"), list) or not package["photos"]:
        issues.append(Issue("error", "The package has no photographs."))


def _metadata_shape(folder: Path, package: dict, metadata: dict, issues: list[Issue]) -> None:
    if metadata.get("schemaVersion") != SCHEMA_VERSION:
        issues.append(Issue("error", f"Unsupported metadata schemaVersion {metadata.get('schemaVersion')!r}."))
    if metadata.get("version") != 1:
        issues.append(Issue("error", "Metadata version must be 1 so the app can read it."))
    if metadata.get("packageId") != package.get("id"):
        issues.append(Issue("error", "metadata.json packageId does not match package.json."))
    for name in ("moods", "sceneries", "photos", "trails"):
        if not isinstance(metadata.get(name), list):
            issues.append(Issue("error", f"metadata.json needs a {name} array."))


def _classifications(metadata: dict, issues: list[Issue]) -> None:
    registry = assemble_registry()
    seen_moods = _unique_objects(metadata.get("moods") or [], "mood", issues)
    seen_sceneries = _unique_objects(metadata.get("sceneries") or [], "scenery", issues)
    for item in metadata.get("moods") or []:
        if not isinstance(item, dict) or not valid_id(str(item.get("id", ""))):
            continue
        if not str(item.get("summary", "")).strip():
            issues.append(Issue("error", f"Mood {item['id']} needs a summary."))
        _compare_definition(
            item,
            registry.moods.get(item["id"]),
            registry.conflict_moods,
            mood_signature,
            "Mood",
            issues,
        )
    for item in metadata.get("sceneries") or []:
        if not isinstance(item, dict) or not valid_id(str(item.get("id", ""))):
            continue
        if not str(item.get("summary", "")).strip():
            issues.append(Issue("error", f"Scenery {item['id']} needs a summary."))
        if not isinstance(item.get("story", ""), str):
            issues.append(Issue("error", f"Scenery {item['id']} story must be a string."))
        _compare_definition(
            item,
            registry.sceneries.get(item["id"]),
            registry.conflict_sceneries,
            scenery_signature,
            "Scenery",
            issues,
        )
    metadata["_mood_ids"] = seen_moods
    metadata["_scenery_ids"] = seen_sceneries
    metadata["_registry"] = registry


def _compare_definition(item, approved, conflicts: set[str], signature, kind: str, issues: list[Issue]) -> None:
    item_id = item["id"]
    if item_id in conflicts:
        issues.append(
            Issue(
                "error",
                f"{kind} {item_id} has conflicting approved definitions. "
                "Resolve that before publishing it again.",
            )
        )
        return
    if approved is None:
        title = str(item.get("title", "")).strip()
        issues.append(
            Issue(
                "notice",
                f"New {kind.lower()} {item_id} ({title}). Approval is required before publish.",
            )
        )
        return
    if signature(item) != signature(approved):
        issues.append(Issue("error", f"{kind} {item_id} does not match the shared definition."))


def _unique_objects(items: list, kind: str, issues: list[Issue]) -> set[str]:
    seen = set()
    for item in items:
        if not isinstance(item, dict) or not valid_id(str(item.get("id", ""))):
            issues.append(Issue("error", f"A {kind} is missing a valid id."))
            continue
        if item["id"] in seen:
            issues.append(Issue("error", f"Duplicate {kind} id {item['id']}."))
        seen.add(item["id"])
        if not str(item.get("title", "")).strip():
            issues.append(Issue("error", f"{kind} {item['id']} needs a title."))
    return seen


def _photos(folder: Path, package: dict, metadata: dict, issues: list[Issue]) -> None:
    listed = package.get("photos") or []
    files = []
    ids = []
    for item in listed:
        if not isinstance(item, dict):
            issues.append(Issue("error", "package.json photos must be objects."))
            continue
        photo_id = str(item.get("id", ""))
        relative = str(item.get("file", ""))
        if not valid_id(photo_id):
            issues.append(Issue("error", f"Photo id {photo_id!r} is not stable."))
        if photo_id in ids:
            issues.append(Issue("error", f"Duplicate photo id {photo_id}."))
        ids.append(photo_id)
        if not relative or relative.startswith("/") or ".." in relative.split("/"):
            issues.append(Issue("error", f"Photo {photo_id} has an unsafe source path."))
            continue
        path = folder / relative
        if not path.is_file():
            issues.append(Issue("error", f"Missing source image for {photo_id}: {relative}"))
        files.append(relative)
    if len(files) != len(set(files)):
        issues.append(Issue("error", "Two photographs share the same source path."))

    by_id = {}
    for photo in metadata.get("photos") or []:
        if not isinstance(photo, dict):
            issues.append(Issue("error", "A photo entry is not an object."))
            continue
        photo_id = str(photo.get("id", ""))
        if photo_id in by_id:
            issues.append(Issue("error", f"Duplicate photo id {photo_id} in metadata.json."))
        by_id[photo_id] = photo
    expected = [item.get("id") for item in listed if isinstance(item, dict)]
    if set(by_id) != set(expected):
        missing = sorted(set(expected) - set(by_id))
        extra = sorted(set(by_id) - set(expected))
        if missing:
            issues.append(Issue("error", f"Metadata is missing photo {missing[0]}."))
        if extra:
            issues.append(Issue("error", f"Metadata lists {extra[0]}, which is not in this package."))
    mood_ids = metadata.get("_mood_ids") or set()
    scenery_ids = metadata.get("_scenery_ids") or set()
    unassigned = []
    for photo in metadata.get("photos") or []:
        if not isinstance(photo, dict):
            continue
        photo_id = str(photo.get("id", ""))
        if not str(photo.get("title", "")).strip():
            issues.append(Issue("error", f"Photo {photo_id} needs a title."))
        if not str(photo.get("blurb", "")).strip():
            issues.append(Issue("error", f"Photo {photo_id} needs a blurb."))
        if not isinstance(photo.get("story", ""), str):
            issues.append(Issue("error", f"Photo {photo_id} story must be a string."))
        scenery = photo.get("sceneryId")
        if scenery not in scenery_ids:
            issues.append(Issue("error", f"Photo {photo_id} uses unknown scenery {scenery}."))
        moods = photo.get("moodIds")
        if not isinstance(moods, list) or not moods:
            issues.append(Issue("error", f"Photo {photo_id} needs at least one mood."))
        elif any(mood not in mood_ids for mood in moods):
            unknown = next(mood for mood in moods if mood not in mood_ids)
            issues.append(Issue("error", f"Photo {photo_id} uses unknown mood {unknown}."))
        reveal = photo.get("revealId") or photo_id
        if reveal != photo_id:
            issues.append(Issue("error", f"Photo {photo_id} revealId must stay {photo_id}."))
        source = photo.get("sourceFile")
        mapped = next((item.get("file") for item in listed if item.get("id") == photo_id), None)
        if source and mapped and source != mapped:
            issues.append(Issue("error", f"Photo {photo_id} sourceFile does not match package.json."))
    _locked_photos(folder, by_id, issues)
    cover = package.get("coverPhotoId")
    if cover not in set(expected):
        issues.append(Issue("error", "Package cover must be a photo in this package."))
    trail_ids = set()
    for trail in metadata.get("trails") or []:
        if isinstance(trail, dict):
            trail_ids.update(trail.get("photoIds") or [])
    for photo_id in expected:
        if photo_id not in trail_ids:
            unassigned.append(photo_id)
    if unassigned:
        issues.append(
            Issue(
                "warning",
                "No trail includes "
                + ", ".join(unassigned)
                + ". Leave it unassigned if the trail is not established.",
            )
        )


def _locked_photos(folder: Path, by_id: dict, issues: list[Issue]) -> None:
    path = folder / "review" / "locked.json"
    if not path.is_file():
        return
    try:
        locked = load_json(path)
    except PublishError as error:
        issues.append(Issue("error", str(error)))
        return
    saved = locked.get("photos") if isinstance(locked, dict) else None
    if not isinstance(saved, dict):
        return
    for photo_id, previous in saved.items():
        photo = by_id.get(photo_id)
        if not isinstance(photo, dict) or not isinstance(previous, dict):
            continue
        if editorial_photo(photo, include_source=False) != editorial_photo(previous, include_source=False):
            issues.append(
                Issue(
                    "error",
                    f"Photo {photo_id} was already approved. "
                    "Copy its title, scenery, moods, blurb, and story unchanged, "
                    "or prepare with --all to review it again.",
                )
            )


def _trails(metadata: dict, issues: list[Issue], extra_photo_ids: set[str]) -> None:
    del extra_photo_ids
    photo_ids = {photo.get("id") for photo in metadata.get("photos") or [] if isinstance(photo, dict)}
    registry = metadata.get("_registry")
    seen = set()
    for trail in metadata.get("trails") or []:
        if not isinstance(trail, dict) or not valid_id(str(trail.get("id", ""))):
            issues.append(Issue("error", "A trail is missing a valid id."))
            continue
        if trail["id"] in seen:
            issues.append(Issue("error", f"Duplicate trail id {trail['id']}."))
        seen.add(trail["id"])
        if not str(trail.get("title", "")).strip():
            issues.append(Issue("error", f"Trail {trail['id']} needs a title."))
        if not str(trail.get("summary", "")).strip():
            issues.append(Issue("error", f"Trail {trail['id']} needs a summary."))
        if not isinstance(trail.get("story", ""), str):
            issues.append(Issue("error", f"Trail {trail['id']} story must be a string."))
        if registry is not None and trail["id"] in registry.conflict_trails:
            issues.append(
                Issue(
                    "error",
                    f"Trail {trail['id']} has conflicting approved wording. "
                    "Resolve that before publishing it again.",
                )
            )
        elif registry is not None:
            approved = registry.trails.get(trail["id"])
            if approved is None:
                issues.append(
                    Issue(
                        "notice",
                        f"New trail {trail['id']} ({str(trail.get('title', '')).strip()}). "
                        "Approval is required before publish.",
                    )
                )
            elif trail_signature(trail) != trail_signature(approved):
                issues.append(
                    Issue(
                        "notice",
                        f"Trail {trail['id']} wording differs from the shared definition. "
                        "Approval applies this revision.",
                    )
                )
        stops = trail.get("photoIds")
        if not isinstance(stops, list) or not stops:
            issues.append(Issue("error", f"Trail {trail['id']} needs at least one photo."))
            continue
        if len(stops) != len(set(stops)):
            issues.append(Issue("error", f"Trail {trail['id']} lists a photo more than once."))
        for photo_id in stops:
            if photo_id not in photo_ids:
                issues.append(
                    Issue(
                        "error",
                        f"Trail {trail['id']} lists {photo_id}, which is not in this package. "
                        "Reuse the trail id and list only this package's photos.",
                    )
                )


def _unlock_cycle_self(package: dict, issues: list[Issue]) -> None:
    unlock = package.get("unlock") or {}
    if unlock.get("type") == "packageCompleted" and unlock.get("packageId") == package.get("id"):
        issues.append(Issue("error", "A package cannot require itself."))


def _cycle(start: str, unlocks: dict[str, dict]) -> bool:
    seen = set()

    def visit(package_id: str) -> bool:
        if package_id in seen:
            return True
        seen.add(package_id)
        rule = unlocks.get(package_id) or {}
        if rule.get("type") == "packageCompleted":
            return visit(rule.get("packageId", ""))
        return False

    return visit(start)
