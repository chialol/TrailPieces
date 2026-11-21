---
name: stage-reveal
description: >-
  Seed reveal-photo metadata for one folder under shared/source/reveal.
  Use when the user points at that folder or asks to pregenerate or stage
  meta.json. Write meta.json and stop. If they named a trail, also add the
  photo id to that trail's photoIds. Do not cut pieces, invent a dark plate,
  invent a trail, run prep, or import the catalog. When the user says the
  cuts are done, commit the saved meta.json with stage_reveal.py commit.
---

# Stage reveal metadata

The agent only seeds `meta.json`. The person cuts the photo and runs the finish sequence themselves. Seeding does not create pieces, does not require `photo-bw`, and does not install the app.

The destination is **Walk a trail**. A photo appears there when its id is in some trail's `photoIds` in `shared/source/catalog/trails.json`, `import_catalog` has written `android/app/src/main/assets/catalog/catalog.json`, and the scene assets exist.

Use `tools/chop_puzzle/.venv/Scripts/python` when `python` cannot import PIL. Paths below are from the repo root. PowerShell: separate commands, no `&&`.

## Stage

1. The folder is `shared/source/reveal/<photo-id>/`. The photo id is the folder name. It is not the scenery id.
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

Omit `story` when you do not know a concrete fact. Omit `revealId` unless the asset folder differs from the photo id.

7. Trail membership is recorded only while seeding, and only when the user named a trail in that request. Open `shared/source/catalog/trails.json`, find the trail with that id, and append the photo id to its `photoIds` if it is not already there. Do not add a trail. Do not change other trails. If they did not name a trail, or the name is not an id already in that file, do not edit `trails.json`.
8. Run `check` with the same interpreter and folder. It must print `ok <photo-id>`. Fix the file until it does.
9. Tell the user the fields, then end with [After seeding](#after-seeding). Do not ask what to run next. Do not ask a pile of other questions.

## After seeding

This is the whole reply ending. Fill in the photo id. Do not offer to cut, prep, import, or install.

Seeded `<photo-id>`. Open the tracer with `.\tools\trace_pieces\serve.ps1`, cut in Cut mode (pen is P), and save with Open folder into `shared/source/reveal/<photo-id>`; if Save downloads a zip, unzip it into that folder so `pieces/` and `meta.json` sit next to `photo.jpg`, not in a sibling `<photo-id>-pieces` folder. Add `photo-bw.jpg` beside the photo at the same pixel size, then from the repo run `.\tools\finish_reveal.ps1 <photo-id>`. Install from Android Studio and open Walk a trail.

If they did not name a trail, add one more sentence after that. Name the trail ids currently in `shared/source/catalog/trails.json` and say the photo will not show in Walk a trail until one is named. Do not invent an id. Do not ask which one.

To cut, in its own window (`serve.ps1` stays open):

```powershell
.\tools\trace_pieces\serve.ps1
```

After `photo-bw.jpg` and the pieces are in the scene folder, paste this:

```powershell
.\tools\finish_reveal.ps1 <photo-id>
```

`finish_reveal.ps1` runs prep. When the photo id is already on a trail, it also runs `import_catalog`. Install from Android Studio and open **Walk a trail**. When the id is not on a trail, the assets are still prepped and the photo will not appear there until the id is added to a trail `photoIds` list and `finish_reveal` (or `import_catalog`) is run again.

## Commit

Run this only after the user says the cuts are done and the metadata should go into the catalog.

Do not rewrite `meta.json`. The copy saved from the tool is the one to commit. Do not run `finish_reveal.ps1` or prep. Do not create a git commit. Do not edit `trails.json`. Trail membership was recorded at seed time, or it was not.

```
tools/chop_puzzle/.venv/Scripts/python tools/stage_reveal/stage_reveal.py commit shared/source/reveal/<photo-id>
```

That command runs `import_catalog`. If it says pieces are missing, or `scene.json` is missing, stop and report that message. The missing-assets message already names `.\tools\finish_reveal.ps1 <photo-id>`. Do not invent a dark plate. Do not invent a trail. If the photo id is not in any trail's `photoIds`, say it will not show in Walk a trail until a trail is named and `finish_reveal` (or `import_catalog`) is run again.

## Operating rules

- Do not auto-generate pieces.
- Do not invent `photo-bw`. It is optional until `finish_reveal.ps1`.
- Do not invent a trail. Add the photo id to a trail's `photoIds` only when they named that trail while asking to seed.
- Open photo loads `meta.json` only when the tracer is served from the repo (`.\tools\trace_pieces\serve.ps1`). A `file://` page cannot see the folder beside the photo.
- The scenery id and the scenery menu must show the same scenery.
- The photo id (folder name) is not the scenery id.
- Unlabeled `01.png` is a valid piece. A word after the number (`01-meadow.png`) is only the on-screen name.
- Reloading an installed APK does not see new assets. They show in Walk a trail after you install from Android Studio, when the photo id is already in a trail's `photoIds` and the catalog has been imported.
- Do not ask what to run next. After seeding, end with [After seeding](#after-seeding). After they say the cuts are done, the commit command above is the path.

## Copy

Match the existing meta voice. A blurb is one concrete line: "A shaded boulder under the firs." A story is one or two factual sentences, or it is left out. Do not invent a measurement, a date, or a history.

## Scenery

Add the scenery to `shared/source/catalog/sceneries.json` and to `embedded.sceneries` in `tools/trace_pieces/index.html`. Use the same object in both places. The id is lowercase letters and digits. Summary and story stay as short as the other sceneries. The menu has to show that same id.

`import_catalog` skips a photo that has `meta.json` but no prepped `scene.json`, so a staged folder does not enter the app catalog early. A trail that already lists that photo is an error until `scene.json` exists. `finish_reveal.ps1` preps first, then imports only when the photo id is already on a trail. Walk a trail reads that catalog. The photo shows there after you install from Android Studio.
