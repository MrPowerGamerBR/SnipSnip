package com.mrpowergamerbr.snipsnip

data class DoubleRectangle(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
) {
    fun contains(x: Double, y: Double): Boolean {
        return x in this.x..(this.x + this.width) && y in this.y..(this.y + this.height)
    }
}