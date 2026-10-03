#!/usr/bin/env python3
"""Downsize a reveal scene for Trail Pieces v3.

The high-res photo stays in shared/source. This writes a phone-sized WebP
plate, a phone-sized color photo, and aligned piece layers into the app assets.

Source layout (folder name is the scene id):

    shared/source/reveal/mountain/
      photo.jpg            # or mountain.jpg — full color
      photo-bw.jpg         # or mountain-bw.jpg — same pixel size, dark plate
      pieces/
        01-meadow.png      # full frame, same pixel size as the photo
        02-river.png

Pieces are offered in numeric order. A word after the number becomes the label.
PNG with real transparency is the preferred piece format. JPEG full frames with
a near-white cutout also work when the kept pixels match the color photo.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageOps

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE_ROOT = REPO_ROOT / "shared" / "source" / "reveal"
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "android" / "app" / "src" / "main" / "assets" / "reveal"
DEFAULT_MAX_HEIGHT = 1920
DEFAULT_MAX_WIDTH = 4096
DEFAULT_QUALITY = 85
DEFAULT_REF_TOLERANCE = 18
DEFAULT_WHITE_THRESHOLD = 245
PIECE_NAME_RE = re.compile(r"^(?P<index>\d+)(?:-(?P<label>.+))?$", re.IGNORECASE)
IMAGE_EXTS = (".jpg", ".jpeg", ".png", ".webp")


def save_webp(image: Image.Image, path: Path, quality: int) -> None:
    image.save(path, format="WEBP", quality=quality, method=6)


def fit_size(width: int, height: int, max_width: int | None, max_height: int | None) -> tuple[int, int]:
    scale = 1.0
    if max_width is not None and width > max_width:
        scale = min(scale, max_width / width)
    if max_height is not None and height > max_height:
        scale = min(scale, max_height / height)
    if scale == 1.0:
        return width, height
    return max(1, round(width * scale)), max(1, round(height * scale))


def open_image(path: Path) -> Image.Image:
    with Image.open(path) as image:
        oriented = ImageOps.exif_transpose(image)
        return oriented.copy()


def find_by_stems(directory: Path, stems: list[str]) -> Path | None:
    for stem in stems:
        for ext in IMAGE_EXTS:
            candidate = directory / f"{stem}{ext}"
            if candidate.is_file():
                return candidate
    return None


def find_color_and_plate(directory: Path, scene_id: str) -> tuple[Path, Path]:
    color = find_by_stems(directory, ["photo", scene_id, "color"])
    if color is None:
        raise FileNotFoundError(
            f"No color photo in {directory}. Expected photo.jpg or {scene_id}.jpg."
        )
    plate = find_by_stems(
        directory,
        [f"{color.stem}-bw", f"{scene_id}-bw", "bw"],
    )
    if plate is None:
        raise FileNotFoundError(
            f"No dark plate in {directory}. Expected {color.stem}-bw.jpg "
            f"(same pixel size as {color.name})."
        )
    return color, plate


def find_pieces(directory: Path) -> list[tuple[int, str | None, Path]]:
    pieces_dir = directory / "pieces"
    if not pieces_dir.is_dir():
        raise FileNotFoundError(
            f"No pieces folder in {directory}. Expected {pieces_dir} with 01.png, 02-meadow.png, ..."
        )
    found: list[tuple[int, str | None, Path]] = []
    seen: dict[int, Path] = {}
    for path in sorted(pieces_dir.iterdir()):
        if not path.is_file() or path.suffix.lower() not in IMAGE_EXTS:
            continue
        match = PIECE_NAME_RE.match(path.stem)
        if not match:
            raise ValueError(
                f"Piece file {path.name} should look like 01.png or 01-meadow.png."
            )
        index = int(match.group("index"))
        if index in seen:
            raise ValueError(f"Duplicate piece number {index}: {seen[index].name} and {path.name}")
        seen[index] = path
        raw_label = match.group("label")
        label = None
        if raw_label:
            label = raw_label.replace("-", " ").replace("_", " ").strip().lower() or None
        found.append((index, label, path))
    found.sort(key=lambda item: item[0])
    if not found:
        raise FileNotFoundError(f"No piece images in {pieces_dir}")
    return found


def alpha_from_reference(layer_rgb: Image.Image, reference_rgb: Image.Image, tolerance: int) -> Image.Image:
    diff = ImageChops.difference(layer_rgb, reference_rgb).convert("L")
    return diff.point(lambda value, tol=tolerance: 255 if value <= tol else 0)


def alpha_from_white_cutout(layer_rgb: Image.Image, threshold: int) -> Image.Image:
    channels = layer_rgb.split()
    below = [
        channel.point(lambda value, thr=threshold: 255 if value < thr else 0)
        for channel in channels
    ]
    return ImageChops.lighter(ImageChops.lighter(below[0], below[1]), below[2])


def opaque_count(alpha: Image.Image) -> int:
    histogram = alpha.histogram()
    return sum(histogram[16:])


def piece_rgba(
    path: Path,
    reference_rgb: Image.Image,
    tolerance: int,
    white_threshold: int,
) -> Image.Image:
    image = open_image(path)
    if image.mode in {"P", "PA", "LA"} or "transparency" in image.info:
        image = image.convert("RGBA")
    if image.size != reference_rgb.size:
        raise ValueError(
            f"{path.name} is {image.size[0]}x{image.size[1]}, "
            f"but the photo is {reference_rgb.size[0]}x{reference_rgb.size[1]}. "
            "Each piece must be a full frame the same pixel size as the photo."
        )
    if "A" in image.getbands():
        alpha = image.getchannel("A")
        if alpha.getextrema()[0] < 250 and opaque_count(alpha) > 32:
            return image.convert("RGBA")

    rgb = image.convert("RGB")
    alpha = alpha_from_reference(rgb, reference_rgb, tolerance)
    if opaque_count(alpha) < 32:
        alpha = alpha_from_white_cutout(rgb, white_threshold)
    if opaque_count(alpha) < 32:
        raise ValueError(
            f"{path.name} has no visible piece. Use a transparent PNG, "
            "or a JPEG whose kept pixels match the color photo and the rest is near-white."
        )
    red, green, blue = rgb.split()
    return Image.merge("RGBA", (red, green, blue, alpha))


def resize(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    if image.size == size:
        return image
    return image.resize(size, Image.Resampling.LANCZOS)


def prepare(scene_id: str, title: str, source_dir: Path, output_root: Path, args: argparse.Namespace) -> Path:
    color_path, plate_path = find_color_and_plate(source_dir, scene_id)
    piece_sources = find_pieces(source_dir)

    color = open_image(color_path).convert("RGB")
    plate = open_image(plate_path).convert("RGB")
    if plate.size != color.size:
        raise ValueError(
            f"{plate_path.name} is {plate.size[0]}x{plate.size[1]}, "
            f"but {color_path.name} is {color.size[0]}x{color.size[1]}. "
            "The dark plate must be the same pixel size as the color photo."
        )

    pieces = [
        (
            index,
            label,
            path,
            piece_rgba(path, color, args.ref_tolerance, args.white_threshold),
        )
        for index, label, path in piece_sources
    ]

    max_width = None if args.full_res else args.max_width
    max_height = None if args.full_res else args.max_height
    target = fit_size(color.width, color.height, max_width, max_height)
    color_out = resize(color, target)
    plate_out = resize(plate, target)

    output_dir = output_root / scene_id
    if output_dir.exists():
        shutil.rmtree(output_dir)
    pieces_dir = output_dir / "pieces"
    tray_dir = output_dir / "tray"
    pieces_dir.mkdir(parents=True)
    tray_dir.mkdir(parents=True)

    save_webp(plate_out, output_dir / "plate.webp", args.quality)
    save_webp(color_out, output_dir / "alive.webp", args.quality)

    manifest_pieces = []
    for index, label, source_path, rgba in pieces:
        scaled = resize(rgba, target)
        alpha = scaled.getchannel("A")
        bbox = alpha.point(lambda value: 255 if value >= 16 else 0).getbbox()
        if bbox is None:
            raise ValueError(f"{source_path.name} disappeared after downscaling.")
        left, top, right, bottom = bbox
        stem = f"piece_{index:02d}.webp"
        save_webp(scaled, pieces_dir / stem, args.quality)
        crop = scaled.crop(bbox)
        save_webp(crop, tray_dir / stem, args.quality)
        entry = {
            "id": index,
            "order": index,
            "file": f"pieces/{stem}",
            "trayFile": f"tray/{stem}",
            "bbox": {"left": left, "top": top, "right": right, "bottom": bottom},
            "sourceFile": source_path.name,
        }
        if label:
            entry["label"] = label
        manifest_pieces.append(entry)

    manifest = {
        "id": scene_id,
        "title": title,
        "mode": "reveal_v3",
        "width": target[0],
        "height": target[1],
        "plateFile": "plate.webp",
        "aliveFile": "alive.webp",
        "source": {
            "colorFile": color_path.name,
            "plateFile": plate_path.name,
            "sourceWidth": color.width,
            "sourceHeight": color.height,
        },
        "pieces": manifest_pieces,
    }
    (output_dir / "scene.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return output_dir


def title_from_id(scene_id: str) -> str:
    return scene_id.replace("-", " ").replace("_", " ").title()


def main() -> int:
    parser = argparse.ArgumentParser(description="Prepare a reveal-v3 scene from a high-res photo and pieces.")
    parser.add_argument("scene", help="Scene folder name under shared/source/reveal/")
    parser.add_argument("--title", help="Display title. Defaults to the folder name.")
    parser.add_argument("--source-root", type=Path, default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--max-height", type=int, default=DEFAULT_MAX_HEIGHT, help="Phone-sized height cap. Default 1920.")
    parser.add_argument("--max-width", type=int, default=DEFAULT_MAX_WIDTH, help="Width cap for very wide photos. Default 4096.")
    parser.add_argument("--full-res", action="store_true", help="Keep the source pixel size.")
    parser.add_argument("--quality", type=int, default=DEFAULT_QUALITY)
    parser.add_argument("--ref-tolerance", type=int, default=DEFAULT_REF_TOLERANCE)
    parser.add_argument("--white-threshold", type=int, default=DEFAULT_WHITE_THRESHOLD)
    args = parser.parse_args()

    scene_id = args.scene.strip()
    source_dir = args.source_root / scene_id
    if not source_dir.is_dir():
        print(f"Scene folder not found: {source_dir}", file=sys.stderr)
        return 1
    title = args.title or title_from_id(scene_id)
    try:
        output = prepare(scene_id, title, source_dir, args.output_root, args)
    except (FileNotFoundError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(f"Wrote {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
