# Publishing content packages

New puzzles can be published without shipping a new app build. The phone keeps the bundled starter catalog and adds packages from CloudFront. A package is how a set of photos is discovered, downloaded, and unlocked. A trail is still a geographic walk. A photo id never changes because a package was revised.

## How this fits the app

Walk a trail reads one catalog: moods, sceneries, photos, and trails. That document already lives in `android/app/src/main/assets/catalog/catalog.json`, and completion is a set of photo ids in the app's private progress file. Remote packages use that same photo and trail shape.

On startup the app reads the bundled catalog and any packages already saved on the device. It does not wait for the network. A refresh of `catalog.json` runs in the background. If CloudFront is down, or the new catalog is malformed or an unsupported schema, the last good catalog stays. A package that disappears from the catalog is kept on the device, and its completions stay in progress.

The same photo id is one puzzle everywhere. The bundled copy keeps its wording. A remote package may add new stops onto a bundled trail. Progress is shared because it is stored by photo id, not by package release.

A downloaded color image is the puzzle photograph. If the package also contains a cut reveal scene (`scenes/<photo-id>/` from `prep_reveal`), the app downloads those files and the existing piece placement plays them. Piece rules are unchanged. A photograph with no cut scene is listed, and its cover comes from the downloaded image; the pieces are not invented.

Client-side unlocks are gameplay, not security. `access: public` objects on CloudFront can be fetched by anyone who knows the URL. An unlock rule only decides whether this install shows and downloads them.

## Layout on CloudFront

Paths are relative to `content/v1/`. The app is configured with one URL, the catalog:

`https://<cdn-domain>/content/v1/catalog.json`

```
content/v1/
  catalog.json
  packages/<package-id>/releases/<release>/
    metadata.json
    previews/cover.webp
  assets/<photo-id>.<sha256-prefix>.webp
  assets/scenes/<photo-id>/<tree-prefix>/...
```

Image names include a content hash, so an unchanged photograph is not uploaded again. A release, once written, is not overwritten. The catalog is the only object a normal publish replaces. Older releases are left in place. Rollback points the catalog at an older release.

Cache-Control:

| Object | Header |
| --- | --- |
| `catalog.json` | `public, max-age=60, must-revalidate` |
| release metadata, previews, hashed images | `public, max-age=31536000, immutable` |

The distribution should use a cache policy with minimum TTL 0 so those headers are honored. The template uses the managed policy `UseOriginCacheControlHeaders`.

## One-time setup

From `tools/publish`:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
copy publish.config.example.json publish.config.json
```

Fill in your AWS profile, region, bucket, CloudFront distribution id, and the distribution's `https://` base URL. Credentials come from that AWS profile. They are not stored in the app or in this repo. `publish.config.json` is gitignored.

The app learns the catalog URL from `android/app/src/main/assets/remote/config.json`:

```json
{ "catalogUrl": "https://d111111abcdef8.cloudfront.net/content/v1/catalog.json" }
```

Leave `catalogUrl` empty and the app never calls the network. The bundled walks still play offline.

## AWS

Do not let the CLI create infrastructure. It only reads and writes objects in the bucket named in your config.

`infra/content-distribution.yaml` is a CloudFormation template for a new private bucket (Block Public Access, bucket-owner-enforced, versioning, encryption), a CloudFront Origin Access Control, and a distribution that redirects HTTP to HTTPS. There are no public object ACLs. Deploy it yourself after you read it:

```powershell
aws cloudformation deploy --template-file tools/publish/infra/content-distribution.yaml --stack-name trailpieces-content --parameter-overrides BucketName=your-content-bucket
```

If you already have a bucket and distribution, skip the template. Point `publish.config.json` at them. The bucket policy must allow that distribution's service principal `s3:GetObject`, and the bucket must stay private.

Confirm the managed cache policy id before relying on the template:

```powershell
aws cloudfront list-cache-policies --type managed
```

Turn on S3 versioning if you did not use the template. It is the recovery path for a bad catalog write. The publisher does not delete old releases or old hashed images.

## From a reveal folder

When the photo already lives in `shared/source/reveal/<id>/` with `photo.jpg`, `photo-bw.jpg`, and `pieces/`, add it to a package from the repo root. `.\make` is a small Windows command in the repo, so GNU make is not required.

```powershell
.\make prep move shared\source\reveal\mountain cedar
.\make prep move shared\source\reveal\mossyrock cedar
```

The package is created at `assets/cedar` the first time. The second command adds the other photo. A package name on its own means `assets/<name>`. The source folder is copied, not removed. Each run renders the phone-sized scene with the same prep step `finish_reveal` uses, into `assets/cedar/scenes/<id>/`, and writes the package JSON. Wording already reviewed in `metadata.json` is left alone. Empty title and blurb are filled from that photo's `meta.json`.

Then:

```powershell
.\make publish validate cedar
.\make publish approve cedar
.\make publish publish cedar --dry-run
.\make publish publish cedar
```

A third photo is the same `move` command, then validate, approve, and publish again. Editing the wording is a change to `assets/cedar/metadata.json`. Approve that file before the next publish. Adding or changing a photo clears the previous approval.

`prep_asset.py` needs the prep virtualenv (`tools/chop_puzzle/.venv`), because that is what renders the pieces.

## Workflow

Put loose photographs in a folder. Originals are only read. Use this when the pictures are not already a reveal folder.

```
cedar/
  photos/
    one.jpg
    two.jpg
  notes.json          optional, and never uploaded
```

`notes.json` may hold a location or a trail name. Set `"status": "confirmed"` when the photographer is sure of the place, or `"tentative"` for a recollection. A missing status is tentative. Confirmed notes are geographic context. Tentative notes and camera coordinates are hints to investigate. None of these notes are published.

```powershell
.\.venv\Scripts\python publish.py prepare C:\path\to\cedar --package-id cedar --title "Cedar Grove" --description "Forest photographs." --unlock immediate --non-interactive
```

In a terminal, leave the flags off and the script asks for the package id, title, description, and unlock.

Prepare writes:

```
cedar/
  package.json
  metadata.json
  notes.json
  review/
    instructions.md
    context.json
    contact-sheet.jpg
    images/<photo-id>.jpg
  build/images/<photo-id>.webp
```

`review/instructions.md` and `context.json` are what you upload to ChatGPT with the review images. They stand alone: the voice, the shared scenery and mood definitions, the trails already published, which photos are in this review, and the exact `metadata.json` shape. ChatGPT may add a scenery, mood, or trail when the approved ones do not fit. It copies approved photographs unchanged. Research goes in `review-notes.json`, not in the blurb or story.

The shared definitions are the bundled catalog plus each package's `published.json`, written when that package is published. A draft edit does not become part of the next review. Two published definitions of the same id are reported as a conflict instead of one being dropped.

Put the returned JSON at `cedar/metadata.json`, or merge it by photo id:

```powershell
python publish.py import-review cedar returned.json
python publish.py validate cedar
python publish.py approve cedar
```

Approve records a hash of `metadata.json` and of each source image. Editing the wording or replacing a file clears that approval. Prepare does not rewrite a blurb that is already there. A later prepare sends only new or changed photographs through the review images, and the context still lists the whole package.

Publishing an unchanged package does not create a release.

```powershell
python publish.py publish cedar --dry-run
python publish.py publish cedar
python publish.py list
python publish.py rollback cedar 1
```

`--dry-run` only reads. A real publish asks you to confirm unless you pass `--yes`.

Publish then:

1. Reads the catalog from S3.
2. Validates the workspace and the approval hashes.
3. Plans the new release and shows added, modified, and removed photos and trails.
4. Uploads missing hashed images and the versioned metadata. Existing immutable objects are not replaced.
5. Checks that every referenced object is on the origin.
6. Writes the catalog last, with `If-Match` on the etag it read. If that etag changed, it stops and leaves the other writer's catalog in place.
7. Reads the catalog back through CloudFront when `cdn_base_url` is set. A stale edge copy is reported and is not a failed publish; the catalog cache is about 60 seconds. `--invalidate` invalidates only `/content/v1/catalog.json`.

Removing a photo or trail requires `--allow-removals`. Replacing the image bytes of an existing photo id requires `--allow-identity-change`. Reusing an id that is already in the installed app requires `--share-bundled-id`. The phone keeps the bundled wording for that id and shares the completion.

If an upload fails before the catalog write, the previous catalog still works. Run publish again. Hashed objects already on the origin are skipped.

Unlocks:

| JSON | Meaning |
| --- | --- |
| `{"type":"immediate"}` | Playable as soon as it is public |
| `{"type":"puzzlesCompleted","count":3}` | The player has at least 3 distinct completed photo ids. Bundled and remote photos count. A photo that has left the catalog still counts. Replaying does not add another. |
| `{"type":"packageCompleted","packageId":"cedar"}` | Every photo id in that package's saved metadata is completed. The same id completed from another package counts. A package whose metadata was never downloaded is not complete. Cycles are rejected. |

`access` is separate. This version publishes only `public`. Any other access value is kept in the catalog and is not granted, so a later entitlement policy can be added without treating it as public. An unknown unlock is not granted either. An unsupported catalog `schemaVersion` is refused and the previous catalog remains.

`puzzlesCompleted` and `packageCompleted` do not hide the objects from the CDN. They only gate this install.

## What is not uploaded

Originals, `notes.json`, `review/` (including the contact sheet, EXIF hints, and uncertainty notes), and `package.json` stay on your machine. Published WebPs are resized to the app's limits (height 1920, width 4096, quality 85), oriented upright, and saved without GPS. A WebP that is already inside those limits and has no EXIF is copied instead of recompressed.

## Tests

```powershell
cd tools\publish
.\.venv\Scripts\python -m pytest
```

From `android`:

```powershell
.\gradlew.bat :journey:test :app:testDebugUnitTest
```

The publisher tests use an in-memory store. They do not call AWS.
