# Editorial review for __PACKAGE_TITLE__

Package id: `__PACKAGE_ID__`

You are starting with no other knowledge of this project. Everything you need is in this file, in `context.json`, and in the photographs under `images/`.

Trail Pieces is a photography puzzle. The player rebuilds one photograph piece by piece. When the picture is whole, the game reveals a title, a blurb, and sometimes a short story. That text is the reward for the time they just spent looking. Write it as a photography gallery label crossed with a nature field guide and a travel journal.

Prefer a specific, memorable fact about the landscape, history, geology, ecology, culture, or a structure that is actually in the frame. The goal is a small moment of discovery, not a tourism brochure.

## Voice

- `title` is a few words. Use the place name or name the specific view. It should sound like a title in a photography collection.
- `blurb` is one concrete sentence about what is visible: rock, water, trees, mist, track, structures, light. Do not describe something that is not in the frame.
- A photograph's `story` is one or two factual sentences, or `""`. Prefer a fact about something that is actually in the frame: what that structure, rock, or stand of trees is, or how it came to look this way. Do not restate the blurb, and do not spend the photo story on a different part of the trail.
- A trail's `story` is broader geographic, historical, ecological, or cultural context for the route. Do not repeat the photo story there.
- A scenery's `story` is about that kind of landscape in general, not about this photograph. See below.
- Use `""` when you cannot support a fact. An empty story is better than a generic one.
- When reliable sources disagree on a measurement, a date, or another detail, use the figure they support in common. When they do not agree, leave the disputed precision out.
- A fact about the whole trail is not a fact about the structure, landmark, or viewpoint in the frame. Name the thing in the frame only when the source is about that thing.
- Do not write "breathtaking views", "hidden gem", "popular destination", or other brochure language.
- Do not put sources, citations, camera data, or open questions in the title, blurb, or story.

These two samples show the difference in voice. They are not facts about any photograph in this package.

Weak: title "Beautiful Mountain View". Blurb "A popular destination for nature lovers with breathtaking views." Story "Visitors come from around the world to enjoy this hidden gem."

Stronger: title "Switchback above the river". Blurb "A single track climbs a cut in the basalt, with the river in the shade below." Story "The grade was cut for the logging railway in the 1910s and later opened as a footpath."

## How to decide what is known

`context.json` separates evidence. Use it in this order:

- `photographerConfirmed` is geographic context you may rely on. You do not need a second source to confirm the exact viewpoint. If the photograph is confirmed to be at Smith Rock and the peak in the frame cannot be named, write about the place's geology or climate and do not name that peak.
- `photographerTentative` is a recollection. Investigate it. Do not treat it as a fact until a reliable source, or a confirmed note, supports it.
- `machineGenerated` comes from the camera file. It is a hint. Investigate it. Do not treat the coordinates as the name of a landmark.
- `editorial` on a photo whose `reviewStatus` is `review` is an unreviewed draft. It can be wrong.
- `editorial` on a photo whose `reviewStatus` is `approved` was accepted in an earlier review. Copy it unchanged.
- Facts you establish from the photograph and from reliable sources are yours to write. Prefer park and land-management publications, geological surveys, historical societies, maps, and official local-language sources over travel blogs.
- Keep a trail-wide fact in the trail story. Put a fact about the structure, landmark, or viewpoint in the photograph only when it is about that thing.
- When a landmark is uncertain, ask a specific question in `review-notes.json` and still return the metadata. Do not leave the package unfinished because one photograph has an open question. Write the broader place when that context is confirmed, and leave the uncertain name out.

## What you may rewrite

Rewrite only the photographs listed here: __REVIEW_IDS__

Copy these approved photographs forward unchanged, including title, sceneryId, moodIds, blurb, and story: __LOCKED_IDS__

Also preserve, for every photograph:

- `id`
- `revealId`, which must equal `id`
- `sourceFile`, copied from `context.json`

Do not renumber ids, change the package id, or change `schemaVersion` or `version`. Do not edit asset hashes, remote paths, or release numbers. Those are not part of this file.

## Scenery and mood

Sceneries name what is visually present, or the subject that defines the photograph. Moods name the atmosphere. Both are global. A summary or story has to stay true for a later photograph from an unrelated place. Do not mention this photograph, this trail, or this region in a scenery or mood definition.

Read every mood in `context.json` before you assign any. A photograph may use more than one mood when each one names a meaningfully different part of the atmosphere. Do not add a second mood that only repeats the first.

Copy an approved scenery or mood into your file exactly, including every field. Do not reword it, and do not change its id. Previously established ids stay as they are, even when a newer id would use a different pattern.

Before you invent a scenery or a mood, compare the proposed meaning with every existing summary, not only the ids. If an existing category already covers that meaning, use it. Add a category only when the overlap is not enough. A railway in the mist is a railway, not a forest and not a canyon, when the track is the subject. Fog may need a new mood when none of the approved moods describe that air.

A new id is lowercase words separated by hyphens, such as `railway` or `mianyue-line`. Use a letter first, then letters, digits, and hyphens only. Underscores are rejected. Use the same pattern for every new scenery, mood, and trail. Do not rename an id that already exists.

A new category must also be:

- Distinct in meaning from the approved ones, not a synonym or a narrower duplicate.
- Broad enough that a later photograph could use it.
- Short enough to read as a label.
- Defined in full: mood `id`, `title`, `summary`; scenery `id`, `title`, `summary`, `story`.

A mood `summary` says what that atmosphere is, in words that fit other places. A scenery `summary` says what that landscape type looks like. A scenery `story` adds a general fact or a characteristic of that type of landscape. It should not be a longer restatement of the summary. `story` may be `""` when you have no such fact.

Weak scenery story: "Trees, moss, and shade along a path." That only repeats the summary.
Stronger scenery story: "Little direct sun reaches the floor, so moss holds on the trunks." That is a characteristic of this kind of landscape, and it does not name a place.

Include in `moods` and `sceneries` only the definitions this package's photographs use. Do not paste the whole shared list. A new definition is a proposal. It is not approved until the package is approved by hand and published. After that, a later package will see it in `context.json`.

If `context.json` lists a conflict for an id, do not invent a third wording. Keep the approved definition shown beside the conflict.

## Trails

Create a trail when the photographs belong to a real route or a real continuous place. Reuse an existing trail id, including one from another package, when these photographs are on that same route. Do not invent a trail so that every photograph has one. A photograph with no established trail is left off every trail.

Each package lists only its own photo ids on a trail, in confirmed walking order. The game merges packages that share a trail id. Do not copy another package's photo ids into this file. If the walking order is not known, keep the order the photographs already have and say in `review-notes.json` that the order is unconfirmed. Do not rearrange them into a guessed sequence.

You may correct a trail's title, summary, or story when the review shows the old wording is wrong. Keep the same id. Put the reason in `review-notes.json`. The trail story carries the route's broader context. Leave the frame-specific fact on the photograph.

## Return these files

Return one complete `metadata.json` for package `__PACKAGE_ID__`. It must be valid JSON, ready to save over this package's `metadata.json` with no restructuring. Include every photograph in `context.json` exactly once.

Empty optional text is `""`. Do not use null, and do not omit `story`.

The shape below is generated from the fields the app reads. The words in it are placeholders, not captions for these photographs.

```json
__SCHEMA_EXAMPLE__
```

`context.json` field `returnDocument` is the same shape filled with this package's real photo ids, source files, and approved text. Fill the photographs under review. Leave the approved photographs as they are. Add each scenery, mood, and trail you reference, with every field present.

Also return `review-notes.json`. The photographer uses it to check the writing before approval. Every factual claim in a title, blurb, story, or new category needs the source URL that supports it. This file is not published.

```json
{
  "research": [
    {"photoId": "photo-id", "claim": "The factual sentence this URL supports.", "url": "https://example.org/source"}
  ],
  "questions": [
    {"photoId": "photo-id", "question": "What a confirmed answer would change."}
  ],
  "taxonomyNotes": [
    "Why a new scenery or mood is distinct in meaning from the approved list, and why its wording stays usable for an unrelated place."
  ]
}
```

Research and questions stay in that file. If you also include an `uncertainties` array on `metadata.json`, it is kept locally and is not published.

The contact sheet is an overview. Study the files in `images/`. Their filenames are the photo ids.
