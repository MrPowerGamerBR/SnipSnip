package com.mrpowergamerbr.snipsnip.kscreendoctor

import kotlinx.serialization.Serializable
import java.awt.Rectangle

// JSON data classes for kscreen-doctor --json output
@Serializable
data class KScreenConfig(
    val outputs: List<KScreenOutput>
)

@Serializable
data class KScreenOutput(
    val name: String,
    val enabled: Boolean,
    val pos: KScreenPosition,
    val size: KScreenSize,
    val scale: Double
)

@Serializable
data class KScreenPosition(
    val x: Int,
    val y: Int
)

@Serializable
data class KScreenSize(
    val width: Int,
    val height: Int
)

data class MonitorInfo(
    val geometry: Rectangle,
    val scale: Double
)