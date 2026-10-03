# Source photos

## Trail puzzle (grid chop)

Drop a **portrait** photo here, then run [tools/chop_puzzle](../../tools/chop_puzzle/README.md).

Example: `shared/source/deathvalley.jpg`

## Layers mode (segmented)

Full-frame segment exports, same size as the photo. Cutout areas are near-white
(or true alpha PNG). Naming:

```
deathvalley.jpg      # optional full reference
deathvalley-1.jpg
deathvalley-2.jpg
...
deathvalley-5.jpg
```

Then run [tools/prep_layers](../../tools/prep_layers/README.md).

## Reveal a photo (v3)

High-res files stay here. The prep script writes a phone-sized copy into the app.

```
shared/source/reveal/mountain/
  photo.jpg            # full color, portrait or landscape
  photo-bw.jpg         # dark plate, same pixel size
  pieces/
    01-meadow.png      # full frame, same pixel size, transparent outside the piece
    02-river.png
```

Then run [tools/prep_reveal](../../tools/prep_reveal/README.md).

Supported formats: `.jpg`, `.jpeg`, `.png`, `.webp`
