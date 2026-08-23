#!/usr/bin/env python3
"""Slice a source photo into sliding-puzzle tiles for Trail Pieces."""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

from PIL import Image

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"}
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE_DIR = REPO_ROOT / "shared" / "source"
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "android" / "app" / "src" / "main" / "assets" / "puzzles"

# Good default for modern phones: sharp at 2x density on ~540pt-wide screens.
DEFAULT_MAX_WIDTH = 1080


def find_source_image(source_dir: Path, explicit: Path | None) -> Path:
    if explicit is not None:
        if not explicit.exists():
            raise FileNotFoundError(f"Input not found: {explicit}")
        return explicit

    candidates = sorted(
        p for p in source_dir.iterdir()
        if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS
    )
    if not candidates:
        raise FileNotFoundError(
            f"No images found in {source_dir}. Drop a portrait photo there first."
        )
    if len(candidates) > 1:
        print(f"Multiple images found; using {candidates[0].name}", file=sys.stderr)
    return candidates[0]


def default_grid(width: int, height: int) -> tuple[int, int]:
    if height >= width:
        return 3, 4
    return 4, 3


def split_dimension(total: int, parts: int) -> list[int]:
    """Split total pixels across parts; remainder goes to the first slices."""
    base, remainder = divmod(total, parts)
    return [base + (1 if i < remainder else 0) for i in range(parts)]


def scale_to_max_width(image: Image.Image, max_width: int | None) -> Image.Image:
    if max_width is None:
        return image
    width, height = image.size
    if width <= max_width:
        return image
    new_height = round(height * max_width / width)
    return image.resize((max_width, new_height), Image.Resampling.LANCZOS)


def save_tile(tile: Image.Image, path: Path, fmt: str, quality: int) -> None:
    if fmt == "png":
        tile.save(path, format="PNG", optimize=True)
    elif fmt == "webp":
        tile.save(path, format="WEBP", quality=quality, method=6)
    elif fmt in ("jpeg", "jpg"):
        tile.save(path, format="JPEG", quality=quality, optimize=True)
    else:
        raise ValueError(f"Unsupported format: {fmt}")


def tile_extension(fmt: str) -> str:
    return "jpg" if fmt in ("jpeg", "jpg") else fmt


def chop(
    source: Path,
    output_dir: Path,
    puzzle_id: str,
    cols: int,
    rows: int,
    title: str | None,
    max_width: int | None,
    fmt: str,
    quality: int,
) -> None:
    if output_dir.exists():
        shutil.rmtree(output_dir)
    tiles_dir = output_dir / "tiles"
    tiles_dir.mkdir(parents=True)

    with Image.open(source) as img:
        rgb = img.convert("RGB")
        original_width, original_height = rgb.size
        rgb = scale_to_max_width(rgb, max_width)
        image_width, image_height = rgb.size
        col_widths = split_dimension(image_width, cols)
        row_heights = split_dimension(image_height, rows)
        puzzle_width = sum(col_widths)
        puzzle_height = sum(row_heights)
        ext = tile_extension(fmt)

        preview_name = f"preview.{ext}"
        save_tile(rgb, output_dir / preview_name, fmt, quality)

        tiles: list[dict] = []
        index = 0
        y = 0
        for row, row_height in enumerate(row_heights):
            x = 0
            for col, col_width in enumerate(col_widths):
                tile = rgb.crop((x, y, x + col_width, y + row_height))
                filename = f"tile_{row}_{col}.{ext}"
                save_tile(tile, tiles_dir / filename, fmt, quality)
                tiles.append(
                    {
                        "index": index,
                        "row": row,
                        "col": col,
                        "file": f"tiles/{filename}",
                        "width": col_width,
                        "height": row_height,
                    }
                )
                index += 1
                x += col_width
            y += row_height

    tile_width = max(col_widths)
    tile_height = max(row_heights)
    empty_index = cols * rows - 1
    manifest = {
        "id": puzzle_id,
        "title": title or source.stem.replace("_", " ").replace("-", " ").title(),
        "sourceFile": source.name,
        "sourceWidth": original_width,
        "sourceHeight": original_height,
        "puzzleWidth": puzzle_width,
        "puzzleHeight": puzzle_height,
        "cols": cols,
        "rows": rows,
        "tileWidth": tile_width,
        "tileHeight": tile_height,
        "colWidths": col_widths,
        "rowHeights": row_heights,
        "emptyIndex": empty_index,
        "previewFile": preview_name,
        "tileFormat": fmt,
        "tileQuality": quality if fmt != "png" else None,
        "tiles": tiles,
    }

    with (output_dir / "puzzle.json").open("w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")

    total_bytes = sum(p.stat().st_size for p in tiles_dir.iterdir())
    print(f"Puzzle '{puzzle_id}' created at {output_dir}")
    print(f"  Source: {original_width}x{original_height}px", end="")
    if (image_width, image_height) != (original_width, original_height):
        print(f" -> scaled to {image_width}x{image_height}px")
    else:
        print()
    print(f"  Grid: {cols}x{rows} ({len(tiles)} tiles, empty slot index {empty_index})")
    print(f"  Tile size: ~{col_widths[0]}x{row_heights[0]}px, format {fmt}")
    preview_bytes = (output_dir / preview_name).stat().st_size
    print(f"  Preview: {preview_name} ({preview_bytes / 1024:.1f} KB)")
    print(f"  Total tile assets: {total_bytes / 1024 / 1024:.2f} MB")
    print("Rebuild the Android app to pick up the new assets.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Chop a photo into puzzle tiles covering the full image."
    )
    parser.add_argument(
        "input",
        nargs="?",
        type=Path,
        help="Source image path (default: first image in shared/source/)",
    )
    parser.add_argument("--id", default="default", help="Puzzle id / assets folder name")
    parser.add_argument("--cols", type=int, default=0, help="Grid columns (0 = auto)")
    parser.add_argument("--rows", type=int, default=0, help="Grid rows (0 = auto)")
    parser.add_argument("--title", default=None, help="Display title")
    parser.add_argument(
        "--max-width",
        type=int,
        default=DEFAULT_MAX_WIDTH,
        help=f"Scale image down to this width before chopping (0 = keep original, default {DEFAULT_MAX_WIDTH})",
    )
    parser.add_argument(
        "--format",
        choices=("webp", "jpeg", "png"),
        default="webp",
        help="Tile image format (default: webp — much smaller than png for photos)",
    )
    parser.add_argument(
        "--quality",
        type=int,
        default=85,
        help="JPEG/WebP quality 1-100 (default: 85)",
    )
    parser.add_argument(
        "--full-res",
        action="store_true",
        help="Keep original resolution (same as --max-width 0)",
    )
    parser.add_argument(
        "--output-root",
        type=Path,
        default=DEFAULT_OUTPUT_ROOT,
        help="Root assets puzzles folder",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        source = find_source_image(DEFAULT_SOURCE_DIR, args.input)
        with Image.open(source) as probe:
            width, height = probe.size

        cols, rows = args.cols, args.rows
        if cols == 0 or rows == 0:
            auto_cols, auto_rows = default_grid(width, height)
            cols = cols or auto_cols
            rows = rows or auto_rows

        max_width = None if args.full_res or args.max_width == 0 else args.max_width
        output_dir = args.output_root / args.id
        chop(
            source=source,
            output_dir=output_dir,
            puzzle_id=args.id,
            cols=cols,
            rows=rows,
            title=args.title,
            max_width=max_width,
            fmt=args.format,
            quality=args.quality,
        )
        return 0
    except (FileNotFoundError, OSError, ValueError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
