package com.trailpieces.app.journey

import android.content.Context
import com.trailpieces.journey.Catalog
import com.trailpieces.journey.CatalogParser
import com.trailpieces.journey.CatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AssetCatalogRepository(
    private val context: Context,
) : CatalogRepository {
    override suspend fun load(): Catalog = withContext(Dispatchers.IO) {
        context.assets.open("catalog/catalog.json").bufferedReader().use { reader ->
            CatalogParser.parse(reader.readText())
        }
    }
}
