"""Merge photo meta.json files and the catalog registries into one catalog.json.

Authoring (committed):
  shared/source/reveal/<photo-id>/meta.json
  shared/source/catalog/moods.json
  shared/source/catalog/sceneries.json
  shared/source/catalog/trails.json

Output (also committed, and the future GET /v1/catalog body):
  android/app/src/main/assets/catalog/catalog.json

Run prep_reveal for a photo before importing it. This script does not touch images.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REVEAL_SOURCE = ROOT / "shared" / "source" / "reveal"
CATALOG_SOURCE = ROOT / "shared" / "source" / "catalog"
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets"
REVEAL_ASSETS = ASSETS / "reveal"
OUTPUT = ASSETS / "catalog" / "catalog.json"


def main() -> int:
    try:
        document = build()
    except CatalogError as error:
        print(f"import_catalog: {error}", file=sys.stderr)
        return 1
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    photo_count = len(document["photos"])
    trail_count = len(document["trails"])
    photo_word = "photo" if photo_count == 1 else "photos"
    trail_word = "trail" if trail_count == 1 else "trails"
    print(f"Wrote {OUTPUT.relative_to(ROOT)} ({photo_count} {photo_word}, {trail_count} {trail_word})")
    return 0


def build() -> dict:
    moods = load_registry(CATALOG_SOURCE / "moods.json", "moods")
    sceneries = load_registry(CATALOG_SOURCE / "sceneries.json", "sceneries")
    trails = load_registry(CATALOG_SOURCE / "trails.json", "trails")
    photos = load_photos()

    require_unique((item["id"] for item in moods), "mood")
    require_unique((item["id"] for item in sceneries), "scenery")
    require_unique((item["id"] for item in trails), "trail")
    require_unique((item["id"] for item in photos), "photo")

    scenery_ids = {item["id"] for item in sceneries}
    mood_ids = {item["id"] for item in moods}
    photo_ids = {item["id"] for item in photos}

    for photo in photos:
        if photo["sceneryId"] not in scenery_ids:
            raise CatalogError(f"{photo['id']} uses unknown scenery {photo['sceneryId']}")
        unknown = [mood for mood in photo["moodIds"] if mood not in mood_ids]
        if unknown:
            raise CatalogError(f"{photo['id']} uses unknown mood {unknown[0]}")
        reveal_id = photo["revealId"]
        scene = REVEAL_ASSETS / reveal_id / "scene.json"
        if not scene.is_file():
            raise CatalogError(
                f"{photo['id']} needs assets/reveal/{reveal_id}/scene.json. Run prep_reveal first."
            )

    for trail in trails:
        require_unique(trail["photoIds"], f"photo on trail {trail['id']}")
        for photo_id in trail["photoIds"]:
            if photo_id not in photo_ids:
                raise CatalogError(f"trail {trail['id']} lists unknown photo {photo_id}")

    return {
        "version": 1,
        "moods": moods,
        "sceneries": sceneries,
        "photos": photos,
        "trails": trails,
    }


def load_photos() -> list[dict]:
    if not REVEAL_SOURCE.is_dir():
        raise CatalogError(f"missing {REVEAL_SOURCE}")
    photos = []
    for folder in sorted(path for path in REVEAL_SOURCE.iterdir() if path.is_dir()):
        meta_path = folder / "meta.json"
        if not meta_path.is_file():
            continue
        meta = read_json(meta_path)
        photo_id = folder.name
        title = required_string(meta, "title", photo_id)
        scenery_id = required_string(meta, "sceneryId", photo_id)
        mood_ids = meta.get("moodIds", [])
        if not isinstance(mood_ids, list) or not all(isinstance(item, str) and item.strip() for item in mood_ids):
            raise CatalogError(f"{photo_id} moodIds must be a list of ids")
        reveal_id = meta.get("revealId") or photo_id
        if not isinstance(reveal_id, str) or not reveal_id.strip():
            raise CatalogError(f"{photo_id} has a blank revealId")
        blurb = meta.get("blurb") or ""
        if not isinstance(blurb, str):
            raise CatalogError(f"{photo_id} blurb must be a string")
        story = optional_string(meta, "story", photo_id)
        photos.append(
            {
                "id": photo_id,
                "title": title,
                "sceneryId": scenery_id,
                "moodIds": [item.strip() for item in mood_ids],
                "revealId": reveal_id.strip(),
                "blurb": blurb,
                "story": story,
            }
        )
    if not photos:
        raise CatalogError(f"no meta.json under {REVEAL_SOURCE}")
    return photos


def load_registry(path: Path, key: str) -> list[dict]:
    document = read_json(path)
    items = document.get(key)
    if not isinstance(items, list):
        raise CatalogError(f"{path.name} must contain a {key} array")
    cleaned = []
    for item in items:
        if not isinstance(item, dict):
            raise CatalogError(f"{path.name} has an entry that is not an object")
        item_id = required_string(item, "id", path.name)
        title = required_string(item, "title", item_id)
        summary = item.get("summary") or ""
        if not isinstance(summary, str):
            raise CatalogError(f"{item_id} summary must be a string")
        story = optional_string(item, "story", item_id)
        if key == "trails":
            photo_ids = item.get("photoIds")
            if not isinstance(photo_ids, list) or not all(isinstance(stop, str) and stop.strip() for stop in photo_ids):
                raise CatalogError(f"trail {item_id} photoIds must be a list of ids")
            cleaned.append(
                {
                    "id": item_id,
                    "title": title,
                    "summary": summary,
                    "story": story,
                    "photoIds": [stop.strip() for stop in photo_ids],
                }
            )
        else:
            cleaned.append({"id": item_id, "title": title, "summary": summary, "story": story})
    return cleaned


def read_json(path: Path) -> dict:
    if not path.is_file():
        raise CatalogError(f"missing {path.relative_to(ROOT)}")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise CatalogError(f"{path.relative_to(ROOT)} is not valid JSON ({error})") from error
    if not isinstance(document, dict):
        raise CatalogError(f"{path.relative_to(ROOT)} must be a JSON object")
    return document


def optional_string(item: dict, field: str, owner: str) -> str:
    value = item.get(field) or ""
    if not isinstance(value, str):
        raise CatalogError(f"{owner} {field} must be a string")
    return value.strip()


def required_string(item: dict, field: str, owner: str) -> str:
    value = item.get(field)
    if not isinstance(value, str) or not value.strip():
        raise CatalogError(f"{owner} is missing {field}")
    return value.strip()


def require_unique(ids, kind: str) -> None:
    seen = set()
    for item_id in ids:
        if item_id in seen:
            raise CatalogError(f"duplicate {kind} id {item_id}")
        seen.add(item_id)


class CatalogError(Exception):
    pass


if __name__ == "__main__":
    sys.exit(main())
