"""Copy the app's color WebPs into this folder for an offline metadata rewrite.

Reads the photos in android/app/src/main/assets/catalog/catalog.json and copies
each alive.webp into tools/transform_metadata/photos/. Those copies are gitignored.
Also writes photos/context.json: the current text, the scenery and mood ids, and
the shape to give back. ChatGPT writes photos/output/metadata.json, including trails.

From the repo:

    python tools/transform_metadata/load_photos.py
"""

from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PHOTOS = HERE / "photos"
CATALOG = ROOT / "android" / "app" / "src" / "main" / "assets" / "catalog" / "catalog.json"
REVEAL_ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets" / "reveal"


class LoadError(Exception):
    pass


def main() -> int:
    try:
        written = load()
    except LoadError as error:
        print(f"load_photos: {error}", file=sys.stderr)
        return 1
    print(f"Loaded {len(written)} photos into {PHOTOS.relative_to(ROOT).as_posix()}")
    for name in written:
        print(f"  {name}")
    print("Upload tools/transform_metadata/photos to ChatGPT.")
    print("Save its reply as tools/transform_metadata/photos/output/metadata.json")
    print("Then run: python tools/transform_metadata/apply_metadata.py")
    return 0


def load() -> list[str]:
    catalog = read_json(CATALOG)
    photos = catalog.get("photos")
    if not isinstance(photos, list) or not photos:
        raise LoadError(f"{CATALOG.relative_to(ROOT).as_posix()} has no photos")
    staged = []
    for photo in photos:
        if not isinstance(photo, dict):
            raise LoadError("catalog has a photo that is not an object")
        photo_id = required_string(photo, "id", "a catalog photo")
        reveal_id = photo.get("revealId") or photo_id
        if not isinstance(reveal_id, str) or not reveal_id.strip():
            raise LoadError(f"{photo_id} has a blank revealId")
        source = REVEAL_ASSETS / reveal_id.strip() / "alive.webp"
        if not source.is_file():
            raise LoadError(
                f"{photo_id} has no {source.relative_to(ROOT).as_posix()}. "
                "Prep that photo before loading it."
            )
        staged.append((photo, source))

    if PHOTOS.exists():
        for child in PHOTOS.iterdir():
            if child.is_file():
                child.unlink()
    PHOTOS.mkdir(parents=True, exist_ok=True)
    (HERE / "output").mkdir(parents=True, exist_ok=True)

    names = []
    context_photos = []
    for photo, source in staged:
        photo_id = photo["id"].strip()
        destination = PHOTOS / f"{photo_id}.webp"
        shutil.copyfile(source, destination)
        names.append(destination.name)
        context_photos.append(context_photo(photo, destination.name))

    context = {
        "task": (
            "Look at each webp and rewrite that photo's metadata. "
            "Keep every id. Return every photo exactly once."
        ),
        "voice": (
            "A blurb is one concrete line about what is in the picture. "
            "A story is one or two factual sentences. Omit story when you do not know a concrete fact. "
            "Do not invent a measurement, a date, or a history."
        ),
        "write": "output/metadata.json",
        "shape": {
            "trails": [
                {
                    "id": "obsidian",
                    "title": "Obsidian Loop",
                    "summary": "High-country meadows and rocky slopes.",
                    "photoIds": ["mountain", "obsidianpool"],
                }
            ],
            "photos": [
                {
                    "id": "mossyrock",
                    "title": "Mossy Rock",
                    "sceneryId": "forest",
                    "moodIds": ["quiet"],
                    "blurb": "A shaded boulder under the firs.",
                }
            ],
        },
        "rules": [
            "sceneryId is one id from sceneries.",
            "moodIds is a non-empty list of ids from moods.",
            "title and blurb are non-empty strings.",
            "story is optional. Leave the field out to clear it.",
            "Put every photo on the trail for its place.",
            "A trail id is lowercase letters and digits. Retitle a trail, or add one, when the place needs it.",
            "Do not add scenery or mood records.",
        ],
        "sceneries": brief_items(catalog.get("sceneries")),
        "moods": brief_items(catalog.get("moods")),
        "trails": [
            {
                "id": item.get("id"),
                "title": item.get("title"),
                "summary": item.get("summary") or "",
                "photoIds": item.get("photoIds") or [],
            }
            for item in catalog.get("trails") or []
            if isinstance(item, dict)
        ],
        "photos": context_photos,
    }
    (PHOTOS / "context.json").write_text(json.dumps(context, indent=2) + "\n", encoding="utf-8")
    return names


def context_photo(photo: dict, filename: str) -> dict:
    item = {
        "id": photo["id"].strip(),
        "file": filename,
        "title": photo.get("title") or "",
        "sceneryId": photo.get("sceneryId") or "",
        "moodIds": photo.get("moodIds") or [],
        "blurb": photo.get("blurb") or "",
    }
    story = photo.get("story") or ""
    if isinstance(story, str) and story.strip():
        item["story"] = story.strip()
    return item


def brief_items(items) -> list[dict]:
    if not isinstance(items, list):
        return []
    brief = []
    for item in items:
        if not isinstance(item, dict):
            continue
        brief.append(
            {
                "id": item.get("id"),
                "title": item.get("title"),
                "summary": item.get("summary") or "",
            }
        )
    return brief


def read_json(path: Path) -> dict:
    if not path.is_file():
        raise LoadError(f"missing {path.relative_to(ROOT).as_posix()}")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise LoadError(f"{path.relative_to(ROOT).as_posix()} is not valid JSON ({error})") from error
    if not isinstance(document, dict):
        raise LoadError(f"{path.relative_to(ROOT).as_posix()} must be a JSON object")
    return document


def required_string(item: dict, field: str, owner: str) -> str:
    value = item.get(field)
    if not isinstance(value, str) or not value.strip():
        raise LoadError(f"{owner} is missing {field}")
    return value.strip()


if __name__ == "__main__":
    sys.exit(main())
