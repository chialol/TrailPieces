#!/usr/bin/env python3
"""Downsize a reveal scene for Trail Pieces v3.

The high-res photo stays in shared/source. This writes phone-sized WebP assets
into the app:

    plate.webp        dark photo the player starts on
    alive.webp        full color photo for the finish
    cover.webp        small color thumbnail for the scene picker
    crop/piece_NN     tight crop of each piece, drawn where it locks
    lift/piece_NN     same crop padded with an outline and soft shadow, drawn while dragging
    icon/piece_NN     small lift image for the waiting row

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
import math
import re
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageOps
from scipy import ndimage

Image.MAX_IMAGE_PIXELS = None

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE_ROOT = REPO_ROOT / "shared" / "source" / "reveal"
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "android" / "app" / "src" / "main" / "assets" / "reveal"
DEFAULT_MAX_HEIGHT = 1920
DEFAULT_MAX_WIDTH = 4096
DEFAULT_QUALITY = 88
DEFAULT_REF_TOLERANCE = 18
DEFAULT_WHITE_THRESHOLD = 245
COVER_MAX = 720
ICON_MAX_WIDTH = 520
ICON_MAX_HEIGHT = 180

# Lift look, in output pixels for a ~1920px scene.
OUTLINE_RADIUS = 5
OUTLINE_SOFTEN = 0.7
OUTLINE_RGB = (250, 246, 236)
SHADOW_SIGMA = 9.0
SHADOW_DY = 7
SHADOW_ALPHA = 0.42

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


def fit_within(width: int, height: int, max_width: int, max_height: int) -> tuple[int, int]:
    scale = min(max_width / width, max_height / height, 1.0)
    return max(1, round(width * scale)), max(1, round(height * scale))


def open_image(path: Path) -> Image.Image:
    with Image.open(path) as image:
        oriented = ImageOps.exif_transpose(image)
        return oriented.copy()


def resize_rgb(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    if image.size == size:
        return image
    return image.resize(size, Image.Resampling.LANCZOS)


def resize_rgba(image: Image.Image, size: tuple[int, int], box: tuple[float, float, float, float] | None = None) -> Image.Image:
    """Resize with premultiplied alpha so edges don't pick up dark or light fringes."""
    if image.size == size and box is None:
        return image
    premultiplied = image.convert("RGBa")
    return premultiplied.resize(size, Image.Resampling.LANCZOS, box=box).convert("RGBA")


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


def scaled_crop(
    source: Image.Image,
    scale_x: float,
    scale_y: float,
    target_size: tuple[int, int],
) -> tuple[Image.Image, tuple[int, int, int, int]]:
    """Crop a full-frame piece to its bbox in output pixels, resampling only that region."""
    source_bbox = source.getchannel("A").point(lambda value: 255 if value >= 8 else 0).getbbox()
    if source_bbox is None:
        raise ValueError("piece is fully transparent")
    left, top, right, bottom = source_bbox
    out_left = max(0, math.floor(left * scale_x) - 1)
    out_top = max(0, math.floor(top * scale_y) - 1)
    out_right = min(target_size[0], math.ceil(right * scale_x) + 1)
    out_bottom = min(target_size[1], math.ceil(bottom * scale_y) + 1)
    box = (
        out_left / scale_x,
        out_top / scale_y,
        out_right / scale_x,
        out_bottom / scale_y,
    )
    size = (out_right - out_left, out_bottom - out_top)
    crop = resize_rgba(source, size, box=box)
    return crop, (out_left, out_top, out_right, out_bottom)


def disk(radius: int) -> np.ndarray:
    y, x = np.ogrid[-radius : radius + 1, -radius : radius + 1]
    return (x * x + y * y) <= radius * radius


def lift_pad() -> int:
    return OUTLINE_RADIUS + math.ceil(SHADOW_SIGMA * 3) + SHADOW_DY + 2


def build_lift(crop: Image.Image, pad: int) -> Image.Image:
    """Crop padded with a soft ivory outline and drop shadow, following the piece edge."""
    premultiplied = np.asarray(crop.convert("RGBa"), dtype=np.float32) / 255.0
    height, width = premultiplied.shape[:2]
    out_h, out_w = height + 2 * pad, width + 2 * pad

    piece = np.zeros((out_h, out_w, 4), dtype=np.float32)
    piece[pad : pad + height, pad : pad + width] = premultiplied
    piece_alpha = piece[..., 3]

    outline = ndimage.grey_dilation(piece_alpha, footprint=disk(OUTLINE_RADIUS))
    outline = ndimage.gaussian_filter(outline, OUTLINE_SOFTEN)

    shadow = ndimage.gaussian_filter(outline, SHADOW_SIGMA)
    shifted = np.zeros_like(shadow)
    shifted[SHADOW_DY:, :] = shadow[:-SHADOW_DY, :]
    shadow = shifted * SHADOW_ALPHA

    outline_rgb = np.array(OUTLINE_RGB, dtype=np.float32) / 255.0
    color = outline_rgb[None, None, :] * outline[..., None]
    alpha = outline + shadow * (1.0 - outline)

    color = piece[..., :3] + color * (1.0 - piece_alpha[..., None])
    alpha = piece_alpha + alpha * (1.0 - piece_alpha)

    straight = np.zeros_like(color)
    visible = alpha > 1e-4
    straight[visible] = color[visible] / alpha[visible][:, None]
    rgba = np.concatenate([straight, alpha[..., None]], axis=-1)
    rgba = np.clip(rgba * 255.0 + 0.5, 0, 255).astype(np.uint8)
    return Image.fromarray(rgba, mode="RGBA")


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

    max_width = None if args.full_res else args.max_width
    max_height = None if args.full_res else args.max_height
    target = fit_size(color.width, color.height, max_width, max_height)
    scale_x = target[0] / color.width
    scale_y = target[1] / color.height

    output_dir = output_root / scene_id
    if output_dir.exists():
        shutil.rmtree(output_dir)
    for sub in ("crop", "lift", "icon"):
        (output_dir / sub).mkdir(parents=True)

    color_out = resize_rgb(color, target)
    save_webp(resize_rgb(plate, target), output_dir / "plate.webp", args.quality)
    save_webp(color_out, output_dir / "alive.webp", args.quality)
    save_webp(
        resize_rgb(color_out, fit_within(target[0], target[1], COVER_MAX, COVER_MAX)),
        output_dir / "cover.webp",
        args.quality,
    )

    pad = lift_pad()
    manifest_pieces = []
    for index, label, source_path in piece_sources:
        rgba = piece_rgba(source_path, color, args.ref_tolerance, args.white_threshold)
        crop, (left, top, right, bottom) = scaled_crop(rgba, scale_x, scale_y, target)
        lift = build_lift(crop, pad)
        icon = resize_rgba(lift, fit_within(lift.width, lift.height, ICON_MAX_WIDTH, ICON_MAX_HEIGHT))

        stem = f"piece_{index:02d}.webp"
        save_webp(crop, output_dir / "crop" / stem, args.quality)
        save_webp(lift, output_dir / "lift" / stem, args.quality)
        save_webp(icon, output_dir / "icon" / stem, args.quality)

        entry = {
            "id": index,
            "order": index,
            "cropFile": f"crop/{stem}",
            "liftFile": f"lift/{stem}",
            "iconFile": f"icon/{stem}",
            "bbox": {"left": left, "top": top, "right": right, "bottom": bottom},
            "sourceFile": source_path.name,
        }
        if label:
            entry["label"] = label
        manifest_pieces.append(entry)
        print(f"  {source_path.name}: {right - left}x{bottom - top} at ({left}, {top})")

    manifest = {
        "id": scene_id,
        "title": title,
        "mode": "reveal_v3",
        "width": target[0],
        "height": target[1],
        "plateFile": "plate.webp",
        "aliveFile": "alive.webp",
        "coverFile": "cover.webp",
        "liftPad": pad,
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
