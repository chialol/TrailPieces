package com.trailpieces.journey

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object CatalogParser {
    const val VERSION = 1

    fun parse(json: String): Catalog {
        try {
            return parseRoot(JSONObject(json))
        } catch (error: CatalogFormatException) {
            throw error
        } catch (error: JSONException) {
            throw CatalogFormatException(error.message ?: "Invalid catalog JSON")
        }
    }

    private fun parseRoot(root: JSONObject): Catalog {
        val version = root.getInt("version")
        if (version != VERSION) {
            throw CatalogFormatException("Unsupported catalog version $version")
        }
        val moods = parseMoods(root.getJSONArray("moods"))
        val sceneries = parseSceneries(root.getJSONArray("sceneries"))
        val photos = parsePhotos(root.getJSONArray("photos"), sceneries, moods)
        val trails = parseTrails(root.getJSONArray("trails"), photos)
        return Catalog(
            version = version,
            moods = moods,
            sceneries = sceneries,
            photos = photos,
            trails = trails,
        )
    }

    private fun parseMoods(array: JSONArray): List<Mood> {
        val moods = readObjects(array, "moods") { item ->
            Mood(
                id = requiredId(item, "mood"),
                title = item.getString("title"),
                summary = item.optString("summary", ""),
            )
        }
        requireUnique(moods.map { it.id }, "mood")
        return moods
    }

    private fun parseSceneries(array: JSONArray): List<Scenery> {
        val sceneries = readObjects(array, "sceneries") { item ->
            Scenery(
                id = requiredId(item, "scenery"),
                title = item.getString("title"),
                summary = item.optString("summary", ""),
                story = item.optString("story", ""),
            )
        }
        requireUnique(sceneries.map { it.id }, "scenery")
        return sceneries
    }

    private fun parsePhotos(
        array: JSONArray,
        sceneries: List<Scenery>,
        moods: List<Mood>,
    ): List<Photo> {
        val sceneryIds = sceneries.map { it.id }.toSet()
        val moodIds = moods.map { it.id }.toSet()
        val photos = readObjects(array, "photos") { item ->
            val id = requiredId(item, "photo")
            val sceneryId = item.getString("sceneryId")
            if (sceneryId !in sceneryIds) {
                throw CatalogFormatException("Photo $id uses unknown scenery $sceneryId")
            }
            val photoMoods = stringList(item.getJSONArray("moodIds"))
            val unknown = photoMoods.filter { it !in moodIds }
            if (unknown.isNotEmpty()) {
                throw CatalogFormatException("Photo $id uses unknown mood ${unknown.first()}")
            }
            val revealId = item.optString("revealId", "").ifBlank { id }
            Photo(
                id = id,
                title = item.getString("title"),
                sceneryId = sceneryId,
                moodIds = photoMoods,
                revealId = revealId,
                blurb = item.optString("blurb", ""),
                story = item.optString("story", ""),
            )
        }
        requireUnique(photos.map { it.id }, "photo")
        return photos
    }

    private fun parseTrails(array: JSONArray, photos: List<Photo>): List<Trail> {
        val photoIds = photos.map { it.id }.toSet()
        val trails = readObjects(array, "trails") { item ->
            val id = requiredId(item, "trail")
            val stops = stringList(item.getJSONArray("photoIds"))
            requireUnique(stops, "photo on trail $id")
            val missing = stops.filter { it !in photoIds }
            if (missing.isNotEmpty()) {
                throw CatalogFormatException("Trail $id lists unknown photo ${missing.first()}")
            }
            Trail(
                id = id,
                title = item.getString("title"),
                summary = item.optString("summary", ""),
                photoIds = stops,
                story = item.optString("story", ""),
            )
        }
        requireUnique(trails.map { it.id }, "trail")
        return trails
    }

    private fun <T> readObjects(array: JSONArray, label: String, map: (JSONObject) -> T): List<T> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw CatalogFormatException("$label[$index] is not an object")
                add(map(item))
            }
        }
    }

    private fun requiredId(item: JSONObject, kind: String): String {
        val id = item.optString("id", "").trim()
        if (id.isEmpty()) throw CatalogFormatException("A $kind is missing an id")
        return id
    }

    private fun stringList(array: JSONArray): List<String> {
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index, "").trim()
                if (value.isEmpty()) throw CatalogFormatException("Expected a string id")
                add(value)
            }
        }
    }

    private fun requireUnique(ids: List<String>, kind: String) {
        val duplicate = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
        if (duplicate != null) {
            throw CatalogFormatException("Duplicate $kind id ${duplicate.key}")
        }
    }
}
