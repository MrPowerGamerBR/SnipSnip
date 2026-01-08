package com.mrpowergamerbr.snipsnip

import kotlinx.serialization.Serializable

@Serializable
data class PlasmaWorkspaceInfo(
    val windows: List<PlasmaWindow>,
    val cursor: Cursor
) {
    @Serializable
    data class PlasmaWindow(
        val caption: String,
        // Same ID used by kdotool
        val internalId: String,
        val geometry: Geometry,
        val minimized: Boolean,
        val pid: Int,
        val resourceClass: String,
        val resourceName: String
    ) {
        @Serializable
        data class Geometry(val x: Double, val y: Double, val width: Double, val height: Double)
    }

    @Serializable
    data class Cursor(
        val x: Double,
        val y: Double
    )
}