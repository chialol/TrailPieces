"""Copy one finished reveal folder into assets/<package> for publishing.

The source folder stays in place. A later photo with the same package name is added
beside the ones already there. Reviewed wording already in metadata.json is kept.
"""

from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from trailpublish.errors import PublishError
from trailpublish.model import empty_metadata, load_canonical, load_json, repo_root, valid_id
from trailpublish.workspace import PrepareOptions, prepare

IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp")


@dataclass
class AddReport:
    package_dir: Path
    package_id: str
    photo_id: str
    scene_dir: Path
    review_ids: list[str]
    seeded_from_meta: bool


def add_reveal(
    source: Path,
    package_name: str,
    *,
    assets_root: Path | None = None,
    render=None,
) -> AddReport:
    source = source.resolve()
    if not source.is_dir():
        raise PublishError(f"Photo folder not found: {source}")
    photo_id = source.name
    if not valid_id(photo_id):
        raise PublishError(
            f"{photo_id!r} is not a photo id. The folder name has to be the id, "
            "like shared/source/reveal/mountain."
        )
    if not valid_id(package_name):
        raise PublishError(
            f"Package name {package_name!r} must be lowercase letters, numbers, and hyphens."
        )
    color = _find(source, ["photo", photo_id, "color"])
    if color is None:
        raise PublishError(f"No color photo in {source}. Expected photo.jpg.")
    plate = _find(source, [f"{color.stem}-bw", f"{photo_id}-bw", "bw"])
    if plate is None:
        raise PublishError(
            f"No dark plate in {source}. Add {color.stem}-bw.jpg next to {color.name}, "
            "the same pixel size, then run this again."
        )
    pieces = _pieces(source)
    if not pieces:
        raise PublishError(
            f"No pieces in {source / 'pieces'}. 01.png is enough. "
            "Unzip a tracer zip into this folder so pieces/ sits next to photo.jpg."
        )

    root = assets_root if assets_root is not None else repo_root() / "assets"
    package_dir = root / package_name
    photos = package_dir / "photos"
    photos.mkdir(parents=True, exist_ok=True)
    _replace_photo(photos, photo_id, color)

    scene_dest = package_dir / "scenes" / photo_id
    renderer = render or _render_with_prep
    renderer(source, photo_id, package_dir / "scenes")
    if not (scene_dest / "scene.json").is_file():
        raise PublishError(f"Scene render did not write {scene_dest / 'scene.json'}.")

    seeded = _preseed(package_dir, package_name, photo_id, source / "meta.json")
    existing = (package_dir / "package.json").is_file()
    title = package_name.replace("-", " ").title()
    report = prepare(
        package_dir,
        PrepareOptions(
            package_id=package_name,
            title=title,
            description=f"Photographs for {title}.",
            set_details=not existing,
        ),
    )
    return AddReport(
        package_dir=package_dir,
        package_id=report.package_id,
        photo_id=photo_id,
        scene_dir=scene_dest,
        review_ids=report.review_ids,
        seeded_from_meta=seeded,
    )


def resolve_photo_folder(text: str) -> Path:
    raw = Path(text)
    if raw.is_absolute():
        return raw
    from_here = (Path.cwd() / raw).resolve()
    if from_here.is_dir():
        return from_here
    from_repo = (repo_root() / raw).resolve()
    if from_repo.is_dir():
        return from_repo
    return from_here


def _render_with_prep(source: Path, photo_id: str, scenes_root: Path) -> None:
    repo = repo_root()
    python = _chop_python(repo)
    prep = repo / "tools" / "prep_reveal" / "prep_reveal.py"
    title = _meta_title(source / "meta.json") or photo_id.replace("-", " ").title()
    scenes_root.mkdir(parents=True, exist_ok=True)
    command = [
        str(python),
        str(prep),
        photo_id,
        "--title",
        title,
        "--source-root",
        str(source.parent),
        "--output-root",
        str(scenes_root),
    ]
    completed = subprocess.run(command, cwd=repo)
    if completed.returncode != 0:
        raise PublishError(f"prep_reveal failed for {photo_id}.")


def _chop_python(repo: Path) -> Path:
    candidates = [
        repo / "tools" / "chop_puzzle" / ".venv" / "Scripts" / "python.exe",
        repo / "tools" / "chop_puzzle" / ".venv" / "bin" / "python",
    ]
    for path in candidates:
        if path.is_file():
            return path
    raise PublishError(
        "Missing tools/chop_puzzle/.venv. From the repo:\n"
        "python -m venv tools\\chop_puzzle\\.venv\n"
        "tools\\chop_puzzle\\.venv\\Scripts\\pip install -r tools\\prep_reveal\\requirements.txt"
    )


def _replace_photo(photos: Path, photo_id: str, color: Path) -> None:
    for path in photos.iterdir():
        if path.is_file() and path.stem == photo_id and path.suffix.lower() != color.suffix.lower():
            path.unlink()
    shutil.copy2(color, photos / f"{photo_id}{color.suffix.lower()}")


def _preseed(package_dir: Path, package_name: str, photo_id: str, meta_path: Path) -> bool:
    """Fill blank wording before prepare, so the review pack sees it.

    Title and blurb that are already written stay as they are.
    """
    if not meta_path.is_file():
        return False
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise PublishError(f"{meta_path} is not valid JSON: {error}") from error
    if not isinstance(meta, dict):
        return False
    metadata_path = package_dir / "metadata.json"
    if metadata_path.is_file():
        metadata = load_json(metadata_path)
        if not isinstance(metadata, dict):
            raise PublishError(f"{metadata_path} is not a JSON object.")
    else:
        metadata = empty_metadata(package_name, load_canonical())
    photos = [photo for photo in metadata.get("photos") or [] if isinstance(photo, dict)]
    photo = next((item for item in photos if item.get("id") == photo_id), None)
    if photo is None:
        photo = {
            "id": photo_id,
            "title": "",
            "sceneryId": "",
            "moodIds": [],
            "revealId": photo_id,
            "blurb": "",
            "story": "",
            "sourceFile": "",
        }
        photos.append(photo)
    if str(photo.get("title", "")).strip() and str(photo.get("blurb", "")).strip():
        return False
    changed = False
    changed = _fill_blank(photo, "title", meta.get("title")) or changed
    changed = _fill_blank(photo, "sceneryId", meta.get("sceneryId")) or changed
    changed = _fill_blank(photo, "blurb", meta.get("blurb")) or changed
    changed = _fill_blank(photo, "story", meta.get("story")) or changed
    if not photo.get("moodIds") and isinstance(meta.get("moodIds"), list) and meta["moodIds"]:
        photo["moodIds"] = list(meta["moodIds"])
        changed = True
    photo["revealId"] = photo_id
    if not changed:
        return False
    metadata["photos"] = photos
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return True


def _fill_blank(photo: dict, key: str, value) -> bool:
    if str(photo.get(key, "")).strip():
        return False
    if not isinstance(value, str) or not value.strip():
        return False
    photo[key] = value.strip()
    return True


def _meta_title(path: Path) -> str | None:
    if not path.is_file():
        return None
    try:
        meta = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None
    title = meta.get("title") if isinstance(meta, dict) else None
    if isinstance(title, str) and title.strip():
        return title.strip()
    return None


def _find(directory: Path, stems: list[str]) -> Path | None:
    for stem in stems:
        for ext in IMAGE_EXTS:
            candidate = directory / f"{stem}{ext}"
            if candidate.is_file():
                return candidate
    return None


def _pieces(directory: Path) -> list[Path]:
    pieces = directory / "pieces"
    if not pieces.is_dir():
        return []
    found = []
    for path in pieces.iterdir():
        if path.is_file() and path.suffix.lower() in IMAGE_EXTS and path.stem[:1].isdigit():
            found.append(path)
    return found
