package com.mrpowergamerbr.snipsnip

data class WindowInfo(
    val id: String,
    val geometry: DoubleRectangle,
    val processName: String?,
    val pid: Int?
)