from __future__ import annotations

import hashlib
import threading
from dataclasses import dataclass, field
from typing import Protocol

from trailpublish.errors import PreconditionFailed, PublishError


@dataclass
class StoredObject:
    key: str
    body: bytes
    etag: str
    content_type: str
    cache_control: str
    metadata: dict[str, str]
    content_length: int = 0


def etag_for(body: bytes) -> str:
    return '"' + hashlib.md5(body).hexdigest() + '"'


class ObjectStore(Protocol):
    def get(self, key: str) -> StoredObject | None: ...

    def head(self, key: str) -> StoredObject | None: ...

    def put(
        self,
        key: str,
        body: bytes,
        *,
        content_type: str,
        cache_control: str,
        metadata: dict[str, str],
        if_match: str | None = None,
        if_none_match: str | None = None,
    ) -> str: ...


@dataclass
class MemoryStore:
    """In-memory S3 stand-in with the same conditional writes."""

    objects: dict[str, StoredObject] = field(default_factory=dict)
    put_keys: list[str] = field(default_factory=list)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def get(self, key: str) -> StoredObject | None:
        with self._lock:
            return self.objects.get(key)

    def head(self, key: str) -> StoredObject | None:
        return self.get(key)

    def put(
        self,
        key: str,
        body: bytes,
        *,
        content_type: str,
        cache_control: str,
        metadata: dict[str, str],
        if_match: str | None = None,
        if_none_match: str | None = None,
    ) -> str:
        with self._lock:
            current = self.objects.get(key)
            if if_none_match == "*" and current is not None:
                raise PreconditionFailed(key)
            if if_match is not None and (current is None or current.etag != if_match):
                raise PreconditionFailed(key)
            stored = StoredObject(
                key=key,
                body=body,
                etag=etag_for(body),
                content_type=content_type,
                cache_control=cache_control,
                metadata=dict(metadata),
                content_length=len(body),
            )
            self.objects[key] = stored
            self.put_keys.append(key)
            return stored.etag


class S3Store:
    """Private-bucket writes. Callers never pass an object ACL."""

    def __init__(self, client, bucket: str):
        self.client = client
        self.bucket = bucket

    def get(self, key: str) -> StoredObject | None:
        head = self.head(key)
        if head is None:
            return None
        try:
            response = self.client.get_object(Bucket=self.bucket, Key=key)
        except Exception as error:
            self._raise(error, key)
        body = response["Body"].read()
        return StoredObject(
            key=key,
            body=body,
            etag=head.etag,
            content_type=head.content_type,
            cache_control=head.cache_control,
            metadata=head.metadata,
            content_length=len(body),
        )

    def head(self, key: str) -> StoredObject | None:
        try:
            response = self.client.head_object(Bucket=self.bucket, Key=key)
        except Exception as error:
            if _missing(error):
                return None
            self._raise(error, key)
        meta = {k.lower(): v for k, v in (response.get("Metadata") or {}).items()}
        return StoredObject(
            key=key,
            body=b"",
            etag=response.get("ETag", ""),
            content_type=response.get("ContentType", ""),
            cache_control=response.get("CacheControl", ""),
            metadata=meta,
            content_length=int(response.get("ContentLength", 0) or 0),
        )

    def put(
        self,
        key: str,
        body: bytes,
        *,
        content_type: str,
        cache_control: str,
        metadata: dict[str, str],
        if_match: str | None = None,
        if_none_match: str | None = None,
    ) -> str:
        kwargs = {
            "Bucket": self.bucket,
            "Key": key,
            "Body": body,
            "ContentType": content_type,
            "CacheControl": cache_control,
            "Metadata": metadata,
        }
        if if_match is not None:
            kwargs["IfMatch"] = if_match
        if if_none_match is not None:
            kwargs["IfNoneMatch"] = if_none_match
        try:
            response = self.client.put_object(**kwargs)
        except Exception as error:
            if _precondition(error):
                raise PreconditionFailed(key) from error
            self._raise(error, key)
        return response.get("ETag", etag_for(body))

    def _raise(self, error: Exception, key: str) -> None:
        raise PublishError(f"S3 request failed for {key}: {error}") from error


def _code(error: Exception) -> str:
    response = getattr(error, "response", None)
    if isinstance(response, dict):
        return str(response.get("Error", {}).get("Code", ""))
    return ""


def _status(error: Exception) -> int:
    response = getattr(error, "response", None)
    if isinstance(response, dict):
        return int(response.get("ResponseMetadata", {}).get("HTTPStatusCode", 0) or 0)
    return 0


def _missing(error: Exception) -> bool:
    return _code(error) in {"404", "NoSuchKey", "NotFound"} or _status(error) == 404


def _precondition(error: Exception) -> bool:
    return _code(error) in {"PreconditionFailed", "412"} or _status(error) == 412


def open_s3_store(config) -> S3Store:
    import boto3

    session = boto3.Session(profile_name=config.aws_profile, region_name=config.aws_region)
    return S3Store(session.client("s3"), config.bucket)


def invalidate_catalog(config) -> str:
    if not config.distribution_id:
        raise PublishError("distribution_id is required to invalidate the catalog")
    import boto3
    import time

    session = boto3.Session(profile_name=config.aws_profile, region_name=config.aws_region)
    client = session.client("cloudfront")
    path = "/" + config.catalog_key()
    response = client.create_invalidation(
        DistributionId=config.distribution_id,
        InvalidationBatch={
            "Paths": {"Quantity": 1, "Items": [path]},
            "CallerReference": str(time.time_ns()),
        },
    )
    return response["Invalidation"]["Id"]
