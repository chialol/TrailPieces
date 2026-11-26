from __future__ import annotations

import json
from pathlib import Path

from PIL import Image

from trailpublish.prep_asset import add_reveal


def _reveal(folder: Path, name: str, blurb: str, color: tuple[int, int, int]) -> None:
    folder.mkdir(parents=True)
    Image.new("RGB", (32, 24), color).save(folder / "photo.jpg", format="JPEG")
    Image.new("RGB", (32, 24), (5, 5, 5)).save(folder / "photo-bw.jpg", format="JPEG")
    pieces = folder / "pieces"
    pieces.mkdir()
    Image.new("RGBA", (32, 24), (20, 80, 40, 255)).save(pieces / "01.png")
    (folder / "meta.json").write_text(
        json.dumps(
            {
                "title": name.title(),
                "sceneryId": "forest",
                "moodIds": ["quiet"],
                "blurb": blurb,
                "story": "",
            }
        ),
        encoding="utf-8",
    )


def _fake_scene(source: Path, photo_id: str, scenes_root: Path) -> None:
    dest = scenes_root / photo_id
    dest.mkdir(parents=True)
    (dest / "scene.json").write_text("{}\n", encoding="utf-8")


def test_move_creates_a_package_and_a_second_photo_keeps_reviewed_text(tmp_path: Path):
    mountain = tmp_path / "reveal" / "mountain"
    mossy = tmp_path / "reveal" / "mossyrock"
    _reveal(mountain, "mountain", "A rocky stream in the grass.", (20, 80, 40))
    _reveal(mossy, "mossyrock", "Moss on a boulder.", (90, 40, 20))
    assets = tmp_path / "assets"

    first = add_reveal(mountain, "cedar", assets_root=assets, render=_fake_scene)
    assert first.package_dir == assets / "cedar"
    assert (assets / "cedar" / "photos" / "mountain.jpg").is_file()
    assert (assets / "cedar" / "scenes" / "mountain" / "scene.json").is_file()
    assert (mountain / "photo.jpg").is_file()
    metadata = json.loads((assets / "cedar" / "metadata.json").read_text(encoding="utf-8"))
    assert metadata["photos"][0]["blurb"] == "A rocky stream in the grass."

    metadata["photos"][0]["blurb"] = "Reviewed and kept."
    (assets / "cedar" / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")

    second = add_reveal(mossy, "cedar", assets_root=assets, render=_fake_scene)
    assert second.photo_id == "mossyrock"
    updated = json.loads((assets / "cedar" / "metadata.json").read_text(encoding="utf-8"))
    by_id = {photo["id"]: photo for photo in updated["photos"]}
    assert by_id["mountain"]["blurb"] == "Reviewed and kept."
    assert by_id["mossyrock"]["blurb"] == "Moss on a boulder."
    assert (assets / "cedar" / "scenes" / "mossyrock" / "scene.json").is_file()
