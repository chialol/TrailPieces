# Import catalog

Merges the trail, mood, and scenery registries with each photo's `meta.json`
into the catalog **Walk a trail** loads. It does not resize or encode images.
Run [`prep_reveal`](../prep_reveal/README.md) first when a photo's pixels changed
(`.\tools\finish_reveal.ps1 <id>` is the command that does that).

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

A `meta.json` with no prepped `scene.json` is skipped. That is how a photo can be staged in the trace tool before its pieces are ready. A trail that lists that photo is still an error.

**Walk a trail** reads the catalog this script writes. A photo appears on a walk when its id is in some trail's `photoIds` in `trails.json` and this script has written `catalog.json` (scene assets have to exist too). `.\tools\finish_reveal.ps1 <id>` runs this script after prep when the photo id is already on a trail. Reloading an installed APK does not see the update. Install from Android Studio and open Walk a trail.

Trail order is the `photoIds` array in `trails.json`. A photo can be listed on
more than one trail. Leave a new scenery in `sceneries.json` even before a
photo uses it; the picker hides sceneries that have no photo yet.

## Usage

```powershell
cd C:\src\2026\natureGame
python .\tools\import_catalog\import_catalog.py
```

That writes `android/app/src/main/assets/catalog/catalog.json`. Commit that
file with the authoring JSON. Install from Android Studio and open Walk a trail.

The written document is also the shape a future `GET /v1/catalog` should return.
