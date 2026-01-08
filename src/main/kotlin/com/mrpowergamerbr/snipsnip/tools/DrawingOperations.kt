package com.mrpowergamerbr.snipsnip.tools

import com.mrpowergamerbr.snipsnip.DoubleRectangle
import java.awt.Color
import java.awt.Point

// Drawing operation classes for annotation tools
sealed class DrawingOperation

data class BrushStroke(
    val points: List<Point>,
    val color: Color,
    val strokeWidth: Float
) : DrawingOperation()

data class TextAnnotation(
    val text: String,
    val position: Point,
    val color: Color,
    val fontSize: Int,
    val fontFamily: String
) : DrawingOperation()

data class FilledRectangle(
    val rect: DoubleRectangle,
    val color: Color
) : DrawingOperation()