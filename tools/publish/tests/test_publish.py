from __future__ import annotations

import json
from pathlib import Path

from PIL import Image
from PIL.ExifTags import IFD

from trailpublish.config import PublishConfig
from trailpublish.errors import ConflictError, PreconditionFailed, PublishError
from trailpublish.images import capture_info, render_webp
from trailpublish.model import is_safe_relative
from trailpublish.registry import assemble_registry
from trailpublish.publishing import list_packages, publish_package, rollback_package
from trailpublish.store import MemoryStore
from trailpublish.validate import local_issues, remote_issues
from trailpublish.workspace import PrepareOptions, approve, import_review, prepare

CONFIG = PublishConfig(
    aws_profile="test",
    aws_region="us-west-2",
    bucket="example-bucket",
    distribution_id="",
    cdn_base_url="",
    content_prefix="content/v1/",
)


def _options(**kwargs) -> PrepareOptions:
    values = dict(
        package_id="cedar",
        title="Cedar Grove",
        description="A package of forest photographs.",
        set_details=True,
    )
    values.update(kwargs)
    return PrepareOptions(**values)


def _write_photo(folder: Path, name: str, color=(20, 90, 40), size=(64, 40)) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    Image.new("RGB", size, color).save(folder / name, format="JPEG")


def _fill(folder: Path, story: str = "") -> None:
    path = folder / "metadata.json"
    metadata = json.loads(path.read_text(encoding="utf-8"))
    ids = []
    for photo in metadata["photos"]:
        photo["title"] = photo["id"].replace("-", " ").title()
        photo["sceneryId"] = "forest"
        photo["moodIds"] = ["quiet"]
        photo["blurb"] = "Moss and fir trunks fill the frame."
        photo["story"] = story
        ids.append(photo["id"])
    metadata["trails"] = [
        {
            "id": "cedar-walk",
            "title": "Cedar Walk",
            "summary": "A short forest walk.",
            "story": "",
            "photoIds": ids,
        }
    ]
    path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


def test_prepare_assigns_stable_ids_and_review_pack(tmp_path: Path):
    photos = tmp_path / "photos"
    _write_photo(photos, "Cedar Grove.jpg")
    report = prepare(tmp_path, _options())
    assert report.photo_ids == ["cedar-grove"]
    assert (tmp_path / "review" / "instructions.md").is_file()
    assert (tmp_path / "review" / "context.json").is_file()
    assert (tmp_path / "review" / "images" / "cedar-grove.jpg").is_file()
    assert (tmp_path / "review" / "contact-sheet.jpg").is_file()
    context = json.loads((tmp_path / "review" / "context.json").read_text(encoding="utf-8"))
    assert context["photos"][0]["sourceFile"] == "photos/Cedar Grove.jpg"
    assert context["photos"][0]["reviewStatus"] == "review"
    assert "quiet" in {item["id"] for item in context["approvedMoods"]}
    instructions = (tmp_path / "review" / "instructions.md").read_text(encoding="utf-8")
    assert "photography puzzle" in instructions
    assert "semantic overlap" in instructions or "compare the proposed meaning" in instructions
    assert "review-notes.json" in instructions
    assert "schemaVersion" in instructions
    assert "cedar-grove" in instructions

    metadata = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    metadata["photos"][0]["title"] = "Reviewed title"
    metadata["photos"][0]["blurb"] = "Reviewed blurb that stays."
    (tmp_path / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
    again = prepare(tmp_path, _options())
    kept = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    assert kept["photos"][0]["title"] == "Reviewed title"
    assert kept["photos"][0]["blurb"] == "Reviewed blurb that stays."
    assert again.photo_ids == ["cedar-grove"]
    assert again.review_ids == []


def test_prepare_keeps_new_photo_from_erasing_reviewed_text(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg", color=(1, 2, 3))
    prepare(tmp_path, _options())
    _fill(tmp_path)
    _write_photo(tmp_path / "photos", "two.jpg", color=(4, 5, 6))
    prepare(tmp_path, _options())
    metadata = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    by_id = {photo["id"]: photo for photo in metadata["photos"]}
    assert by_id["one"]["blurb"] == "Moss and fir trunks fill the frame."
    assert by_id["two"]["title"] == ""
    assert {photo["id"] for photo in metadata["photos"]} == {"one", "two"}


def test_import_review_merges_by_id_and_strips_notes_from_metadata(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg")
    _write_photo(tmp_path / "photos", "two.jpg", color=(9, 9, 9))
    prepare(tmp_path, _options())
    _fill(tmp_path)
    reviewed = {
        "schemaVersion": 1,
        "packageId": "cedar",
        "version": 1,
        "photos": [
            {
                "id": "two",
                "title": "Second frame",
                "sceneryId": "forest",
                "moodIds": ["quiet"],
                "revealId": "two",
                "blurb": "A second trunk.",
                "story": "",
                "sourceFile": "wrong.jpg",
            }
        ],
        "uncertainties": [{"photoId": "two", "note": "The creek name is not established."}],
    }
    reviewed_path = tmp_path / "returned.json"
    reviewed_path.write_text(json.dumps(reviewed), encoding="utf-8")
    import_review(tmp_path, reviewed_path)
    metadata = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    by_id = {photo["id"]: photo for photo in metadata["photos"]}
    assert by_id["one"]["blurb"] == "Moss and fir trunks fill the frame."
    assert by_id["two"]["title"] == "Second frame"
    assert by_id["two"]["sourceFile"] == "photos/two.jpg"
    assert "uncertainties" not in metadata
    notes = json.loads((tmp_path / "review" / "uncertainties.json").read_text(encoding="utf-8"))
    assert notes[0]["photoId"] == "two"
    package = json.loads((tmp_path / "package.json").read_text(encoding="utf-8"))
    assert package["approval"] is None


def test_validate_rejects_duplicate_and_unknown_ids(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg")
    prepare(tmp_path, _options())
    _fill(tmp_path)
    metadata = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    metadata["photos"].append(dict(metadata["photos"][0]))
    metadata["photos"][0]["sceneryId"] = "not-a-place"
    (tmp_path / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
    messages = [issue.message for issue in local_issues(tmp_path) if issue.level == "error"]
    assert any("Duplicate photo id" in message for message in messages)
    assert any("unknown scenery" in message for message in messages)


def test_duplicate_bytes_are_not_a_second_photo(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg", color=(3, 3, 3))
    _write_photo(tmp_path / "photos", "copy.jpg", color=(3, 3, 3))
    report = prepare(tmp_path, _options())
    assert len(report.photo_ids) == 1
    assert any("same image" in warning for warning in report.warnings)


def test_published_webp_drops_gps(tmp_path: Path):
    source = tmp_path / "frame.jpg"
    image = Image.new("RGB", (48, 32), (12, 40, 80))
    exif = Image.Exif()
    gps = exif.get_ifd(IFD.GPSInfo)
    gps[1] = "N"
    gps[2] = (45.0, 12.0, 0.0)
    gps[3] = "W"
    gps[4] = (121.0, 30.0, 0.0)
    image.save(source, format="JPEG", exif=exif.tobytes())
    hint = capture_info(source)["locationHint"]
    assert hint["unverified"] is True
    assert hint["latitude"] > 0
    rendered = render_webp(source, tmp_path / "frame.webp")
    with Image.open(tmp_path / "frame.webp") as saved:
        assert not saved.getexif()
    assert rendered.copied is False


def test_rejects_unsafe_paths():
    assert is_safe_relative("assets/cedar.abc.webp")
    assert not is_safe_relative("../catalog.json")
    assert not is_safe_relative("/assets/a.webp")
    assert not is_safe_relative("https://example.com/a.webp")
    assert not is_safe_relative(r"assets\a.webp")


def test_publish_catalog_and_metadata_only_update(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg", color=(30, 10, 10))
    prepare(tmp_path, _options())
    _fill(tmp_path, story="")
    approve(tmp_path)
    store = MemoryStore()
    first = publish_package(tmp_path, store, CONFIG, confirmed=True)
    assert first.release == "1"
    assert first.unchanged is False
    published = json.loads((tmp_path / "published.json").read_text(encoding="utf-8"))
    assert published["release"] == "1"
    assert any(item["id"] == "forest" for item in published["sceneries"])
    catalog = json.loads(store.get("content/v1/catalog.json").body)
    assert catalog["packages"][0]["id"] == "cedar"
    assert catalog["packages"][0]["metadata"] == "packages/cedar/releases/1/metadata.json"
    image_keys = [key for key in store.objects if "/assets/" in key]
    assert image_keys
    assert store.objects[image_keys[0]].cache_control.startswith("public, max-age=31536000")
    assert "immutable" in store.objects[image_keys[0]].cache_control
    catalog_object = store.get("content/v1/catalog.json")
    assert catalog_object.cache_control == "public, max-age=60, must-revalidate"
    assert not any(
        key.endswith("notes.json") or key.endswith("instructions.md") or "contact-sheet" in key
        for key in store.objects
    )

    metadata = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    metadata["photos"][0]["story"] = "Cedars keep their leaves through the winter."
    (tmp_path / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
    approve(tmp_path)
    puts_before = len(store.put_keys)
    second = publish_package(tmp_path, store, CONFIG, confirmed=True)
    assert second.release == "2"
    assert second.modified_photos if False else "one" in second.diff.modified_photos
    new_puts = store.put_keys[puts_before:]
    assert not any(key.startswith("content/v1/assets/") for key in new_puts)
    assert any(key.endswith("releases/2/metadata.json") for key in new_puts)
    third = publish_package(tmp_path, store, CONFIG, confirmed=True)
    assert third.unchanged is True
    assert store.put_keys[puts_before + len(new_puts) :] == []


def test_dry_run_writes_nothing(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg")
    prepare(tmp_path, _options())
    _fill(tmp_path)
    approve(tmp_path)
    store = MemoryStore()
    report = publish_package(tmp_path, store, CONFIG, dry_run=True)
    assert report.dry_run is True
    assert store.put_keys == []
    assert store.objects == {}
    assert not (tmp_path / "published.json").exists()


def test_failed_upload_leaves_the_previous_catalog(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg", color=(8, 8, 8))
    prepare(tmp_path, _options())
    _fill(tmp_path)
    approve(tmp_path)
    store = _FailOnce()
    try:
        publish_package(tmp_path, store, CONFIG, confirmed=True)
        raise AssertionError("expected a failed upload")
    except PublishError as error:
        assert "reset" in str(error)
    assert store.get("content/v1/catalog.json") is None
    assert any(key.startswith("content/v1/assets/") for key in store.objects)
    finished = publish_package(tmp_path, store, CONFIG, confirmed=True)
    assert finished.release == "1"
    catalog = json.loads(store.get("content/v1/catalog.json").body)
    assert catalog["packages"][0]["release"] == "1"
    image_puts = [key for key in store.put_keys if key.startswith("content/v1/assets/")]
    assert len(image_puts) == 1


def test_catalog_conflict_does_not_overwrite(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg", color=(2, 2, 8))
    prepare(tmp_path, _options())
    _fill(tmp_path)
    approve(tmp_path)
    store = _RaceCatalog()
    try:
        publish_package(tmp_path, store, CONFIG, confirmed=True)
        raise AssertionError("expected a conflict")
    except ConflictError:
        pass
    catalog = json.loads(store.get("content/v1/catalog.json").body)
    assert catalog["revision"] == "other"
    assert catalog["packages"] == []


def test_rollback_points_the_catalog_at_the_old_release(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg")
    prepare(tmp_path, _options())
    _fill(tmp_path)
    approve(tmp_path)
    store = MemoryStore()
    publish_package(tmp_path, store, CONFIG, confirmed=True)
    metadata = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    metadata["photos"][0]["blurb"] = "A changed blurb after the first release."
    (tmp_path / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
    approve(tmp_path)
    publish_package(tmp_path, store, CONFIG, confirmed=True)
    keys_before = set(store.objects)
    report = rollback_package(store, CONFIG, "cedar", "1", confirmed=True)
    assert report.release == "1"
    catalog = json.loads(store.get("content/v1/catalog.json").body)
    assert catalog["packages"][0]["release"] == "1"
    assert set(store.objects) == keys_before | {"content/v1/catalog.json"}
    remote = json.loads(store.get("content/v1/" + catalog["packages"][0]["metadata"]).body)
    assert remote["editorial"]["photos"][0]["blurb"] == "Moss and fir trunks fill the frame."


def test_removal_and_identity_need_explicit_flags(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg", color=(1, 1, 1))
    _write_photo(tmp_path / "photos", "two.jpg", color=(2, 2, 2))
    prepare(tmp_path, _options())
    _fill(tmp_path)
    approve(tmp_path)
    store = MemoryStore()
    publish_package(tmp_path, store, CONFIG, confirmed=True)
    package = json.loads((tmp_path / "package.json").read_text(encoding="utf-8"))
    package["photos"] = [item for item in package["photos"] if item["id"] != "two"]
    (tmp_path / "package.json").write_text(json.dumps(package), encoding="utf-8")
    (tmp_path / "photos" / "two.jpg").unlink()
    metadata = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    metadata["photos"] = [photo for photo in metadata["photos"] if photo["id"] != "two"]
    metadata["trails"][0]["photoIds"] = ["one"]
    (tmp_path / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
    approve(tmp_path)
    try:
        publish_package(tmp_path, store, CONFIG, confirmed=True)
        raise AssertionError("removal should be refused")
    except PublishError as error:
        assert "allow-removals" in str(error)
    publish_package(tmp_path, store, CONFIG, confirmed=True, allow_removals=True)

    _write_photo(tmp_path / "photos", "one.jpg", color=(9, 9, 9), size=(70, 40))
    approve(tmp_path)
    try:
        publish_package(tmp_path, store, CONFIG, confirmed=True, allow_removals=True)
        raise AssertionError("identity change should be refused")
    except PublishError as error:
        assert "allow-identity-change" in str(error)


def test_stale_approval_blocks_publish(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg")
    prepare(tmp_path, _options())
    _fill(tmp_path)
    approve(tmp_path)
    metadata = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    metadata["photos"][0]["blurb"] = "Edited after approval."
    (tmp_path / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
    try:
        publish_package(tmp_path, MemoryStore(), CONFIG, confirmed=True)
        raise AssertionError("stale approval should block publish")
    except PublishError as error:
        assert "stale" in str(error).lower()


def test_unlock_cycle_is_rejected(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg")
    prepare(tmp_path, _options(unlock={"type": "packageCompleted", "packageId": "other"}))
    _fill(tmp_path)
    issues = remote_issues(
        tmp_path,
        other_photos={},
        other_unlocks={"other": {"type": "packageCompleted", "packageId": "cedar"}},
        published_photo_ids=set(),
        share_bundled=False,
    )
    assert any("cycle" in issue.message.lower() for issue in issues if issue.level == "error")


def test_list_reads_the_catalog(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg")
    prepare(tmp_path, _options())
    _fill(tmp_path)
    approve(tmp_path)
    store = MemoryStore()
    publish_package(tmp_path, store, CONFIG, confirmed=True)
    assert [item["id"] for item in list_packages(store, CONFIG)] == ["cedar"]


def test_railway_proposal_is_accepted_and_a_foreign_stop_is_not(tmp_path: Path):
    _write_photo(tmp_path / "photos", "mianyue.jpg", color=(70, 80, 90))
    prepare(tmp_path, _options(package_id="taiwan", title="Taiwan", description="Photographs for Taiwan."))
    metadata = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    metadata["moods"] = [
        {"id": "atmospheric", "title": "Atmospheric", "summary": "Mist and soft distance."}
    ]
    metadata["sceneries"] = [
        {
            "id": "railway",
            "title": "Railway",
            "summary": "Track and the grade around it.",
            "story": "",
        }
    ]
    metadata["photos"][0]["title"] = "Mist on the grade"
    metadata["photos"][0]["sceneryId"] = "railway"
    metadata["photos"][0]["moodIds"] = ["atmospheric"]
    metadata["photos"][0]["blurb"] = "A single track climbs into the cloud, with the poles fading uphill."
    metadata["photos"][0]["story"] = "The line was built to carry timber down from the mountain."
    metadata["trails"] = [
        {
            "id": "mianyue-line",
            "title": "Mianyue Line",
            "summary": "A branch of the mountain railway.",
            "story": "",
            "photoIds": ["mianyue", "mountain"],
        }
    ]
    (tmp_path / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
    foreign = [issue.message for issue in local_issues(tmp_path) if issue.level == "error"]
    assert any("not in this package" in message for message in foreign)

    metadata["trails"][0]["photoIds"] = ["mianyue"]
    (tmp_path / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
    issues = local_issues(tmp_path)
    errors = [issue.message for issue in issues if issue.level == "error"]
    notices = [issue.message for issue in issues if issue.level == "notice"]
    assert errors == []
    assert any("New scenery railway" in message for message in notices)
    assert any("New mood atmospheric" in message for message in notices)
    assert any("New trail mianyue-line" in message for message in notices)

    metadata["sceneries"].append({"id": "forest", "title": "Woods", "summary": "Different.", "story": ""})
    (tmp_path / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
    mismatched = [issue.message for issue in local_issues(tmp_path) if issue.level == "error"]
    assert any("does not match the shared definition" in message for message in mismatched)


def test_published_definitions_appear_in_the_next_review(tmp_path: Path, monkeypatch):
    root = tmp_path / "repo"
    catalog = root / "shared" / "source" / "catalog"
    catalog.mkdir(parents=True)
    (catalog / "moods.json").write_text(
        json.dumps({"moods": [{"id": "quiet", "title": "Quiet", "summary": "Still."}]}),
        encoding="utf-8",
    )
    (catalog / "sceneries.json").write_text(
        json.dumps({"sceneries": [{"id": "forest", "title": "Forest", "summary": "Trees.", "story": ""}]}),
        encoding="utf-8",
    )
    (catalog / "trails.json").write_text(json.dumps({"trails": []}), encoding="utf-8")
    taiwan = root / "assets" / "taiwan"
    taiwan.mkdir(parents=True)
    (taiwan / "published.json").write_text(
        json.dumps(
            {
                "release": "1",
                "moods": [{"id": "atmospheric", "title": "Atmospheric", "summary": "Mist and soft distance."}],
                "sceneries": [
                    {
                        "id": "railway",
                        "title": "Railway",
                        "summary": "Track and the grade around it.",
                        "story": "",
                    }
                ],
                "trails": [
                    {
                        "id": "mianyue-line",
                        "title": "Mianyue Line",
                        "summary": "A mountain railway.",
                        "story": "",
                        "photoIds": ["mianyue"],
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    monkeypatch.setattr("trailpublish.model.repo_root", lambda: root)
    monkeypatch.setattr("trailpublish.registry.repo_root", lambda: root)
    later = root / "assets" / "later"
    _write_photo(later / "photos", "ridge.jpg", color=(20, 30, 80))
    prepare(later, _options(package_id="later", title="Later", description="A later package."))
    context = json.loads((later / "review" / "context.json").read_text(encoding="utf-8"))
    assert "railway" in {item["id"] for item in context["approvedSceneries"]}
    assert "atmospheric" in {item["id"] for item in context["approvedMoods"]}
    assert "mianyue-line" in {item["id"] for item in context["sharedTrails"]}
    assert "forest" in {item["id"] for item in context["approvedSceneries"]}
    registry = assemble_registry(root)
    assert registry.conflicts == []
    disagree = root / "assets" / "other"
    disagree.mkdir()
    (disagree / "published.json").write_text(
        json.dumps(
            {
                "release": "1",
                "moods": [{"id": "atmospheric", "title": "Foggy", "summary": "A different mood."}],
                "sceneries": [],
                "trails": [],
            }
        ),
        encoding="utf-8",
    )
    conflicted = assemble_registry(root)
    assert "atmospheric" in conflicted.conflict_moods
    assert any("atmospheric" in message for message in conflicted.conflicts)


def test_import_keeps_approved_photos_and_context_marks_the_new_one(tmp_path: Path):
    _write_photo(tmp_path / "photos", "one.jpg", color=(1, 2, 3))
    prepare(tmp_path, _options())
    _fill(tmp_path)
    prepare(tmp_path, _options())
    _write_photo(tmp_path / "photos", "two.jpg", color=(4, 5, 6))
    prepare(tmp_path, _options())
    context = json.loads((tmp_path / "review" / "context.json").read_text(encoding="utf-8"))
    by_status = {photo["id"]: photo["reviewStatus"] for photo in context["photos"]}
    assert by_status["one"] == "approved"
    assert by_status["two"] == "review"
    assert context["reviewPhotoIds"] == ["two"]

    reviewed = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    for photo in reviewed["photos"]:
        if photo["id"] == "one":
            photo["blurb"] = "This rewrite should not land."
        if photo["id"] == "two":
            photo["title"] = "Second frame"
            photo["sceneryId"] = "forest"
            photo["moodIds"] = ["quiet"]
            photo["blurb"] = "A second trunk."
            photo["story"] = ""
    reviewed["uncertainties"] = [{"photoId": "two", "note": "The creek name is not established."}]
    reviewed_path = tmp_path / "returned.json"
    reviewed_path.write_text(json.dumps(reviewed), encoding="utf-8")
    notes = import_review(tmp_path, reviewed_path)
    metadata = json.loads((tmp_path / "metadata.json").read_text(encoding="utf-8"))
    by_id = {photo["id"]: photo for photo in metadata["photos"]}
    assert by_id["one"]["blurb"] == "Moss and fir trunks fill the frame."
    assert by_id["two"]["title"] == "Second frame"
    assert "uncertainties" not in metadata
    assert any("Kept approved text for one" in note for note in notes)
    assert (tmp_path / "review" / "review-notes.json").is_file()


class _FailOnce(MemoryStore):
    def __init__(self):
        super().__init__()
        self.fail_metadata = True

    def put(self, key, body, **kwargs):
        if key.endswith("metadata.json") and self.fail_metadata:
            self.fail_metadata = False
            raise PublishError("connection reset")
        return super().put(key, body, **kwargs)


class _RaceCatalog(MemoryStore):
    def put(self, key, body, **kwargs):
        if key.endswith("catalog.json"):
            if self.get(key) is None:
                super().put(
                    key,
                    b'{"schemaVersion": 1, "revision": "other", "packages": []}',
                    content_type="application/json",
                    cache_control="public, max-age=60, must-revalidate",
                    metadata={},
                    if_none_match="*",
                )
            raise PreconditionFailed(key)
        return super().put(key, body, **kwargs)
