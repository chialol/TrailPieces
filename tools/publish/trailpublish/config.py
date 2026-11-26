from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from trailpublish.errors import PublishError


@dataclass(frozen=True)
class PublishConfig:
    aws_profile: str
    aws_region: str
    bucket: str
    distribution_id: str
    cdn_base_url: str
    content_prefix: str
    invalidate_catalog: bool = False

    def catalog_key(self) -> str:
        return f"{self.content_prefix}catalog.json"

    def catalog_url(self) -> str:
        base = self.cdn_base_url.rstrip("/")
        return f"{base}/{self.content_prefix}catalog.json"


def load_config(path: Path) -> PublishConfig:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise PublishError(f"Config not found: {path}") from error
    except json.JSONDecodeError as error:
        raise PublishError(f"{path} is not valid JSON: {error}") from error
    if not isinstance(raw, dict):
        raise PublishError(f"{path} must be a JSON object")

    def required(name: str) -> str:
        value = raw.get(name)
        if not isinstance(value, str) or not value.strip() or value.startswith("your-"):
            raise PublishError(f"{path} needs a real {name}")
        return value.strip()

    prefix = raw.get("content_prefix", "content/v1/")
    if not isinstance(prefix, str) or not prefix.strip():
        raise PublishError("content_prefix must be a non-empty string")
    prefix = prefix.strip().strip("/") + "/"
    if ".." in prefix.split("/"):
        raise PublishError("content_prefix cannot contain ..")

    cdn = raw.get("cdn_base_url", "")
    distribution = raw.get("distribution_id", "")
    if not isinstance(cdn, str) or not isinstance(distribution, str):
        raise PublishError("cdn_base_url and distribution_id must be strings")
    cdn = cdn.strip().rstrip("/")
    if cdn and not cdn.startswith("https://"):
        raise PublishError("cdn_base_url must start with https://")

    return PublishConfig(
        aws_profile=required("aws_profile"),
        aws_region=required("aws_region"),
        bucket=required("bucket"),
        distribution_id=distribution.strip(),
        cdn_base_url=cdn,
        content_prefix=prefix,
        invalidate_catalog=bool(raw.get("invalidate_catalog", False)),
    )


def resolve_config_path(explicit: str | None) -> Path:
    if explicit:
        return Path(explicit)
    cwd = Path.cwd() / "publish.config.json"
    if cwd.is_file():
        return cwd
    bundled = Path(__file__).resolve().parents[1] / "publish.config.json"
    if bundled.is_file():
        return bundled
    example = Path(__file__).resolve().parents[1] / "publish.config.example.json"
    raise PublishError(
        "No publish.config.json found. Copy "
        f"{example} and fill in your AWS settings."
    )
