package com.trailpieces.app.layers

/**
 * Versioned layers play loops. Keep experiments here so we can A/B without
 * collapsing mechanics into one code path before we settle.
 */
enum class LayersMechanicVersion(val id: String, val label: String) {
    /** Place muted chips → soft park on canvas → color develops in. */
    DEVELOP_PRINT_V1("develop_print_v1", "Develop print"),

    /** Irregular masked pieces snap to home on the empty canvas. */
    SNAP_PLACE_V2("snap_place_v2", "Snap place"),
}

object LayersMechanics {
    val DEFAULT: LayersMechanicVersion = LayersMechanicVersion.SNAP_PLACE_V2
}
