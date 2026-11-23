# Source photos

## Walk a trail photo

High-res files stay here. The phone-sized copy is written into the app by
[tools/prep_reveal](../../tools/prep_reveal/README.md).

```
shared/source/reveal/mountain/
  photo.jpg            # full color, portrait or landscape. This is the first file.
  photo-bw.jpg         # dark plate, same pixel size. Add it before finish_reveal.
  meta.json            # seeded before cutting
  pieces/
    01.png             # full frame, same pixel size. A name after the number is optional.
    02-river.png
```

Seed `meta.json`, then cut with `.\tools\trace_pieces\serve.ps1` and save into
this folder. A tracer zip is unzipped here too, so `pieces/` and `meta.json`
sit next to `photo.jpg`, not in a sibling `mountain-pieces` folder. Add
`photo-bw.jpg` at the same pixel size, then:

```powershell
.\tools\finish_reveal.ps1 mountain
```

When the photo id is already in a trail's `photoIds`, that command also runs
`import_catalog`. Install from Android Studio and open **Walk a trail**.
If it is not on a trail, the assets are still prepped and the photo will not
appear there until the id is added and the command (or `import_catalog`) is run again.

Supported formats: `.jpg`, `.jpeg`, `.png`, `.webp`
