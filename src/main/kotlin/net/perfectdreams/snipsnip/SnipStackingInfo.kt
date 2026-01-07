package net.perfectdreams.snipsnip

import kotlinx.serialization.Serializable

@Serializable
data class SnipStackingInfo(
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