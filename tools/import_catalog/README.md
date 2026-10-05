# Import catalog

Merges the trail, mood, and scenery registries with each photo's `meta.json`
into the one document the app loads. It does not resize or encode images.
Run [`prep_reveal`](../prep_reveal/README.md) first when a photo's pixels changed.

## Where to edit

```
shared/source/catalog/moods.json
shared/source/catalog/sceneries.json
shared/source/catalog/trails.json

shared/source/reveal/mossyrock/meta.json
```

`meta.json` sits beside the source photo. The folder name is the photo id.

```json
{
  "title": "Mossy Rock",
  "sceneryId": "forest",
  "moodIds": ["quiet"],
  "blurb": "A shaded boulder under the firs."
}
```

`story` is optional on a photo, a scenery, and a trail. It is the longer note shown after that photo is finished. A blank story is left out.

Trail order is the `photoIds` array in `trails.json`. A photo can be listed on
more than one trail. Leave a new scenery in `sceneries.json` even before a
photo uses it; the picker hides sceneries that have no photo yet.

## Usage

```powershell
cd C:\src\2026\natureGame
python .\tools\import_catalog\import_catalog.py
```

That writes `android/app/src/main/assets/catalog/catalog.json`. Commit that
file with the authoring JSON. Rebuild the app afterward.

The written document is also the shape a future `GET /v1/catalog` should return.
