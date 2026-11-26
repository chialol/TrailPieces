"""Approved moods, sceneries, and trails from the bundled catalog and published packages.

This is a local view. It does not read S3. A package contributes only after a
successful publish writes published.json beside it. A later draft of metadata.json
does not change the shared definitions.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from trailpublish.model import (
    classification,
    dump_json,
    load_json,
    mood_signature,
    repo_root,
    scenery_signature,
    trail_signature,
)


@dataclass
class Registry:
    moods: dict[str, dict] = field(default_factory=dict)
    sceneries: dict[str, dict] = field(default_factory=dict)
    trails: dict[str, dict] = field(default_factory=dict)
    conflicts: list[str] = field(default_factory=list)
    conflict_moods: set[str] = field(default_factory=set)
    conflict_sceneries: set[str] = field(default_factory=set)
    conflict_trails: set[str] = field(default_factory=set)

    def mood_list(self) -> list[dict]:
        return [_public_mood(item) for item in self.moods.values()]

    def scenery_list(self) -> list[dict]:
        return [_public_scenery(item) for item in self.sceneries.values()]

    def trail_list(self) -> list[dict]:
        return [_public_trail(item) for item in self.trails.values()]


def assemble_registry(root: Path | None = None) -> Registry:
    root = root or repo_root()
    registry = Registry()
    catalog = root / "shared" / "source" / "catalog"
    if (catalog / "moods.json").is_file():
        for item in load_json(catalog / "moods.json").get("moods") or []:
            _add_mood(registry, item, "bundled catalog")
    if (catalog / "sceneries.json").is_file():
        for item in load_json(catalog / "sceneries.json").get("sceneries") or []:
            _add_scenery(registry, item, "bundled catalog")
    if (catalog / "trails.json").is_file():
        for item in load_json(catalog / "trails.json").get("trails") or []:
            _add_trail(registry, item, "bundled", "bundled catalog")
    assets = root / "assets"
    if assets.is_dir():
        for package_dir in sorted(path for path in assets.iterdir() if path.is_dir()):
            snapshot = package_dir / "published.json"
            if not snapshot.is_file():
                continue
            published = load_json(snapshot)
            if not isinstance(published, dict):
                continue
            source = package_dir.name
            for item in published.get("moods") or []:
                _add_mood(registry, item, source)
            for item in published.get("sceneries") or []:
                _add_scenery(registry, item, source)
            for item in published.get("trails") or []:
                _add_trail(registry, item, source, source)
    return registry


def mark_published(folder: Path, release: str) -> None:
    """Record the definitions that just shipped. Draft edits stay in metadata.json."""
    folder = folder.resolve()
    metadata = load_json(folder / "metadata.json")
    snapshot = {
        "release": str(release),
        "moods": [classification(item, scenery=False) for item in metadata.get("moods") or [] if isinstance(item, dict)],
        "sceneries": [classification(item, scenery=True) for item in metadata.get("sceneries") or [] if isinstance(item, dict)],
        "trails": [_public_trail(item) for item in metadata.get("trails") or [] if isinstance(item, dict)],
    }
    (folder / "published.json").write_bytes(dump_json(snapshot))
    package_path = folder / "package.json"
    package = load_json(package_path)
    package["publishedRelease"] = str(release)
    package_path.write_bytes(dump_json(package))


def _add_mood(registry: Registry, item: dict, source: str) -> None:
    if not isinstance(item, dict) or not item.get("id"):
        return
    current = registry.moods.get(item["id"])
    if current is None:
        registry.moods[item["id"]] = classification(item, scenery=False)
        return
    if mood_signature(current) != mood_signature(item):
        registry.conflict_moods.add(item["id"])
        registry.conflicts.append(f"Mood {item['id']} from {source} conflicts with the approved definition.")


def _add_scenery(registry: Registry, item: dict, source: str) -> None:
    if not isinstance(item, dict) or not item.get("id"):
        return
    current = registry.sceneries.get(item["id"])
    if current is None:
        registry.sceneries[item["id"]] = classification(item, scenery=True)
        return
    if scenery_signature(current) != scenery_signature(item):
        registry.conflict_sceneries.add(item["id"])
        registry.conflicts.append(f"Scenery {item['id']} from {source} conflicts with the approved definition.")


def _add_trail(registry: Registry, item: dict, package_id: str, source: str) -> None:
    if not isinstance(item, dict) or not item.get("id"):
        return
    membership = {
        "packageId": package_id,
        "photoIds": [photo_id for photo_id in item.get("photoIds") or [] if isinstance(photo_id, str)],
    }
    current = registry.trails.get(item["id"])
    if current is None:
        stored = {
            "id": item["id"],
            "title": item.get("title", ""),
            "summary": item.get("summary", ""),
            "story": item.get("story", ""),
            "memberships": [membership],
        }
        registry.trails[item["id"]] = stored
        return
    if trail_signature(current) != trail_signature(item):
        registry.conflict_trails.add(item["id"])
        registry.conflicts.append(f"Trail {item['id']} from {source} conflicts with the approved wording.")
    current.setdefault("memberships", []).append(membership)


def _public_mood(item: dict) -> dict:
    return classification(item, scenery=False)


def _public_scenery(item: dict) -> dict:
    return classification(item, scenery=True)


def _public_trail(item: dict) -> dict:
    return {
        "id": item.get("id"),
        "title": item.get("title", ""),
        "summary": item.get("summary", ""),
        "story": item.get("story", ""),
        "photoIds": [photo_id for photo_id in item.get("photoIds") or [] if isinstance(photo_id, str)],
    }
