from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps
from PIL.ExifTags import IFD

from trailpublish.model import sha256_bytes, sha256_file

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
MAX_WIDTH = 4096
MAX_HEIGHT = 1920
WEBP_QUALITY = 85
REVIEW_EDGE = 1600


@dataclass(frozen=True)
class RenderedImage:
    sha256: str
    width: int
    height: int
    byte_count: int
    copied: bool


def list_images(photo_dir: Path) -> list[Path]:
    files = [
        path
        for path in photo_dir.iterdir()
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
    ]
    return sorted(files, key=lambda path: path.name.lower())


def capture_info(path: Path) -> dict:
    with Image.open(path) as image:
        width, height = image.size
        exif = image.getexif()
        taken = exif.get(36867) or exif.get(306)
        location = _location(exif)
    return {
        "width": width,
        "height": height,
        "captureTime": str(taken) if taken else None,
        "locationHint": location,
    }


def _location(exif) -> dict | None:
    try:
        gps = exif.get_ifd(IFD.GPSInfo)
    except Exception:
        return None
    if not gps:
        return None
    latitude = _degrees(gps.get(2), gps.get(1))
    longitude = _degrees(gps.get(4), gps.get(3))
    if latitude is None or longitude is None:
        return None
    return {
        "latitude": round(latitude, 5),
        "longitude": round(longitude, 5),
        "source": "exif",
        "unverified": True,
    }


def _degrees(value, ref) -> float | None:
    if not value or not ref:
        return None
    try:
        parts = []
        for item in value:
            if isinstance(item, tuple):
                parts.append(item[0] / item[1] if item[1] else 0)
            else:
                parts.append(float(item))
        degrees = parts[0] + parts[1] / 60 + parts[2] / 3600
    except (TypeError, ValueError, IndexError, ZeroDivisionError):
        return None
    if str(ref).upper() in {"S", "W"}:
        degrees = -degrees
    return degrees


def render_webp(source: Path, dest: Path) -> RenderedImage:
    """Write a phone-sized WebP. The file at [source] is only read."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(source) as opened:
        image = ImageOps.exif_transpose(opened)
        image.load()
        if _can_copy(source, image):
            data = source.read_bytes()
            dest.write_bytes(data)
            return RenderedImage(sha256_bytes(data), image.width, image.height, len(data), True)
        fitted = _fit(image)
        if fitted.mode not in {"RGB", "RGBA"}:
            fitted = fitted.convert("RGB")
        fitted.save(dest, format="WEBP", quality=WEBP_QUALITY, method=6)
    data = dest.read_bytes()
    with Image.open(dest) as saved:
        width, height = saved.size
    return RenderedImage(sha256_file(dest), width, height, len(data), False)


def _can_copy(source: Path, image: Image.Image) -> bool:
    if source.suffix.lower() != ".webp":
        return False
    if image.width > MAX_WIDTH or image.height > MAX_HEIGHT:
        return False
    exif = image.getexif()
    return not exif


def _fit(image: Image.Image) -> Image.Image:
    scale = min(1.0, MAX_WIDTH / image.width, MAX_HEIGHT / image.height)
    if scale >= 1:
        return image
    size = (max(1, round(image.width * scale)), max(1, round(image.height * scale)))
    return image.resize(size, Image.Resampling.LANCZOS)


def write_review_image(source: Path, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(source) as opened:
        image = ImageOps.exif_transpose(opened)
        image.load()
        scale = min(1.0, REVIEW_EDGE / max(image.width, image.height))
        if scale < 1:
            size = (max(1, round(image.width * scale)), max(1, round(image.height * scale)))
            image = image.resize(size, Image.Resampling.LANCZOS)
        _flatten(image).save(dest, format="JPEG", quality=85)


def write_contact_sheet(items: list[tuple[str, Path]], dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if not items:
        Image.new("RGB", (640, 120), (244, 241, 232)).save(dest, format="JPEG", quality=85)
        return
    columns = 4 if len(items) > 1 else 1
    thumb = 280
    label_h = 36
    pad = 16
    rows = (len(items) + columns - 1) // columns
    width = columns * thumb + (columns + 1) * pad
    height = rows * (thumb + label_h) + (rows + 1) * pad
    sheet = Image.new("RGB", (width, height), (244, 241, 232))
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.load_default()
    for index, (label, path) in enumerate(items):
        column = index % columns
        row = index // columns
        x = pad + column * (thumb + pad)
        y = pad + row * (thumb + label_h + pad)
        with Image.open(path) as image:
            frame = _flatten(ImageOps.exif_transpose(image))
            frame.thumbnail((thumb, thumb))
            sheet.paste(frame, (x, y))
        draw.text((x, y + thumb + 6), label, fill=(30, 36, 32), font=font)
    sheet.save(dest, format="JPEG", quality=85)


def write_cover(source_webp: Path, dest: Path) -> bytes:
    dest.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(source_webp) as image:
        fitted = _flatten(image)
        scale = min(1.0, 960 / max(fitted.width, fitted.height))
        if scale < 1:
            size = (max(1, round(fitted.width * scale)), max(1, round(fitted.height * scale)))
            fitted = fitted.resize(size, Image.Resampling.LANCZOS)
        fitted.save(dest, format="WEBP", quality=80, method=6)
    return dest.read_bytes()


def _flatten(image: Image.Image) -> Image.Image:
    if image.mode == "RGB":
        return image
    rgba = image.convert("RGBA")
    background = Image.new("RGB", rgba.size, (255, 255, 255))
    background.paste(rgba, mask=rgba.getchannel("A"))
    return background
