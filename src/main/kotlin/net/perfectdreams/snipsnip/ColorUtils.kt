package net.perfectdreams.snipsnip

object ColorUtils {
    fun convertFromColorToHex(input: Int): String {
        val red = input shr 16 and 0xFF
        val green = input shr 8 and 0xFF
        val blue = input and 0xFF

        val hexRed = red.toString(16).padStart(2, '0')
        val hexGreen = green.toString(16).padStart(2, '0')
        val hexBlue = blue.toString(16).padStart(2, '0')
        return "#$hexRed$hexGreen$hexBlue"
    }
}