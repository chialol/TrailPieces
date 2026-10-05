# Journey architecture (plan)

Last updated: 2026-10-04. Status: **implemented**. Authoring steps are in [`../tools/import_catalog/README.md`](../tools/import_catalog/README.md).

This is the app flow around a finished photo. It does not change puzzle-engine rules or the reveal drag mechanic. [`puzzle-architecture.md`](puzzle-architecture.md) and [`reveal-handoff.md`](reveal-handoff.md) stay the source of truth for those modes.

## Player journey

1. From home, **Walk a trail** (the existing Trail puzzle, Develop a print, and Reveal a photo entries stay).
2. The player picks a lens: **Mood**, **Scenery**, or **Trail**.
3. Mood or scenery shows the photos tagged with that choice. A trail shows its stops in walk order, each with collected state.
4. Choosing a photo opens that photo’s reveal scene directly. The reveal scene picker is not shown on this path.
5. When the last piece locks, the photo is collected immediately, including if the player leaves during the finale. The finale (flash, color, sheen) plays with nothing covering the photo. After that, a landscape photo eases back until the whole frame is visible, and pinch zoom turns on. Only then does the reading panel appear, in the space beside the photo: the trail fraction (`1 of 1` on Columbia Gorge), the place and trail stories, and **Continue**. Replay stays in the top bar.
6. Opening a photo that is already collected skips the puzzle and the finale. It shows the full-color photo, already fit to the screen, with pinch zoom, the same panel, and replay at the top. Replay starts the puzzle. Leaving before the last piece locks does not collect the photo. Back returns along the screens that opened the photo.

A photo has one scenery (meadow, forest, …) and any number of moods. Trails are ordered lists of photos. Completing a photo fills that stop on every trail that lists it. The two pilot photos each sit on one trail, so the fraction reads as “this trail.”

## Layers

```
shared/source/          authoring JSON the human edits (committed)
        │
        ▼
tools/import_catalog    validates and merges
        │
        ▼
assets/catalog/catalog.json     the document the app loads
        │
        ▼
:journey                pure JVM: models, parse, queries, progress rules, repository interfaces
        │
        ▼
:app                    asset + local-file implementations, Compose flow, reveal hook
```

`:puzzle-engine` is not involved. Reveal image prep (`tools/prep_reveal`) is not involved. Metadata changes do not re-encode photos.

A future login swaps implementations behind the same interfaces. Screens never branch on local versus network.

| Interface | Now | Later |
|-----------|-----|--------|
| `CatalogRepository.suspend load(): Catalog` | Read `assets/catalog/catalog.json` | `GET /v1/catalog` returns the same document |
| `ProgressRepository` | JSON file in app private storage | Same document uploaded or fetched for `playerId` |
| `PlayerRepository.suspend current(): Player` | Create a local uuid once | Account player after login |

`catalog.json` is the API contract. Unknown JSON keys are ignored. `version` other than `1` fails the parse. Duplicate ids and dangling references fail the import and the parse.

## Authoring files

Images under `shared/source/` stay git-ignored. These JSON files are committed.

Per photo, beside the source image:

```
shared/source/reveal/mossyrock/meta.json
```

```json
{
  "title": "Mossy Rock",
  "sceneryId": "forest",
  "moodIds": ["quiet"],
  "blurb": "A shaded boulder under the firs."
}
```

The folder name is the photo id. Optional `revealId` overrides the asset folder name when it differs (default: the photo id).

Registries, edited when adding a mood, scenery, or trail — not when only retagging one photo’s blurb:

```
shared/source/catalog/moods.json
shared/source/catalog/sceneries.json
shared/source/catalog/trails.json
```

```json
{
  "trails": [
    {
      "id": "obsidian",
      "title": "Obsidian",
      "summary": "A short high-country walk.",
      "photoIds": ["mossyrock", "mountain"]
    }
  ]
}
```

Walk order is array order. A photo may be listed on more than one trail. Trail membership is not copied into `meta.json`, so reordering a trail touches one file.

`tools/import_catalog/import_catalog.py` reads those files and writes `android/app/src/main/assets/catalog/catalog.json`. It checks:

- photo ids are unique and match a `meta.json` folder
- every `sceneryId` and `moodId` exists
- every trail `photoIds` entry has a meta file
- each photo’s reveal folder already contains `scene.json` under `assets/reveal/` (run `prep_reveal` first)
- moods, sceneries, and trails have unique ids

Empty sceneries (a meadow defined before any photo is tagged) are kept in the document and hidden in the picker.

Seed content uses the two pilots only. Obsidian lists `mossyrock` then `mountain`. A meadow scenery is registered with no photo until one is added. Tags on the pilots are placeholders to edit in `meta.json`.

Adding a photo later:

1. Drop the image and pieces, run `prep_reveal`.
2. Add `meta.json` in that folder.
3. Append the photo id to a trail (and add moods or sceneries if they are new).
4. Run `import_catalog` and rebuild.

## Domain (`:journey`)

Package `com.trailpieces.journey`. Kotlin JVM, same style as `:puzzle-engine`. JSON parsed with `org.json` so the parser matches `RevealLoader` and needs no new compiler plugin.

```kotlin
data class Mood(val id: String, val title: String, val summary: String)
data class Scenery(val id: String, val title: String, val summary: String, val story: String)

data class Photo(
    val id: String,
    val title: String,
    val sceneryId: String,
    val moodIds: List<String>,
    val revealId: String,
    val blurb: String,
    val story: String,
)

data class Trail(
    val id: String,
    val title: String,
    val summary: String,
    val photoIds: List<String>,
    val story: String,
)

data class Catalog(
    val version: Int,
    val moods: List<Mood>,
    val sceneries: List<Scenery>,
    val photos: List<Photo>,
    val trails: List<Trail>,
)

enum class PlayerKind { Local, Account }

data class Player(val id: String, val displayName: String?, val kind: PlayerKind)

data class PlayerProgress(val playerId: String, val completedPhotoIds: Set<String>)

data class TrailProgress(
    val trailId: String,
    val collected: Int,
    val total: Int,
    val nextPhotoId: String?,
)
```

`CatalogIndex` answers: moods and sceneries that have at least one photo, photos for a mood, photos for a scenery, trails containing a photo, and `trailProgress`. `TrailProgress.nextPhotoId` is the first uncollected stop in walk order, which is the resume point on the trail screen. `ContinueOffer.nextPhotoId` is the separate scan that starts after the photo just finished.

```kotlin
data class ContinueOffer(
    val trailId: String?,
    val collected: Int,
    val total: Int,
    val nextPhotoId: String?,
)

fun continueAfter(
    catalog: Catalog,
    progress: PlayerProgress,
    photoId: String,
    trailId: String?,
): ContinueOffer
```

`continueAfter` always counts `photoId` as collected for this offer, even when `progress` does not yet. The fraction includes it. The next-stop scan starts at the following stop, wraps once, and must not return `photoId`. Finishing the last open stop therefore reports a full trail and a null `nextPhotoId`, rather than wrapping back onto the photo just placed.

Trail resolution: use `trailId` when that trail lists the photo; otherwise the first trail in catalog order that lists it; otherwise no trail (`trailId` null, `nextPhotoId` null, fraction 0 of 0). The caller keeps the returned `trailId` for the next stop. A later photo does not re-resolve “first trail in catalog order,” which would jump trails when a photo is listed twice.

```kotlin
interface CatalogRepository {
    suspend fun load(): Catalog
}

interface ProgressRepository {
    suspend fun load(): PlayerProgress
    suspend fun recordCompletion(photoId: String): PlayerProgress
}

interface PlayerRepository {
    suspend fun current(): Player
}
```

`recordCompletion` is idempotent. Rules and parsing are unit-tested on the JVM. Android tests are not required for them.

Local documents (app private files, not assets):

```json
{ "version": 1, "id": "<uuid>", "displayName": null, "kind": "local" }
```

```json
{ "version": 1, "playerId": "<uuid>", "completedPhotoIds": ["mossyrock"] }
```

File-backed `PlayerRepository` and `ProgressRepository` live in `:journey` and take a `File`. The app points them at its private storage and keeps one process-wide graph so a completion write is not cancelled when the player leaves the screen. Reading `assets/catalog/catalog.json` stays in `:app`, because that is the asset-shaped part.

The app constructs one graph and passes it into the journey screens:

```kotlin
class JourneyGraph(
    val catalog: CatalogRepository,
    val progress: ProgressRepository,
    val players: PlayerRepository,
)
```

No remote stub classes. The interfaces are the seam.

## App flow

`AppDestination.Journey` opens the journey. Browse state is a small stack the screen owns:

- lens (mood / scenery / trail)
- selected mood id, scenery id, or trail id (the list that opened the photo)
- playing photo id
- trail id carried with the play session (set when opened from a trail; otherwise null until the first completion offer)

Back pops one frame: photo, then the list that opened it, then the lens, then home. It does not adopt the trail Continue resolved.

`RevealScreen` gains two optional parameters and no trail types:

- `initialSceneId: String? = null` — skip the picker and open this scene; back calls `onBack`
- `onCompleted: () -> Unit = {}` — no id argument. Reveal does not know the catalog photo id, and `revealId` may differ from it.

Fire `onCompleted` synchronously when the last piece locks, at the same moment as `playFinale()`. The journey calls `recordCompletion(playingPhotoId)` from a scope that Back does not cancel, and computes the Continue offer then, but does not show it yet. Replay may invoke the callback again; the write is idempotent. The offer’s `trailId` is stored on the session. The reading panel waits for `onPresentationSettled`, described below.

Continue with a `nextPhotoId` replaces the playing photo id, keeps the offer’s `trailId`, and keys `RevealScreen` so the new scene starts clean. The fraction and Continue panel clear when the playing photo id changes. They also clear when the player hits replay on the same photo: replay restores play framing (landscape height-fill, pinch off) and keeps the panel hidden through the puzzle and the next finale, until `onPresentationSettled` fires again. Continue with a null `nextPhotoId` opens that trail when the offer has one, and otherwise returns to the list that opened the photo.

Cover thumbnails are a convention, not a catalog field: `assets/reveal/<revealId>/cover.webp`. The journey records the catalog photo id, never the reveal scene id.

## After the photo is complete

The collection is still saved when the last piece locks (`onCompleted`), before the finale. The reading panel waits.

1. The existing finale runs on the framing used during play. Landscape stays height-filling and pannable. Nothing is drawn over the photo.
2. When the sheen finishes, a landscape photo animates from that framing to a contain fit: the whole photo sits inside the play area. The move is slow, about 1.8s, eased like a lens pulling back, and it keeps the center of the current view until the whole frame is inside. A portrait photo already fits, so it skips this move.
3. Pinch zoom and pan turn on only after that animation (or right after the sheen, for a portrait). The player cannot pinch smaller than the whole-photo fit. They can pinch back in to at least the size the photo had during play, and pan while zoomed in. Gestures stay off during the lens move.
4. The journey then shows the reading panel in the open space around the photo, not on top of it. On a landscape photo the panel uses the band left by the zoom-out. On a portrait photo the photo eases up enough to open that band. The panel scrolls inside itself if the copy is long. It shows, when present:
   - trail name and fraction (`1 of 1 collected`)
   - the photo story, or the scenery story when the photo has none
   - the trail story
   - **Continue**, with the same next-stop rules as before
5. `onPresentationSettled` fires once at step 4. Re-opening a collected photo jumps straight here: full-color contained photo, pinch on, panel visible, no finale and no lens animation. The top bar’s replay control hides the panel, restores play framing, and starts the puzzle from the first piece. The panel stays hidden until the next `onPresentationSettled`.

`story` is an optional string on a photo (`meta.json`), a scenery, and a trail. Blank means “leave it out.” A place such as Multnomah is the photo story, because the shared waterfall scenery also covers Sahalie. The trail story is the path. The scenery story is the short note about that kind of place. The import script copies `story` through. Missing keys stay empty strings, so older catalogs still parse.

Reveal a photo, outside a trail, still plays the finale, the landscape zoom-out, and pinch zoom. It has no reading panel.

## Out of scope

- Accounts, network, and login UI
- Horizontal-photo reveal tuning
- Puzzle-engine behavior
- Invented photos for the rest of a five-stop trail
- Syncing local progress into an account (the `playerId` on the progress document is the hook; no merge code yet)
