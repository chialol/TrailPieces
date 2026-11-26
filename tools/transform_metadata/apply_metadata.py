"""Replace app photo and trail metadata with ChatGPT's metadata.json.

Reads tools/transform_metadata/photos/output/metadata.json (or
tools/transform_metadata/output/metadata.json). Checks it against the photos
in the app catalog, writes each shared/source/reveal/<id>/meta.json, and
replaces shared/source/catalog/trails.json with the trails in that file.
Then runs import_catalog so catalog.json matches.

Every photo in the app catalog must appear exactly once, and on a trail.
sceneryId and moodIds have to already exist. revealId on the current
meta.json is kept when it points at a different asset folder.

From the repo:

    python tools/transform_metadata/apply_metadata.py
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CANDIDATES = (
    HERE / "photos" / "output" / "metadata.json",
    HERE / "output" / "metadata.json",
)
CATALOG = ROOT / "android" / "app" / "src" / "main" / "assets" / "catalog" / "catalog.json"
TRAILS = ROOT / "shared" / "source" / "catalog" / "trails.json"
REVEAL = ROOT / "shared" / "source" / "reveal"
PHOTO_FIELDS = ("title", "sceneryId", "moodIds", "blurb", "story")
TRAIL_FIELDS = ("id", "title", "summary", "story", "photoIds")
TRAIL_ID = re.compile(r"^[a-z][a-z0-9]*$")


class ApplyError(Exception):
    pass


def main() -> int:
    try:
        photo_ids, trail_ids = apply()
    except ApplyError as error:
        print(f"apply_metadata: {error}", file=sys.stderr)
        return 1
    for photo_id in photo_ids:
        print(f"updated {photo_id}")
    for trail_id in trail_ids:
        print(f"updated trail {trail_id}")
    print(
        f"Wrote {len(photo_ids)} meta.json files, {len(trail_ids)} trails, "
        "and refreshed catalog.json"
    )
    return 0


def apply() -> tuple[list[str], list[str]]:
    catalog = read_json(CATALOG, "catalog")
    source, document = read_metadata()
    print(f"read {source.relative_to(ROOT).as_posix()}", flush=True)
    incoming = read_photos(document)
    current_ids = [photo["id"].strip() for photo in catalog_photos(catalog)]
    current = set(current_ids)
    sceneries = {item["id"] for item in registry(catalog, "sceneries")}
    moods = {item["id"] for item in registry(catalog, "moods")}

    missing = [photo_id for photo_id in current_ids if photo_id not in incoming]
    if missing:
        raise ApplyError(f"metadata.json is missing {', '.join(missing)}")
    extra = [photo_id for photo_id in incoming if photo_id not in current]
    if extra:
        raise ApplyError(f"metadata.json lists {extra[0]}, which is not a photo in the app")

    prepared = []
    for photo_id in current_ids:
        cleaned = validate_meta(photo_id, incoming[photo_id], sceneries, moods)
        reveal_id = preserved_reveal_id(photo_id)
        if reveal_id:
            cleaned["revealId"] = reveal_id
        prepared.append((photo_id, cleaned))

    trails = validate_trails(document.get("trails"), current_ids)

    for photo_id, meta in prepared:
        destination = REVEAL / photo_id / "meta.json"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(dump_meta(meta), encoding="utf-8")
    TRAILS.write_text(dump_trails(trails), encoding="utf-8")

    script = ROOT / "tools" / "import_catalog" / "import_catalog.py"
    result = subprocess.run([sys.executable, str(script)], check=False)
    if result.returncode != 0:
        raise ApplyError(
            "meta.json and trails.json were written, but import_catalog failed. "
            "catalog.json was not refreshed."
        )
    return [photo_id for photo_id, _meta in prepared], [trail["id"] for trail in trails]


def read_metadata() -> tuple[Path, dict]:
    found = [path for path in CANDIDATES if path.is_file()]
    if not found:
        raise ApplyError(
            "missing metadata.json. Save it as "
            "tools/transform_metadata/photos/output/metadata.json"
        )
    path = max(found, key=lambda item: item.stat().st_mtime)
    return path, read_json(path, "metadata")


def read_photos(document: dict) -> dict[str, dict]:
    photos = document.get("photos")
    if not isinstance(photos, list) or not photos:
        raise ApplyError("metadata.json must contain a photos array")
    found = {}
    for index, item in enumerate(photos, start=1):
        if not isinstance(item, dict):
            raise ApplyError(f"photo {index} in metadata.json is not an object")
        photo_id = item.get("id")
        if not isinstance(photo_id, str) or not photo_id.strip():
            raise ApplyError(f"photo {index} in metadata.json is missing id")
        photo_id = photo_id.strip()
        if photo_id in found:
            raise ApplyError(f"{photo_id} appears more than once")
        unknown = [key for key in item if key not in PHOTO_FIELDS and key != "id"]
        if unknown:
            raise ApplyError(f"{photo_id} has unknown field {unknown[0]}")
        found[photo_id] = item
    return found


def validate_trails(raw, photo_ids: list[str]) -> list[dict]:
    if not isinstance(raw, list) or not raw:
        raise ApplyError("metadata.json must contain a trails array")
    known = set(photo_ids)
    cleaned = []
    seen_ids = set()
    listed = []
    for index, item in enumerate(raw, start=1):
        if not isinstance(item, dict):
            raise ApplyError(f"trail {index} is not an object")
        unknown = [key for key in item if key not in TRAIL_FIELDS]
        if unknown:
            raise ApplyError(f"trail {index} has unknown field {unknown[0]}")
        trail_id = required_string(item, "id", f"trail {index}")
        if TRAIL_ID.match(trail_id) is None:
            raise ApplyError(f"trail id {trail_id} must be lowercase letters and digits")
        if trail_id in seen_ids:
            raise ApplyError(f"duplicate trail id {trail_id}")
        seen_ids.add(trail_id)
        title = required_string(item, "title", trail_id)
        summary = required_string(item, "summary", trail_id)
        stops = clean_stops(trail_id, item.get("photoIds"), known)
        trail = {
            "id": trail_id,
            "title": title,
            "summary": summary,
            "photoIds": stops,
        }
        if "story" in item:
            story = item.get("story")
            if not isinstance(story, str):
                raise ApplyError(f"{trail_id} story must be a string")
            if story.strip():
                trail["story"] = story.strip()
        cleaned.append(trail)
        listed.extend(stops)
    missing = [photo_id for photo_id in photo_ids if photo_id not in listed]
    if missing:
        raise ApplyError(f"no trail lists {', '.join(missing)}")
    return cleaned


def clean_stops(trail_id: str, photo_list, known: set[str]) -> list[str]:
    if not isinstance(photo_list, list) or not photo_list:
        raise ApplyError(f"trail {trail_id} photoIds must be a non-empty list of ids")
    stops = []
    for photo_id in photo_list:
        if not isinstance(photo_id, str) or not photo_id.strip():
            raise ApplyError(f"trail {trail_id} photoIds must be a list of ids")
        photo_id = photo_id.strip()
        if photo_id not in known:
            raise ApplyError(f"trail {trail_id} lists unknown photo {photo_id}")
        if photo_id in stops:
            raise ApplyError(f"trail {trail_id} lists {photo_id} more than once")
        stops.append(photo_id)
    return stops


def validate_meta(photo_id: str, meta: dict, sceneries: set[str], moods: set[str]) -> dict:
    title = required_string(meta, "title", photo_id)
    scenery = required_string(meta, "sceneryId", photo_id)
    if scenery not in sceneries:
        raise ApplyError(f"{photo_id} uses unknown scenery {scenery}")
    mood_ids = meta.get("moodIds")
    if not isinstance(mood_ids, list) or not mood_ids:
        raise ApplyError(f"{photo_id} moodIds must be a non-empty list of ids")
    cleaned_moods = []
    for mood in mood_ids:
        if not isinstance(mood, str) or not mood.strip():
            raise ApplyError(f"{photo_id} moodIds must be a non-empty list of ids")
        mood = mood.strip()
        if mood not in moods:
            raise ApplyError(f"{photo_id} uses unknown mood {mood}")
        if mood not in cleaned_moods:
            cleaned_moods.append(mood)
    blurb = meta.get("blurb")
    if not isinstance(blurb, str) or not blurb.strip():
        raise ApplyError(f"{photo_id} blurb is missing")
    cleaned = {
        "title": title,
        "sceneryId": scenery,
        "moodIds": cleaned_moods,
        "blurb": blurb.strip(),
    }
    if "story" in meta:
        story = meta.get("story")
        if not isinstance(story, str):
            raise ApplyError(f"{photo_id} story must be a string")
        if story.strip():
            cleaned["story"] = story.strip()
    return cleaned


def preserved_reveal_id(photo_id: str) -> str:
    path = REVEAL / photo_id / "meta.json"
    if not path.is_file():
        return ""
    try:
        current = json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as error:
        raise ApplyError(f"{path.relative_to(ROOT).as_posix()} is not valid JSON ({error})") from error
    if not isinstance(current, dict):
        return ""
    reveal_id = current.get("revealId") or ""
    if not isinstance(reveal_id, str):
        return ""
    reveal_id = reveal_id.strip()
    if not reveal_id or reveal_id == photo_id:
        return ""
    return reveal_id


def catalog_photos(catalog: dict) -> list[dict]:
    photos = catalog.get("photos")
    if not isinstance(photos, list) or not photos:
        raise ApplyError("catalog has no photos")
    cleaned = []
    for photo in photos:
        if not isinstance(photo, dict):
            raise ApplyError("catalog has a photo that is not an object")
        photo_id = photo.get("id")
        if not isinstance(photo_id, str) or not photo_id.strip():
            raise ApplyError("a catalog photo is missing id")
        cleaned.append(photo)
    return cleaned


def registry(catalog: dict, key: str) -> list[dict]:
    items = catalog.get(key)
    if not isinstance(items, list):
        raise ApplyError(f"catalog has no {key}")
    return [item for item in items if isinstance(item, dict) and isinstance(item.get("id"), str)]


def dump_meta(meta: dict) -> str:
    lines = [
        "{",
        f'  "title": {json.dumps(meta["title"], ensure_ascii=False)},',
        f'  "sceneryId": {json.dumps(meta["sceneryId"], ensure_ascii=False)},',
        f'  "moodIds": {json.dumps(meta["moodIds"], ensure_ascii=False)},',
        f'  "blurb": {json.dumps(meta["blurb"], ensure_ascii=False)}',
    ]
    if meta.get("story"):
        lines[-1] += ","
        lines.append(f'  "story": {json.dumps(meta["story"], ensure_ascii=False)}')
    if meta.get("revealId"):
        lines[-1] += ","
        lines.append(f'  "revealId": {json.dumps(meta["revealId"], ensure_ascii=False)}')
    lines.append("}")
    return "\n".join(lines) + "\n"


def dump_trails(trails: list[dict]) -> str:
    blocks = []
    for trail in trails:
        lines = [
            "    {",
            f'      "id": {json.dumps(trail["id"], ensure_ascii=False)},',
            f'      "title": {json.dumps(trail["title"], ensure_ascii=False)},',
            f'      "summary": {json.dumps(trail["summary"], ensure_ascii=False)},',
        ]
        if trail.get("story"):
            lines.append(f'      "story": {json.dumps(trail["story"], ensure_ascii=False)},')
        lines.append(f'      "photoIds": {json.dumps(trail["photoIds"], ensure_ascii=False)}')
        lines.append("    }")
        blocks.append("\n".join(lines))
    return '{\n  "trails": [\n' + ",\n".join(blocks) + "\n  ]\n}\n"


def read_json(path: Path, label: str) -> dict:
    if not path.is_file():
        raise ApplyError(f"missing {path.relative_to(ROOT).as_posix()}")
    try:
        document = json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as error:
        raise ApplyError(f"{path.relative_to(ROOT).as_posix()} is not valid JSON ({error})") from error
    if not isinstance(document, dict):
        raise ApplyError(f"{label} must be a JSON object")
    return document


def required_string(item: dict, field: str, owner: str) -> str:
    value = item.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ApplyError(f"{owner} is missing {field}")
    return value.strip()


if __name__ == "__main__":
    sys.exit(main())
