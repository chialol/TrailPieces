from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from trailpublish import SCHEMA_VERSION
from trailpublish.errors import PublishError
from trailpublish.images import (
    capture_info,
    list_images,
    render_webp,
    write_contact_sheet,
    write_review_image,
)
from trailpublish.model import (
    classification,
    dump_json,
    editorial_photo,
    empty_metadata,
    load_canonical,
    load_json,
    output_shape,
    parse_unlock,
    relative_to,
    sha256_bytes,
    sha256_file,
    slugify,
    valid_id,
)
from trailpublish.registry import assemble_registry

TEMPLATE = Path(__file__).resolve().parent / "templates" / "instructions.md"


@dataclass
class PrepareOptions:
    package_id: str
    title: str
    description: str
    access: str = "public"
    unlock: dict = field(default_factory=lambda: {"type": "immediate"})
    cover_photo_id: str | None = None
    order: int = 0
    min_app_version: str | None = None
    review_all: bool = False
    set_details: bool = False


@dataclass
class PrepareReport:
    folder: Path
    package_id: str
    photo_ids: list[str]
    review_ids: list[str]
    warnings: list[str]


def prepare(folder: Path, options: PrepareOptions) -> PrepareReport:
    folder = folder.resolve()
    if not folder.is_dir():
        raise PublishError(f"Folder not found: {folder}")
    photo_dir = _photo_dir(folder)
    files = list_images(photo_dir)
    if not files:
        raise PublishError(f"No jpg, png, or webp photographs in {photo_dir}")

    warnings: list[str] = []
    _reject_duplicate_names(files)
    contents = _reject_duplicate_bytes(files, warnings)

    package = _load_package(folder)
    if package is not None:
        present = {relative_to(folder, path) for path in contents}
        for item in package.get("photos") or []:
            relative = item.get("file") if isinstance(item, dict) else None
            if relative and relative not in present:
                raise PublishError(
                    f"{relative} is missing. Removal is not automatic. "
                    "Delete that photo from package.json and metadata.json if you mean to remove it."
                )
    if package is None:
        if not valid_id(options.package_id):
            raise PublishError(f"Package id {options.package_id!r} must be lowercase letters, numbers, and hyphens")
        if not options.title.strip() or not options.description.strip():
            raise PublishError("A new package needs a title and a description")
        package = {
            "id": options.package_id,
            "title": options.title.strip(),
            "description": options.description.strip(),
            "access": options.access,
            "unlock": parse_unlock(options.unlock),
            "coverPhotoId": None,
            "order": options.order,
            "minAppVersion": options.min_app_version,
            "photos": [],
            "approval": None,
        }
    else:
        if options.package_id and options.package_id != package["id"]:
            raise PublishError(
                f"This folder is already package {package['id']}. "
                "Refusing to change the package id."
            )
        if options.set_details:
            package["title"] = options.title.strip() or package["title"]
            package["description"] = options.description.strip() or package["description"]
            package["access"] = options.access
            package["unlock"] = parse_unlock(options.unlock)
            package["order"] = options.order
            package["minAppVersion"] = options.min_app_version

    if package.get("access") != "public":
        raise PublishError("Only the public access policy can be published in this version")
    package["unlock"] = parse_unlock(package.get("unlock"), where="package unlock")

    assignments, id_warnings = _assign_ids(folder, package, contents)
    warnings.extend(id_warnings)
    package["photos"] = [{"id": item["id"], "file": item["file"]} for item in assignments]
    if options.cover_photo_id:
        package["coverPhotoId"] = options.cover_photo_id
    if package.get("coverPhotoId") not in {item["id"] for item in assignments}:
        package["coverPhotoId"] = assignments[0]["id"]

    metadata, metadata_changed = _merge_metadata(folder, package["id"], assignments)
    _write_json(folder / "package.json", package)
    if metadata_changed:
        _clear_approval(folder)
        _write_json(folder / "metadata.json", metadata)
    notes_path = folder / "notes.json"
    if not notes_path.exists():
        _write_json(notes_path, {"notes": []})

    build_dir = folder / "build" / "images"
    for item in assignments:
        render_webp(folder / item["file"], build_dir / f"{item['id']}.webp")

    review_ids = _review_ids(folder, assignments, metadata, options.review_all)
    _write_review(folder, package, metadata, assignments, review_ids)
    return PrepareReport(folder, package["id"], [item["id"] for item in assignments], review_ids, warnings)


def approve(folder: Path) -> dict:
    from trailpublish.validate import local_issues

    folder = folder.resolve()
    errors = [issue for issue in local_issues(folder) if issue.level == "error"]
    if errors:
        text = "\n".join(f"- {issue.message}" for issue in errors)
        raise PublishError(f"Fix these before approving:\n{text}")
    metadata_path = folder / "metadata.json"
    sources = {}
    package = load_json(folder / "package.json")
    for item in package["photos"]:
        sources[item["file"]] = sha256_file(folder / item["file"])
    approval = {
        "metadataSha256": sha256_bytes(metadata_path.read_bytes()),
        "sources": sources,
        "approvedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
    }
    package["approval"] = approval
    _write_json(folder / "package.json", package)
    return approval


def approval_problems(folder: Path) -> list[str]:
    package = load_json(folder / "package.json")
    approval = package.get("approval")
    if not isinstance(approval, dict):
        return ["Editorial metadata has not been approved. Run approve after review."]
    metadata_path = folder / "metadata.json"
    problems = []
    current = sha256_bytes(metadata_path.read_bytes())
    if approval.get("metadataSha256") != current:
        problems.append("Approval is stale because metadata.json changed.")
    sources = approval.get("sources") or {}
    for item in package.get("photos") or []:
        path = folder / item["file"]
        if not path.is_file():
            problems.append(f"Approved source is missing: {item['file']}")
            continue
        digest = sha256_file(path)
        if sources.get(item["file"]) != digest:
            problems.append(f"Approval is stale because {item['file']} changed.")
    return problems


def import_review(folder: Path, reviewed_path: Path) -> list[str]:
    """Merge a reviewed metadata.json into the package.

    Photographs outside the current review scope keep their approved wording.
    Research notes are stored beside the package and are not published.
    """
    folder = folder.resolve()
    package = load_json(folder / "package.json")
    current = load_json(folder / "metadata.json")
    returned = load_json(reviewed_path)
    if not isinstance(returned, dict):
        raise PublishError("Reviewed metadata must be a JSON object")
    notes = _store_review_notes(folder, reviewed_path, returned)

    known = {item["id"]: item["file"] for item in package.get("photos") or []}
    incoming = returned.get("photos")
    if not isinstance(incoming, list):
        raise PublishError("Reviewed metadata needs a photos array")
    scope = _review_scope(folder)
    by_id = {}
    for photo in current.get("photos") or []:
        if isinstance(photo, dict) and isinstance(photo.get("id"), str):
            by_id[photo["id"]] = photo
    for photo in incoming:
        if not isinstance(photo, dict) or not isinstance(photo.get("id"), str):
            raise PublishError("Each reviewed photo needs an id")
        photo_id = photo["id"]
        if photo_id not in known:
            raise PublishError(f"Reviewed photo {photo_id} is not in this package")
        previous = by_id.get(photo_id, {})
        merged = editorial_photo(photo, include_source=True)
        merged["sourceFile"] = known[photo_id]
        merged["revealId"] = photo_id
        if scope is not None and photo_id not in scope and previous:
            if editorial_photo(merged, include_source=False) != editorial_photo(previous, include_source=False):
                notes.append(f"Kept approved text for {photo_id}.")
            merged = editorial_photo(previous, include_source=True)
            merged["sourceFile"] = known[photo_id]
            merged["revealId"] = photo_id
        by_id[photo_id] = merged
    ordered = []
    for item in package["photos"]:
        if item["id"] not in by_id:
            raise PublishError(f"Metadata is missing photo {item['id']}")
        ordered.append(by_id[item["id"]])

    previous_definitions = {
        "moods": list(current.get("moods") or []),
        "sceneries": list(current.get("sceneries") or []),
    }
    if isinstance(returned.get("moods"), list):
        current["moods"] = returned["moods"]
    if isinstance(returned.get("sceneries"), list):
        current["sceneries"] = returned["sceneries"]
    if isinstance(returned.get("trails"), list):
        current["trails"] = returned["trails"]
    _restore_locked_definitions(current, previous_definitions, ordered, scope)
    current["photos"] = ordered
    current["schemaVersion"] = SCHEMA_VERSION
    current["packageId"] = package["id"]
    current["version"] = 1
    current.pop("uncertainties", None)
    current.pop("reviewNotes", None)
    _write_json(folder / "metadata.json", current)
    _clear_approval(folder)
    return notes


def load_workspace(folder: Path) -> tuple[dict, dict]:
    return load_json(folder / "package.json"), load_json(folder / "metadata.json")


def _photo_dir(folder: Path) -> Path:
    nested = folder / "photos"
    if nested.is_dir():
        return nested
    return folder


def _reject_duplicate_names(files: list[Path]) -> None:
    seen: dict[str, Path] = {}
    for path in files:
        key = path.name.lower()
        if key in seen:
            raise PublishError(f"Duplicate filename {path.name} and {seen[key].name}")
        seen[key] = path


def _reject_duplicate_bytes(files: list[Path], warnings: list[str]) -> list[Path]:
    seen: dict[str, Path] = {}
    kept = []
    for path in files:
        digest = sha256_file(path)
        if digest in seen:
            warnings.append(
                f"Skipped {path.name}; it is the same image as {seen[digest].name}"
            )
            continue
        seen[digest] = path
        kept.append(path)
    return kept


def _load_package(folder: Path) -> dict | None:
    path = folder / "package.json"
    if not path.exists():
        return None
    package = load_json(path)
    if not isinstance(package, dict) or not valid_id(str(package.get("id", ""))):
        raise PublishError(f"{path} needs a stable package id")
    return package


def _assign_ids(folder: Path, package: dict, files: list[Path]) -> tuple[list[dict], list[str]]:
    existing = {}
    for item in package.get("photos") or []:
        if isinstance(item, dict) and isinstance(item.get("file"), str) and isinstance(item.get("id"), str):
            existing[item["file"]] = item["id"]
    used = set(existing.values())
    assigned = []
    warnings = []
    for path in files:
        relative = relative_to(folder, path)
        photo_id = existing.get(relative)
        if photo_id is None:
            photo_id = _fresh_id(path.name, used)
            warnings.append(f"New photo id {photo_id} for {relative}")
        elif not valid_id(photo_id):
            raise PublishError(f"Stored photo id {photo_id!r} is not a stable id")
        used.add(photo_id)
        assigned.append({"id": photo_id, "file": relative})
    return assigned, warnings


def _fresh_id(filename: str, used: set[str]) -> str:
    base = slugify(filename)
    if base not in used:
        return base
    digest = sha256_bytes(filename.encode("utf-8"))[:6]
    candidate = f"{base[:57].strip('-')}-{digest}"
    suffix = 2
    while candidate in used:
        candidate = f"{base[:57].strip('-')}-{suffix}"
        suffix += 1
    return candidate


def _merge_metadata(folder: Path, package_id: str, assignments: list[dict]) -> tuple[dict, bool]:
    path = folder / "metadata.json"
    by_file = {item["file"]: item["id"] for item in assignments}
    if not path.exists():
        metadata = empty_metadata(package_id, load_canonical())
        metadata["photos"] = [_stub(item) for item in assignments]
        return metadata, True
    metadata = load_json(path)
    if not isinstance(metadata, dict):
        raise PublishError("metadata.json must be an object")
    photos = []
    index = {}
    for photo in metadata.get("photos") or []:
        if isinstance(photo, dict) and isinstance(photo.get("id"), str):
            index[photo["id"]] = photo
    changed = False
    for item in assignments:
        previous = index.get(item["id"])
        if previous is None:
            photos.append(_stub(item))
            changed = True
            continue
        kept = dict(previous)
        kept["id"] = item["id"]
        kept["sourceFile"] = item["file"]
        if not kept.get("revealId"):
            kept["revealId"] = item["id"]
        photos.append(kept)
        if previous.get("sourceFile") != item["file"]:
            changed = True
    if [photo.get("id") for photo in metadata.get("photos") or []] != [item["id"] for item in assignments]:
        changed = True
    metadata["photos"] = photos
    metadata["packageId"] = package_id
    metadata["schemaVersion"] = SCHEMA_VERSION
    metadata["version"] = 1
    # Drop photos whose files are gone from the written list only when the file
    # mapping says so. Editorial text on remaining photos is copied through.
    del by_file
    return metadata, changed or not path.exists()


def _stub(item: dict) -> dict:
    return {
        "id": item["id"],
        "title": "",
        "sceneryId": "",
        "moodIds": [],
        "revealId": item["id"],
        "blurb": "",
        "story": "",
        "sourceFile": item["file"],
    }


def _review_ids(folder: Path, assignments: list[dict], metadata: dict, review_all: bool) -> list[str]:
    hashes_path = folder / "review" / "hashes.json"
    previous = {}
    if hashes_path.exists():
        loaded = load_json(hashes_path)
        if isinstance(loaded, dict):
            previous = loaded
    editorial = {photo["id"]: photo for photo in metadata.get("photos") or []}
    chosen = []
    current = {}
    for item in assignments:
        digest = sha256_file(folder / item["file"])
        current[item["id"]] = digest
        photo = editorial.get(item["id"], {})
        blank = not str(photo.get("title", "")).strip() or not str(photo.get("blurb", "")).strip()
        moved = previous.get(item["id"]) != digest
        if review_all or blank or moved or item["id"] not in previous:
            chosen.append(item["id"])
    _write_json(hashes_path, current)
    return chosen


def _write_review(folder, package, metadata, assignments, review_ids: list[str]) -> None:
    review = folder / "review"
    images = review / "images"
    images.mkdir(parents=True, exist_ok=True)
    registry = assemble_registry()
    notes = _notes_by_file(folder)
    editorial = {photo["id"]: photo for photo in metadata.get("photos") or []}
    sheet_items = []
    for item in assignments:
        if item["id"] not in review_ids:
            continue
        source = folder / item["file"]
        review_image = images / f"{item['id']}.jpg"
        write_review_image(source, review_image)
        sheet_items.append((item["id"], review_image))
    write_contact_sheet(sheet_items, review / "contact-sheet.jpg")
    photos = [_context_photo(folder, item, editorial, notes, review_ids) for item in assignments]
    locked = {
        photo["id"]: photo["editorial"]
        for photo in photos
        if photo["reviewStatus"] == "approved"
    }
    _write_json(review / "locked.json", {"photos": locked})
    document = _return_document(package["id"], photos, metadata)
    context = {
        "package": {
            "id": package["id"],
            "title": package.get("title", ""),
            "description": package.get("description", ""),
        },
        "purpose": "Trail Pieces reveals this text after the player finishes rebuilding the photograph.",
        "approvedMoods": registry.mood_list(),
        "approvedSceneries": registry.scenery_list(),
        "sharedTrails": [
            {
                "id": item["id"],
                "title": item.get("title", ""),
                "summary": item.get("summary", ""),
                "story": item.get("story", ""),
                "memberships": item.get("memberships") or [],
            }
            for item in registry.trails.values()
        ],
        "conflicts": list(registry.conflicts),
        "photos": photos,
        "reviewPhotoIds": review_ids,
        "approvedPhotoIds": [photo["id"] for photo in photos if photo["reviewStatus"] == "approved"],
        "returnDocument": document,
        "output": {
            "file": "metadata.json",
            "notesFile": "review-notes.json",
            "schemaVersion": SCHEMA_VERSION,
            "version": 1,
            "packageId": package["id"],
        },
    }
    _write_json(review / "context.json", context)
    template = TEMPLATE.read_text(encoding="utf-8")
    review_text = ", ".join(review_ids) if review_ids else "(none — return the existing photos unchanged)"
    locked_text = ", ".join(context["approvedPhotoIds"]) if context["approvedPhotoIds"] else "(none)"
    example = dump_json(output_shape(package["id"])).decode("utf-8").strip()
    text = (
        template.replace("__PACKAGE_TITLE__", package.get("title") or package["id"])
        .replace("__PACKAGE_ID__", package["id"])
        .replace("__REVIEW_IDS__", review_text)
        .replace("__LOCKED_IDS__", locked_text)
        .replace("__SCHEMA_EXAMPLE__", example)
    )
    (review / "instructions.md").write_text(text, encoding="utf-8")


def _return_document(package_id: str, photos: list[dict], metadata: dict) -> dict:
    used_moods = set()
    used_sceneries = set()
    returned_photos = []
    for photo in photos:
        editorial = dict(photo["editorial"])
        editorial["sourceFile"] = photo["sourceFile"]
        returned_photos.append(editorial)
        if photo["reviewStatus"] != "approved":
            continue
        if editorial.get("sceneryId"):
            used_sceneries.add(editorial["sceneryId"])
        used_moods.update(editorial.get("moodIds") or [])
    moods = [
        classification(item, scenery=False)
        for item in metadata.get("moods") or []
        if isinstance(item, dict) and item.get("id") in used_moods
    ]
    sceneries = [
        classification(item, scenery=True)
        for item in metadata.get("sceneries") or []
        if isinstance(item, dict) and item.get("id") in used_sceneries
    ]
    trails = []
    for trail in metadata.get("trails") or []:
        if not isinstance(trail, dict):
            continue
        stops = [photo_id for photo_id in trail.get("photoIds") or [] if photo_id in {photo["id"] for photo in photos}]
        if not stops:
            continue
        trails.append(
            {
                "id": trail.get("id"),
                "title": trail.get("title", ""),
                "summary": trail.get("summary", ""),
                "story": trail.get("story", ""),
                "photoIds": stops,
            }
        )
    return {
        "schemaVersion": SCHEMA_VERSION,
        "packageId": package_id,
        "version": 1,
        "moods": moods,
        "sceneries": sceneries,
        "photos": returned_photos,
        "trails": trails,
    }


def _context_photo(folder, item, editorial, notes, review_ids: list[str]) -> dict:
    info = capture_info(folder / item["file"])
    existing = editorial.get(item["id"]) or {}
    status = "review" if item["id"] in review_ids else "approved"
    file_notes = notes.get(item["file"], [])
    return {
        "id": item["id"],
        "sourceFile": item["file"],
        "reviewStatus": status,
        "reviewImage": f"images/{item['id']}.jpg" if status == "review" else None,
        "width": info["width"],
        "height": info["height"],
        "captureTime": info["captureTime"],
        "location": {
            "photographerConfirmed": [note for note in file_notes if note["status"] == "confirmed"],
            "photographerTentative": [note for note in file_notes if note["status"] != "confirmed"],
            "machineGenerated": info["locationHint"],
        },
        "editorial": editorial_photo(existing, include_source=True) if existing else editorial_photo(
            {"id": item["id"], "revealId": item["id"], "sourceFile": item["file"]},
            include_source=True,
        ),
    }


def _notes_by_file(folder: Path) -> dict[str, list]:
    path = folder / "notes.json"
    if not path.exists():
        return {}
    raw = load_json(path)
    grouped: dict[str, list] = {}
    for note in raw.get("notes") or []:
        if isinstance(note, dict) and isinstance(note.get("file"), str):
            status = str(note.get("status") or "").strip().lower()
            if status not in {"confirmed", "tentative"}:
                status = "confirmed" if note.get("confirmed") is True else "tentative"
            grouped.setdefault(note["file"], []).append(
                {
                    "status": status,
                    "location": note.get("location", ""),
                    "trail": note.get("trail", ""),
                    "comment": note.get("comment", ""),
                }
            )
    return grouped


def _review_scope(folder: Path) -> set[str] | None:
    path = folder / "review" / "context.json"
    if not path.is_file():
        return None
    context = load_json(path)
    ids = context.get("reviewPhotoIds") if isinstance(context, dict) else None
    if not isinstance(ids, list):
        return None
    return {photo_id for photo_id in ids if isinstance(photo_id, str)}


def _store_review_notes(folder: Path, reviewed_path: Path, returned: dict) -> list[str]:
    notes = []
    payload = {}
    if isinstance(returned.get("uncertainties"), list):
        payload["uncertainties"] = returned["uncertainties"]
    if isinstance(returned.get("reviewNotes"), dict):
        payload["reviewNotes"] = returned["reviewNotes"]
    sibling = reviewed_path.parent / "review-notes.json"
    if sibling.is_file() and sibling.resolve() != (folder / "review" / "review-notes.json").resolve():
        extra = load_json(sibling)
        if isinstance(extra, dict):
            payload["reviewNotes"] = extra
    if not payload:
        return notes
    _write_json(folder / "review" / "review-notes.json", payload)
    if "uncertainties" in payload:
        _write_json(folder / "review" / "uncertainties.json", payload["uncertainties"])
    notes.append("Saved research notes in review/review-notes.json. They are not published.")
    return notes


def _restore_locked_definitions(current: dict, previous: dict, photos: list[dict], scope: set[str] | None) -> None:
    if scope is None:
        return
    locked = [photo for photo in photos if photo["id"] not in scope]
    if not locked:
        return
    previous_moods = {
        item["id"]: item for item in previous.get("moods") or [] if isinstance(item, dict) and item.get("id")
    }
    previous_sceneries = {
        item["id"]: item for item in previous.get("sceneries") or [] if isinstance(item, dict) and item.get("id")
    }
    moods = [item for item in current.get("moods") or [] if isinstance(item, dict)]
    sceneries = [item for item in current.get("sceneries") or [] if isinstance(item, dict)]
    mood_index = {item.get("id"): index for index, item in enumerate(moods)}
    scenery_index = {item.get("id"): index for index, item in enumerate(sceneries)}
    for photo in locked:
        scenery_id = photo.get("sceneryId")
        if scenery_id in previous_sceneries:
            restored = classification(previous_sceneries[scenery_id], scenery=True)
            if scenery_id in scenery_index:
                sceneries[scenery_index[scenery_id]] = restored
            else:
                scenery_index[scenery_id] = len(sceneries)
                sceneries.append(restored)
        for mood_id in photo.get("moodIds") or []:
            if mood_id not in previous_moods:
                continue
            restored = classification(previous_moods[mood_id], scenery=False)
            if mood_id in mood_index:
                moods[mood_index[mood_id]] = restored
            else:
                mood_index[mood_id] = len(moods)
                moods.append(restored)
    current["moods"] = moods
    current["sceneries"] = sceneries


def _clear_approval(folder: Path) -> None:
    path = folder / "package.json"
    if not path.exists():
        return
    package = load_json(path)
    if package.get("approval") is not None:
        package["approval"] = None
        _write_json(path, package)


def _write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(dump_json(value))
