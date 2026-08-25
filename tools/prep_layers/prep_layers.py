#!/usr/bin/env python3
"""Prepare segmented photo layers for the Trail Pieces layers mode.

Source files are full-frame exports (same size as the photo) where the active
segment keeps the photo pixels and the cutout is near-white (JPEG has no alpha).

This script:
  1. Detects the segment mask (prefer match-to-reference; else near-white cutout)
  2. Writes aligned RGBA WebP layers (full canvas — stack cleanly in the app)
  3. Writes tight tray crops (optional muted saturation for "undeveloped" chips)
  4. Writes layers.json + a composite preview

Defaults match chop_puzzle: max width 1080, lossy WebP ~85 — sharp on a phone,
not larger than needed.

Saturation / warmth during play should be applied at runtime (Compose ColorMatrix).
Python only bakes a fixed muted tray look so chips can look undeveloped without
shipping dozens of color variants.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageEnhance, ImageFilter

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE_DIR = REPO_ROOT / "shared" / "source"
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "android" / "app" / "src" / "main" / "assets" / "layers"
DEFAULT_MAX_WIDTH = 1080
DEFAULT_QUALITY = 85
DEFAULT_WHITE_THRESHOLD = 245
DEFAULT_REF_TOLERANCE = 18
DEFAULT_MUTED_SATURATION = 0.35
LAYER_NAME_RE = re.compile(r"^(?P<stem>.+)-(?P<index>\d+)$", re.IGNORECASE)


def save_image(image: Image.Image, path: Path, fmt: str, quality: int) -> None:
    if fmt == "png":
        image.save(path, format="PNG", optimize=True)
    elif fmt == "webp":
        # Lossy WebP keeps alpha; good enough for phone photo layers.
        image.save(path, format="WEBP", quality=quality, method=6)
    else:
        raise ValueError(f"Unsupported format: {fmt}")


def layer_extension(fmt: str) -> str:
    return fmt


def scale_to_max_width(image: Image.Image, max_width: int | None) -> Image.Image:
    if max_width is None:
        return image
    width, height = image.size
    if width <= max_width:
        return image
    new_height = round(height * max_width / width)
    return image.resize((max_width, new_height), Image.Resampling.LANCZOS)


def find_layer_set(source_dir: Path, stem: str) -> tuple[Path | None, list[Path]]:
    """Return (optional reference full image, ordered layer paths) for stem."""
    reference = None
    for ext in (".jpg", ".jpeg", ".png", ".webp"):
        candidate = source_dir / f"{stem}{ext}"
        if candidate.exists():
            reference = candidate
            break

    layers: list[tuple[int, Path]] = []
    for path in source_dir.iterdir():
        if not path.is_file():
            continue
        match = LAYER_NAME_RE.match(path.stem)
        if not match or match.group("stem").lower() != stem.lower():
            continue
        if path.suffix.lower() not in {".jpg", ".jpeg", ".png", ".webp"}:
            continue
        layers.append((int(match.group("index")), path))

    layers.sort(key=lambda item: item[0])
    if not layers:
        raise FileNotFoundError(
            f"No layered sources found for '{stem}' in {source_dir} "
            f"(expected {stem}-1.jpg, {stem}-2.jpg, ...)"
        )
    return reference, [path for _, path in layers]


def alpha_from_reference(
    layer_rgb: Image.Image,
    reference_rgb: Image.Image,
    tolerance: int,
) -> Image.Image:
    """Opaque where layer ≈ reference (the kept segment)."""
    diff = ImageChops.difference(layer_rgb, reference_rgb).convert("L")
    return diff.point(lambda v: 255 if v <= tolerance else 0)


def alpha_from_white_cutout(layer_rgb: Image.Image, threshold: int) -> Image.Image:
    """Opaque where pixel is not near-white cutout fill."""
    channels = layer_rgb.split()
    below = [
        channel.point(lambda v, thr=threshold: 255 if v < thr else 0)
        for channel in channels
    ]
    return ImageChops.lighter(ImageChops.lighter(below[0], below[1]), below[2])


def feather_alpha(alpha: Image.Image, radius: float) -> Image.Image:
    if radius <= 0:
        return alpha
    return alpha.filter(ImageFilter.GaussianBlur(radius=radius))


def bounding_box(alpha: Image.Image) -> tuple[int, int, int, int] | None:
    return alpha.getbbox()


def apply_saturation(image: Image.Image, factor: float) -> Image.Image:
    """factor 0 = grayscale, 1 = original."""
    if abs(factor - 1.0) < 1e-6:
        return image
    # Enhance on RGB only; re-apply alpha after.
    if image.mode != "RGBA":
        return ImageEnhance.Color(image).enhance(factor)
    rgb = image.convert("RGB")
    muted = ImageEnhance.Color(rgb).enhance(factor)
    out = muted.convert("RGBA")
    out.putalpha(image.getchannel("A"))
    return out


def to_rgba_layer(
    source: Path,
    reference_rgb: Image.Image | None,
    max_width: int | None,
    white_threshold: int,
    ref_tolerance: int,
    feather: float,
) -> tuple[Image.Image, dict]:
    with Image.open(source) as img:
        original_size = img.size
        if img.mode == "RGBA" and "A" in img.getbands():
            rgba = img.convert("RGBA")
            rgba = scale_to_max_width(rgba, max_width)
            alpha = rgba.getchannel("A")
            # If almost fully opaque, treat as unmasked JPEG-style and rebuild alpha.
            hist = alpha.histogram()
            opaque_ratio = hist[255] / max(1, sum(hist))
            if opaque_ratio > 0.98:
                rgb = rgba.convert("RGB")
                if reference_rgb is not None:
                    alpha = alpha_from_reference(rgb, reference_rgb, ref_tolerance)
                else:
                    alpha = alpha_from_white_cutout(rgb, white_threshold)
                alpha = feather_alpha(alpha, feather)
                rgba = rgb.convert("RGBA")
                rgba.putalpha(alpha)
            else:
                if feather > 0:
                    rgba.putalpha(feather_alpha(alpha, feather))
            return rgba, {
                "sourceFile": source.name,
                "sourceWidth": original_size[0],
                "sourceHeight": original_size[1],
                "mask": "embedded-alpha",
            }

        rgb = img.convert("RGB")
        rgb = scale_to_max_width(rgb, max_width)
        if reference_rgb is not None:
            alpha = alpha_from_reference(rgb, reference_rgb, ref_tolerance)
            mask_mode = "reference"
        else:
            alpha = alpha_from_white_cutout(rgb, white_threshold)
            mask_mode = "white-cutout"
        alpha = feather_alpha(alpha, feather)
        rgba = rgb.convert("RGBA")
        rgba.putalpha(alpha)
        return rgba, {
            "sourceFile": source.name,
            "sourceWidth": original_size[0],
            "sourceHeight": original_size[1],
            "mask": mask_mode,
        }


def composite_preview(layers: list[Image.Image]) -> Image.Image:
    base = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for layer in layers:
        base = Image.alpha_composite(base, layer)
    return base


def prep_layers(
    stem: str,
    source_dir: Path,
    output_dir: Path,
    title: str | None,
    max_width: int | None,
    fmt: str,
    quality: int,
    white_threshold: int,
    ref_tolerance: int,
    feather: float,
    muted_saturation: float,
    pad_crop: int,
) -> None:
    reference_path, layer_paths = find_layer_set(source_dir, stem)

    if output_dir.exists():
        shutil.rmtree(output_dir)
    layers_dir = output_dir / "layers"
    tray_dir = output_dir / "tray"
    layers_dir.mkdir(parents=True)
    tray_dir.mkdir(parents=True)

    reference_rgb = None
    reference_meta = None
    if reference_path is not None:
        with Image.open(reference_path) as ref_img:
            reference_meta = {
                "sourceFile": reference_path.name,
                "sourceWidth": ref_img.size[0],
                "sourceHeight": ref_img.size[1],
            }
            reference_rgb = scale_to_max_width(ref_img.convert("RGB"), max_width)

    prepared: list[Image.Image] = []
    manifest_layers: list[dict] = []

    for index, path in enumerate(layer_paths, start=1):
        rgba, meta = to_rgba_layer(
            source=path,
            reference_rgb=reference_rgb,
            max_width=max_width,
            white_threshold=white_threshold,
            ref_tolerance=ref_tolerance,
            feather=feather,
        )
        if reference_rgb is not None and rgba.size != reference_rgb.size:
            raise ValueError(
                f"Size mismatch: {path.name} is {rgba.size}, "
                f"reference is {reference_rgb.size}"
            )
        if prepared and rgba.size != prepared[0].size:
            raise ValueError(
                f"Size mismatch: {path.name} is {rgba.size}, "
                f"expected {prepared[0].size}"
            )

        bbox = bounding_box(rgba.getchannel("A"))
        if bbox is None:
            raise ValueError(f"Layer has no visible pixels: {path.name}")

        ext = layer_extension(fmt)
        filename = f"layer_{index:02d}.{ext}"
        save_image(rgba, layers_dir / filename, fmt, quality)

        left, top, right, bottom = bbox
        left = max(0, left - pad_crop)
        top = max(0, top - pad_crop)
        right = min(rgba.width, right + pad_crop)
        bottom = min(rgba.height, bottom + pad_crop)
        crop = rgba.crop((left, top, right, bottom))
        tray = apply_saturation(crop, muted_saturation)
        tray_name = f"layer_{index:02d}.{ext}"
        save_image(tray, tray_dir / tray_name, fmt, quality)

        alpha_channel = rgba.getchannel("A")
        hist = alpha_channel.histogram()
        visible = sum(hist[1:])  # any alpha > 0
        coverage = visible / max(1, rgba.width * rgba.height)
        manifest_layers.append(
            {
                "id": index,
                "file": f"layers/{filename}",
                "trayFile": f"tray/{tray_name}",
                "bbox": {"left": left, "top": top, "right": right, "bottom": bottom},
                "coverage": round(coverage, 4),
                **meta,
            }
        )
        prepared.append(rgba)
        print(
            f"  layer {index}: {path.name} -> {filename} "
            f"({coverage * 100:.1f}% coverage, mask={meta['mask']})"
        )

    preview = composite_preview(prepared).convert("RGB")
    preview_name = f"preview.{layer_extension(fmt)}"
    save_image(preview, output_dir / preview_name, fmt, quality)

    width, height = prepared[0].size
    manifest = {
        "id": stem,
        "title": title or stem.replace("_", " ").replace("-", " ").title(),
        "mode": "layers",
        "width": width,
        "height": height,
        "previewFile": preview_name,
        "layerFormat": fmt,
        "layerQuality": quality if fmt != "png" else None,
        "reference": reference_meta,
        "mutedTraySaturation": muted_saturation,
        "colorControl": {
            "runtime": True,
            "note": "Adjust saturation/warmth in the app via ColorMatrix; "
            "tray assets are optionally pre-muted only.",
        },
        "layers": manifest_layers,
    }
    with (output_dir / "layers.json").open("w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")

    total = sum(p.stat().st_size for p in layers_dir.iterdir()) + sum(
        p.stat().st_size for p in tray_dir.iterdir()
    )
    print(f"Layers '{stem}' created at {output_dir}")
    print(f"  Canvas: {width}x{height}px, {len(manifest_layers)} layers")
    if reference_path:
        print(f"  Reference: {reference_path.name}")
    print(f"  Preview: {preview_name}, format {fmt}" + (f" q{quality}" if fmt != "png" else ""))
    print(f"  Layer+tray assets: {total / 1024 / 1024:.2f} MB")
    print("Rebuild the Android app to pick up the new assets.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert segmented photo layers into phone-sized Android assets."
    )
    parser.add_argument(
        "stem",
        nargs="?",
        default="deathvalley",
        help="Layer set stem (default: deathvalley → deathvalley-1..N)",
    )
    parser.add_argument("--title", default=None, help="Display title")
    parser.add_argument(
        "--source-dir",
        type=Path,
        default=DEFAULT_SOURCE_DIR,
        help="Folder containing segmented sources",
    )
    parser.add_argument(
        "--output-root",
        type=Path,
        default=DEFAULT_OUTPUT_ROOT,
        help="Root assets/layers folder",
    )
    parser.add_argument(
        "--max-width",
        type=int,
        default=DEFAULT_MAX_WIDTH,
        help=f"Scale canvas width (0 = original, default {DEFAULT_MAX_WIDTH})",
    )
    parser.add_argument(
        "--full-res",
        action="store_true",
        help="Keep original resolution (same as --max-width 0)",
    )
    parser.add_argument(
        "--format",
        choices=("webp", "png"),
        default="webp",
        help="Layer image format (default: webp — much smaller than png for photos)",
    )
    parser.add_argument(
        "--quality",
        type=int,
        default=DEFAULT_QUALITY,
        help=f"WebP quality 1-100 (default: {DEFAULT_QUALITY})",
    )
    parser.add_argument(
        "--white-threshold",
        type=int,
        default=DEFAULT_WHITE_THRESHOLD,
        help="Near-white cutout threshold when no reference image is present",
    )
    parser.add_argument(
        "--ref-tolerance",
        type=int,
        default=DEFAULT_REF_TOLERANCE,
        help="Max per-channel diff vs reference to count as segment pixels",
    )
    parser.add_argument(
        "--feather",
        type=float,
        default=0.8,
        help="Alpha edge blur radius in pixels (0 = hard mask)",
    )
    parser.add_argument(
        "--muted-sat",
        type=float,
        default=DEFAULT_MUTED_SATURATION,
        help="Saturation factor for tray chips (0=gray, 1=full color)",
    )
    parser.add_argument(
        "--pad-crop",
        type=int,
        default=4,
        help="Padding around tight tray crop in pixels",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        max_width = None if args.full_res or args.max_width == 0 else args.max_width
        output_dir = args.output_root / args.stem
        prep_layers(
            stem=args.stem,
            source_dir=args.source_dir,
            output_dir=output_dir,
            title=args.title,
            max_width=max_width,
            fmt=args.format,
            quality=args.quality,
            white_threshold=args.white_threshold,
            ref_tolerance=args.ref_tolerance,
            feather=args.feather,
            muted_saturation=args.muted_sat,
            pad_crop=args.pad_crop,
        )
        return 0
    except (FileNotFoundError, OSError, ValueError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
