"""Inspect a reveal folder and check its staged meta.json.

Stage writes nothing except a small preview image. The agent writes meta.json
after looking at that preview. Commit checks the folder, then runs import_catalog.
It does not rewrite meta.json.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[2]
REVEAL = ROOT / "shared" / "source" / "reveal"
CATALOG = ROOT / "shared" / "source" / "catalog"
ASSETS = ROOT / "android" / "app" / "src" / "main" / "assets" / "reveal"
IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp")
PIECE_NAME = re.compile(r"^(\d+)(?:-.+)?$")


class StageError(Exception):
    pass


def main() -> int:
    parser = argparse.ArgumentParser(description="Stage or commit reveal metadata.")
    parser.add_argument("command", choices=("inspect", "check", "commit"))
    parser.add_argument("folder", help="shared/source/reveal/<photo-id> or an absolute path")
    args = parser.parse_args()
    try:
        folder = resolve_folder(args.folder)
        if args.command == "inspect":
            json.dump(inspect_folder(folder), sys.stdout, indent=2)
            sys.stdout.write("\n")
            return 0
        problems = validate(folder)
        if problems:
            raise StageError("\n".join(problems))
        if args.command == "check":
            print(f"ok {folder.name}")
            return 0
        commit(folder)
        return 0
    except StageError as error:
        print(f"stage_reveal: {error}", file=sys.stderr)
        return 1


def resolve_folder(raw: str) -> Path:
    path = Path(raw)
    if not path.is_absolute():
        path = ROOT / path
    path = path.resolve()
    if not path.is_dir():
        raise StageError(f"No folder at {path}")
    try:
        path.relative_to(REVEAL.resolve())
    except ValueError as error:
        raise StageError(f"{path} is not under shared/source/reveal") from error
    if path.parent.resolve() != REVEAL.resolve():
        raise StageError(f"{path} must be a scene folder, not a file inside one")
    return path


def inspect_folder(folder: Path) -> dict:
    color = find_color(folder)
    if color is None:
        raise StageError(f"No color photo in {folder.name}. Expected photo.jpg or {folder.name}.jpg.")
    with Image.open(color) as image:
        width, height = image.size
        preview = write_preview(folder.name, image)
    plate = find_plate(folder, color)
    plate_info = None
    if plate is not None:
        with Image.open(plate) as image:
            plate_info = {
                "file": plate.name,
                "width": image.size[0],
                "height": image.size[1],
                "matchesColor": image.size == (width, height),
            }
    meta_path = folder / "meta.json"
    return {
        "photoId": folder.name,
        "folder": folder.relative_to(ROOT).as_posix(),
        "color": {"file": color.name, "width": width, "height": height},
        "plate": plate_info,
        "pieces": piece_names(folder),
        "meta": read_meta(meta_path) if meta_path.is_file() else None,
        "preview": str(preview),
        "sceneries": load_list("sceneries.json", "sceneries"),
        "moods": load_list("moods.json", "moods"),
        "trails": [
            {"id": item["id"], "title": item["title"], "photoIds": item.get("photoIds", [])}
            for item in load_list("trails.json", "trails")
        ],
    }


def validate(folder: Path) -> list[str]:
    problems = []
    meta_path = folder / "meta.json"
    if not meta_path.is_file():
        return [f"{folder.name} has no meta.json"]
    try:
        meta = read_meta(meta_path)
    except StageError as error:
        return [str(error)]
    if not isinstance(meta, dict):
        return [f"{meta_path.relative_to(ROOT).as_posix()} must be a JSON object"]
    title = meta.get("title")
    scenery = meta.get("sceneryId")
    moods = meta.get("moodIds")
    blurb = meta.get("blurb")
    if not isinstance(title, str) or not title.strip():
        problems.append("title is missing")
    if not isinstance(scenery, str) or not scenery.strip():
        problems.append("sceneryId is missing")
    elif scenery not in {item["id"] for item in load_list("sceneries.json", "sceneries")}:
        problems.append(f"unknown scenery {scenery}")
    if not isinstance(moods, list) or not moods or not all(isinstance(item, str) and item.strip() for item in moods):
        problems.append("moodIds must be a non-empty list of ids")
    else:
        known = {item["id"] for item in load_list("moods.json", "moods")}
        unknown = [item for item in moods if item not in known]
        if unknown:
            problems.append(f"unknown mood {unknown[0]}")
    if not isinstance(blurb, str) or not blurb.strip():
        problems.append("blurb is missing")
    story = meta.get("story", "")
    if story is not None and not isinstance(story, str):
        problems.append("story must be a string")
    reveal = meta.get("revealId", "")
    if reveal is not None and not isinstance(reveal, str):
        problems.append("revealId must be a string")
    return problems


def commit(folder: Path) -> None:
    pieces = piece_names(folder)
    if not pieces:
        raise StageError(
            f"{folder.name} has no pieces yet. Commit waits until the cuts are saved in pieces/."
        )
    meta = read_meta(folder / "meta.json")
    reveal_id = (meta.get("revealId") or folder.name).strip()
    scene = ASSETS / reveal_id / "scene.json"
    if not scene.is_file():
        raise StageError(
            f"{folder.name} has no android/app/src/main/assets/reveal/{reveal_id}/scene.json. "
            "The dark plate has to be in the folder, then run "
            f"tools/chop_puzzle/.venv/Scripts/python tools/prep_reveal/prep_reveal.py {reveal_id}"
        )
    script = ROOT / "tools" / "import_catalog" / "import_catalog.py"
    result = subprocess.run([sys.executable, str(script)], check=False)
    if result.returncode != 0:
        raise StageError("import_catalog failed")


def find_color(folder: Path) -> Path | None:
    return find_by_stems(folder, ["photo", folder.name, "color"])


def find_plate(folder: Path, color: Path) -> Path | None:
    return find_by_stems(folder, [f"{color.stem}-bw", f"{folder.name}-bw", "bw"])


def find_by_stems(folder: Path, stems: list[str]) -> Path | None:
    for stem in stems:
        for ext in IMAGE_EXTS:
            candidate = folder / f"{stem}{ext}"
            if candidate.is_file():
                return candidate
    return None


def piece_names(folder: Path) -> list[str]:
    pieces = folder / "pieces"
    if not pieces.is_dir():
        return []
    names = []
    for path in sorted(pieces.iterdir()):
        if path.is_file() and path.suffix.lower() in IMAGE_EXTS and PIECE_NAME.match(path.stem):
            names.append(path.name)
    return names


def write_preview(photo_id: str, image: Image.Image) -> Path:
    oriented = ImageOps.exif_transpose(image)
    preview = oriented.convert("RGB")
    preview.thumbnail((1200, 1200))
    destination = Path(tempfile.gettempdir()) / "stage-reveal"
    destination.mkdir(parents=True, exist_ok=True)
    out = destination / f"{photo_id}.jpg"
    preview.save(out, "JPEG", quality=80)
    return out


def read_meta(path: Path) -> dict:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise StageError(f"{path.relative_to(ROOT).as_posix()} is not valid JSON ({error})") from error
    if not isinstance(document, dict):
        raise StageError(f"{path.relative_to(ROOT).as_posix()} must be a JSON object")
    return document


def load_list(filename: str, key: str) -> list[dict]:
    document = json.loads((CATALOG / filename).read_text(encoding="utf-8"))
    items = document.get(key)
    if not isinstance(items, list):
        raise StageError(f"{filename} must contain a {key} array")
    return items


if __name__ == "__main__":
    sys.exit(main())
