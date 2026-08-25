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

Supported formats: `.jpg`, `.jpeg`, `.png`, `.webp`
