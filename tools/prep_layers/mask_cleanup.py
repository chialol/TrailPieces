"""Clean layer alpha masks — keep the main blob, drop speckle noise."""

from __future__ import annotations

from PIL import Image
import numpy as np
from scipy import ndimage


def clean_alpha_mask(
    alpha: Image.Image,
    *,
    threshold: int = 64,
    open_radius: int = 3,
    close_radius: int = 2,
    keep_largest_only: bool = True,
    min_blob_ratio: float = 0.01,
) -> tuple[Image.Image, dict]:
    """
    Remove pepper noise and stray islands from a segment mask.

    Typical segment exports have one dominant blob plus tiny false-positive islands
    from reference matching. Keeping the largest connected component (after a light
    morphological open) yields tighter bounds and cleaner drag silhouettes.
    """
    source = np.array(alpha, dtype=np.uint8)
    binary = source >= threshold

    if open_radius > 0:
        footprint = np.ones((open_radius * 2 + 1, open_radius * 2 + 1), dtype=bool)
        binary = ndimage.binary_opening(binary, structure=footprint)

    labeled, component_count = ndimage.label(binary)
    if component_count == 0:
        return Image.fromarray(np.zeros_like(source), mode="L"), {
            "componentsBefore": 0,
            "componentsKept": 0,
            "removedPixels": int(np.sum(source >= threshold)),
            "coverageBefore": 0.0,
            "coverageAfter": 0.0,
        }

    sizes = ndimage.sum(binary, labeled, range(1, component_count + 1))
    largest_idx = int(np.argmax(sizes))
    largest_label = largest_idx + 1
    largest_size = float(sizes[largest_idx])

    if keep_largest_only:
        keep = labeled == largest_label
        kept_labels = 1
    else:
        min_size = largest_size * min_blob_ratio
        kept = np.zeros_like(binary, dtype=bool)
        kept_labels = 0
        for label_id, size in enumerate(sizes, start=1):
            if size >= min_size:
                kept |= labeled == label_id
                kept_labels += 1

    if close_radius > 0:
        footprint = np.ones((close_radius * 2 + 1, close_radius * 2 + 1), dtype=bool)
        keep = ndimage.binary_closing(keep, structure=footprint)

    before_pixels = int(np.sum(source >= threshold))
    after_pixels = int(np.sum(keep))
    cleaned = np.where(keep, source, 0).astype(np.uint8)

    return Image.fromarray(cleaned, mode="L"), {
        "componentsBefore": int(component_count),
        "componentsKept": int(kept_labels),
        "largestComponentPx": int(largest_size),
        "removedPixels": before_pixels - after_pixels,
        "coverageBefore": round(before_pixels / source.size, 4),
        "coverageAfter": round(after_pixels / source.size, 4),
    }


def apply_cleaned_alpha(rgba: Image.Image, cleaned_alpha: Image.Image) -> Image.Image:
    """Apply cleaned alpha; zero RGB where fully transparent."""
    rgb = np.array(rgba.convert("RGB"), dtype=np.uint8)
    alpha = np.array(cleaned_alpha, dtype=np.uint8)
    rgb[alpha == 0] = 0
    out = Image.fromarray(rgb, mode="RGB").convert("RGBA")
    out.putalpha(Image.fromarray(alpha, mode="L"))
    return out
