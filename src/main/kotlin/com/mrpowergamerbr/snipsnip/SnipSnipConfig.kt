package com.mrpowergamerbr.snipsnip

import kotlinx.serialization.Serializable

@Serializable
data class SnipSnipConfig(
    val screenshotsFolder: String,
    val useKDialogForColorPicking: Boolean,
    val defaultFontFamily: String,
    val magnifier: Magnifier
) {
    @Serializable
    data class Magnifier(
        val zoom: Int,
        val offset: Int,
        val size: Int
    )
}