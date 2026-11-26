"""Publish Trail Pieces content packages to a private S3 bucket."""

SCHEMA_VERSION = 1
CATALOG_CACHE_CONTROL = "public, max-age=60, must-revalidate"
IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"
ID_PATTERN = r"^[a-z0-9][a-z0-9-]{0,63}$"
RELEASE_PATTERN = r"^[A-Za-z0-9._-]{1,64}$"
