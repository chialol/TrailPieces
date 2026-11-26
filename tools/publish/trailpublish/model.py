from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

from trailpublish import ID_PATTERN, SCHEMA_VERSION
from trailpublish.errors import PublishError

ID_RE = re.compile(ID_PATTERN)
RELEASE_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
VERSION_RE = re.compile(r"^\d+(\.\d+){0,3}$")

PHOTO_EDITORIAL = ("id", "title", "sceneryId", "moodIds", "revealId", "blurb", "story")
LOCAL_PHOTO_KEYS = PHOTO_EDITORIAL + ("sourceFile",)


def repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def dump_json(value) -> bytes:
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise PublishError(f"Missing {path}") from error
    except json.JSONDecodeError as error:
        raise PublishError(f"{path} is not valid JSON: {error}") from error


def is_safe_relative(path: str) -> bool:
    if not isinstance(path, str) or not path or path.startswith("/"):
        return False
    if "\\" in path or "://" in path or "//" in path:
        return False
    parts = path.split("/")
    return all(part not in {"", ".", ".."} for part in parts)


def content_key(prefix: str, relative: str) -> str:
    if not is_safe_relative(relative):
        raise PublishError(f"Unsafe content path: {relative}")
    key = f"{prefix}{relative}"
    if not key.startswith(prefix) or ".." in key.split("/"):
        raise PublishError(f"Content path escapes the content root: {relative}")
    return key


def slugify(filename: str) -> str:
    stem = Path(filename).stem.lower()
    slug = re.sub(r"[^a-z0-9]+", "-", stem).strip("-")
    slug = re.sub(r"-{2,}", "-", slug)[:64].strip("-")
    if not slug or not slug[0].isalnum():
        slug = ("photo-" + slug).strip("-")
    return slug[:64].strip("-") or "photo"


def valid_id(value: str) -> bool:
    return bool(ID_RE.fullmatch(value or ""))


def parse_unlock(value, *, where: str = "unlock") -> dict:
    if not isinstance(value, dict) or not isinstance(value.get("type"), str):
        raise PublishError(f"{where} must be an object with a type")
    kind = value["type"]
    if kind == "immediate":
        return {"type": "immediate"}
    if kind == "puzzlesCompleted":
        count = value.get("count")
        if not isinstance(count, int) or isinstance(count, bool) or count < 1:
            raise PublishError(f"{where}.count must be an integer of at least 1")
        return {"type": "puzzlesCompleted", "count": count}
    if kind == "packageCompleted":
        package_id = value.get("packageId")
        if not isinstance(package_id, str) or not valid_id(package_id):
            raise PublishError(f"{where}.packageId must be a package id")
        return {"type": "packageCompleted", "packageId": package_id}
    raise PublishError(
        f"{where}.type {kind!r} is not supported. "
        "Use immediate, puzzlesCompleted, or packageCompleted."
    )


def parse_unlock_arg(text: str) -> dict:
    if text == "immediate":
        return {"type": "immediate"}
    if text.startswith("puzzles:"):
        try:
            count = int(text.split(":", 1)[1])
        except ValueError as error:
            raise PublishError("Use puzzles:<count>, for example puzzles:3") from error
        return parse_unlock({"type": "puzzlesCompleted", "count": count})
    if text.startswith("package:"):
        return parse_unlock({"type": "packageCompleted", "packageId": text.split(":", 1)[1]})
    raise PublishError("Unlock must be immediate, puzzles:<count>, or package:<id>")


def load_canonical() -> dict:
    root = repo_root() / "shared" / "source" / "catalog"
    moods = load_json(root / "moods.json").get("moods", [])
    sceneries = load_json(root / "sceneries.json").get("sceneries", [])
    trails = load_json(root / "trails.json").get("trails", [])
    return {"moods": moods, "sceneries": sceneries, "trails": trails}


def load_bundled_catalog() -> dict:
    path = repo_root() / "android" / "app" / "src" / "main" / "assets" / "catalog" / "catalog.json"
    return load_json(path)


def bundled_photo_ids() -> set[str]:
    return {photo["id"] for photo in load_bundled_catalog().get("photos", [])}


def mood_signature(item: dict) -> tuple:
    return (item.get("id"), item.get("title"), item.get("summary", ""))


def scenery_signature(item: dict) -> tuple:
    return (item.get("id"), item.get("title"), item.get("summary", ""), item.get("story", ""))


def trail_signature(item: dict) -> tuple:
    return (item.get("id"), item.get("title"), item.get("summary", ""), item.get("story", ""))


def output_shape(package_id: str) -> dict:
    """The metadata.json object the app and the publisher both read.

    Values shown here are the field shapes. They are not facts about a photograph.
    """
    return {
        "schemaVersion": SCHEMA_VERSION,
        "packageId": package_id,
        "version": 1,
        "moods": [classification({"id": "mood-id", "title": "Name", "summary": "The atmosphere, in a few words."}, scenery=False)],
        "sceneries": [
            classification(
                {
                    "id": "scenery-id",
                    "title": "Name",
                    "summary": "What is visually present.",
                    "story": "",
                },
                scenery=True,
            )
        ],
        "photos": [
            {
                "id": "photo-id",
                "title": "Short title",
                "sceneryId": "scenery-id",
                "moodIds": ["mood-id"],
                "revealId": "photo-id",
                "blurb": "One concrete sentence about what is in the frame.",
                "story": "",
                "sourceFile": "photos/file.jpg",
            }
        ],
        "trails": [
            {
                "id": "trail-id",
                "title": "Trail name",
                "summary": "What this route is.",
                "story": "",
                "photoIds": ["photo-id"],
            }
        ],
    }


def editorial_photo(photo: dict, *, include_source: bool) -> dict:
    item = {
        "id": photo["id"],
        "title": photo.get("title", ""),
        "sceneryId": photo.get("sceneryId", ""),
        "moodIds": list(photo.get("moodIds") or []),
        "revealId": photo.get("revealId") or photo["id"],
        "blurb": photo.get("blurb", ""),
        "story": photo.get("story", ""),
    }
    if include_source:
        item["sourceFile"] = photo.get("sourceFile", "")
    return item


def classification(item: dict, *, scenery: bool) -> dict:
    value = {
        "id": item["id"],
        "title": item.get("title", ""),
        "summary": item.get("summary", ""),
    }
    if scenery:
        value["story"] = item.get("story", "")
    return value


def empty_metadata(package_id: str, canonical: dict) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "packageId": package_id,
        "version": 1,
        "moods": [classification(item, scenery=False) for item in canonical["moods"]],
        "sceneries": [classification(item, scenery=True) for item in canonical["sceneries"]],
        "photos": [],
        "trails": [],
    }


def relative_to(folder: Path, path: Path) -> str:
    return path.resolve().relative_to(folder.resolve()).as_posix()
