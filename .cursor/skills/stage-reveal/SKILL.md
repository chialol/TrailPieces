---
name: stage-reveal
description: >-
  Stage reveal-photo metadata from a source folder for the trace tool, and
  later commit that metadata into the app catalog. Use when the user points
  at a folder under shared/source/reveal, asks to pregenerate or stage
  meta.json, or says the cuts are done and the metadata should be committed.
---

# Stage reveal metadata

Stage writes one folder's `meta.json` so the trace tool can show it. Commit reads that file into the app catalog after the cuts exist. Do not commit during stage. Do not draw pieces.

Use `tools/chop_puzzle/.venv/Scripts/python` when `python` cannot import PIL. Paths below are from the repo root.

## Stage

1. The folder is `shared/source/reveal/<photo-id>/`. The photo id is the folder name.
2. Run:

```
tools/chop_puzzle/.venv/Scripts/python tools/stage_reveal/stage_reveal.py inspect shared/source/reveal/<photo-id>
```

3. Read the `preview` image from the JSON. Do not open the original photo.
4. If `meta` is not null, show the existing fields and stop. Replace `meta.json` only when the user asked to replace it.
5. Pick `sceneryId` and one mood id from the lists in the JSON. Add a scenery only when none of them fit. See [Scenery](#scenery).
6. Write `meta.json` in the scene folder:

```json
{
  "title": "Mossy Rock",
  "sceneryId": "forest",
  "moodIds": ["quiet"],
  "blurb": "A shaded boulder under the firs."
}
```

Omit `story` when you do not know a concrete fact. Omit `revealId` unless the asset folder differs from the photo id. Do not add the photo to `trails.json`.

7. Run `check` with the same interpreter and folder. It must print `ok <photo-id>`. Fix the file until it does.
8. Tell the user the fields. They review them in the trace tool. Open photo fills the form when the page is served from the repo and the file matches a scene folder. Open folder is what saves back into that folder. `photo-bw` is not required yet.

## Commit

Run this only after the user says the cuts are done and the metadata should go into the catalog.

Do not rewrite `meta.json`. The copy saved from the tool is the one to commit.

```
tools/chop_puzzle/.venv/Scripts/python tools/stage_reveal/stage_reveal.py commit shared/source/reveal/<photo-id>
```

If commit says pieces are missing, or `scene.json` is missing, stop and report that message. Do not invent a dark plate. Do not add a trail unless the user named one. If they did, append the photo id to that trail's `photoIds`, then run commit again.

## Copy

Match the existing meta voice. A blurb is one concrete line: "A shaded boulder under the firs." A story is one or two factual sentences, or it is left out. Do not invent a measurement, a date, or a history.

## Scenery

Add the scenery to `shared/source/catalog/sceneries.json` and to `embedded.sceneries` in `tools/trace_pieces/index.html`. Use the same object in both places. The id is lowercase letters and digits. Summary and story stay as short as the other sceneries.

`import_catalog` skips a photo that has `meta.json` but no prepped `scene.json`, so a staged folder does not enter the app catalog early. A trail that already lists that photo is an error.
