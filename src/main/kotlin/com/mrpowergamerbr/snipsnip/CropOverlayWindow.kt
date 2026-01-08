package com.mrpowergamerbr.snipsnip

import com.mrpowergamerbr.snipsnip.tools.BrushStroke
import com.mrpowergamerbr.snipsnip.tools.DrawingOperation
import com.mrpowergamerbr.snipsnip.tools.FilledRectangle
import com.mrpowergamerbr.snipsnip.tools.TextAnnotation
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.image.BufferedImage
import javax.swing.JColorChooser
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.abs

class CropOverlayWindow(
    private val m: SnipSnipManager,
    private val screenshot: BufferedImage,
    private val monitorGeometry: Rectangle,
    windowInfos: List<WindowInfo>,
    private val onCropComplete: (BufferedImage, WindowInfo?) -> Unit,
    private val onCancel: () -> Unit
) : JFrame() {
    private var selectionStart: Point? = null
    private var selectionEnd: Point? = null
    private var currentCursorPosition: Point? = null
    private var isDragging = false
    private var isKeyboardSelecting = false
    private var hoveredWindow: WindowInfo? = null
    private var selectedWindow: WindowInfo? = null

    // These will be calculated based on actual panel size
    private var scaleX: Double = 1.0
    private var scaleY: Double = 1.0

    // Scale factors to convert panel coordinates to window coordinate space
    private var panelToWindowScaleX: Double = 1.0
    private var panelToWindowScaleY: Double = 1.0

    // Tool state
    private var currentTool = ToolMode.CROP
    private var currentColor = Color(255, 0, 0) // Default red
    private var brushStrokeWidth = 3f
    private var textFontSize = 18
    private var currentFontFamily = m.config.defaultFontFamily

    // Drawing operations list
    private val drawingOperations = mutableListOf<DrawingOperation>()

    // In-progress brush stroke
    private val currentBrushPoints = mutableListOf<Point>()

    // In-progress rectangle
    private var rectangleStart: Point? = null
    private var rectangleEnd: Point? = null

    // Text interaction state (for dragging/editing existing text)
    private var selectedTextIndex: Int? = null
    private var textDragStartPoint: Point? = null
    private var textOriginalPosition: Point? = null

    // Toolbar button bounds (calculated during paint)
    private var toolbarButtonBounds = mutableMapOf<String, Rectangle>()

    // Adjust window infos to be relative to our monitor (accounting for display scale)
    private val adjustedWindowInfos: List<WindowInfo>

    init {
        title = "SnipSnip - Select Region"
        isUndecorated = true
        defaultCloseOperation = DO_NOTHING_ON_CLOSE

        // Position on the active monitor
        bounds = monitorGeometry

        // kdotool returns coordinates in scaled space, same as monitorGeometry
        // But window sizes from kdotool might need to be scaled for the overlay
        adjustedWindowInfos = windowInfos.mapNotNull { info ->
            val adjustedRect = DoubleRectangle(
                info.geometry.x - monitorGeometry.x,
                info.geometry.y - monitorGeometry.y,
                info.geometry.width,
                info.geometry.height
            )
            // Only include windows that are at least partially visible on this monitor
            if (adjustedRect.x + adjustedRect.width > 0 &&
                adjustedRect.y + adjustedRect.height > 0 &&
                adjustedRect.x < monitorGeometry.width &&
                adjustedRect.y < monitorGeometry.height) {
                info.copy(geometry = adjustedRect)
            } else null
        }

        println("Windows Informations:")
        for (windowInfo in adjustedWindowInfos) {
            println("- $windowInfo")
        }

        val panel = object : JPanel() {
            init {
                isDoubleBuffered = true
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val hoveredWindow = this@CropOverlayWindow.hoveredWindow

                // Calculate scale based on actual panel size vs screenshot size
                // This accounts for any Swing HiDPI scaling
                scaleX = screenshot.width.toDouble() / width
                scaleY = screenshot.height.toDouble() / height

                // Calculate scale to convert panel coordinates to window coordinate space
                // Window coordinates are in monitorGeometry space
                panelToWindowScaleX = monitorGeometry.width.toDouble() / width
                panelToWindowScaleY = monitorGeometry.height.toDouble() / height

                // Draw the screenshot scaled to fit the window
                g2d.drawImage(screenshot, 0, 0, width, height, null)

                // Calculate selection rectangle early so we can exclude it from the overlay
                val selectionRect = if (selectionStart != null && selectionEnd != null && currentTool == ToolMode.CROP) {
                    val rect = getSelectionRectangle()
                    // Convert to integer coordinates ONCE to avoid rounding inconsistencies
                    val destX = rect.x.toInt()
                    val destY = rect.y.toInt()
                    val destX2 = (rect.x + rect.width).toInt()
                    val destY2 = (rect.y + rect.height).toInt()
                    Rectangle(destX, destY, destX2 - destX, destY2 - destY)
                } else null

                // Draw semi-transparent overlay, excluding the selection area to avoid flickering
                g2d.color = Color(0, 0, 0, 100)
                if (selectionRect != null && selectionRect.width > 0 && selectionRect.height > 0) {
                    // Draw overlay in 4 parts around the selection to leave selection area untouched
                    // Top
                    g2d.fillRect(0, 0, width, selectionRect.y)
                    // Bottom
                    g2d.fillRect(0, selectionRect.y + selectionRect.height, width, height - (selectionRect.y + selectionRect.height))
                    // Left
                    g2d.fillRect(0, selectionRect.y, selectionRect.x, selectionRect.height)
                    // Right
                    g2d.fillRect(selectionRect.x + selectionRect.width, selectionRect.y, width - (selectionRect.x + selectionRect.width), selectionRect.height)
                } else {
                    g2d.fillRect(0, 0, width, height)
                }

                // Render all completed drawing operations
                for (op in drawingOperations) {
                    renderDrawingOperation(g2d, op)
                }

                // Render in-progress brush stroke
                if (currentBrushPoints.size >= 2) {
                    g2d.color = currentColor
                    g2d.stroke = BasicStroke(brushStrokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    val path = GeneralPath()
                    path.moveTo(currentBrushPoints[0].x.toFloat(), currentBrushPoints[0].y.toFloat())
                    for (i in 1 until currentBrushPoints.size) {
                        path.lineTo(currentBrushPoints[i].x.toFloat(), currentBrushPoints[i].y.toFloat())
                    }
                    g2d.draw(path)
                }

                // Render in-progress rectangle
                if (rectangleStart != null && rectangleEnd != null && currentTool == ToolMode.RECTANGLE) {
                    val rectX = minOf(rectangleStart!!.x, rectangleEnd!!.x)
                    val rectY = minOf(rectangleStart!!.y, rectangleEnd!!.y)
                    val rectW = abs(rectangleEnd!!.x - rectangleStart!!.x)
                    val rectH = abs(rectangleEnd!!.y - rectangleStart!!.y)
                    g2d.color = currentColor
                    g2d.fillRect(rectX, rectY, rectW, rectH)
                }

                // Highlight hovered window (only when not selecting and in CROP mode)
                if (hoveredWindow != null && !isDragging && !isKeyboardSelecting && currentTool == ToolMode.CROP) {
                    // Convert window coordinates to panel coordinates for drawing
                    val panelRect = windowToPanelRect(hoveredWindow.geometry)
                    g2d.color = Color(100, 150, 255, 80)
                    g2d.fillRect(panelRect.x.toInt(), panelRect.y.toInt(), panelRect.width.toInt(), panelRect.height.toInt())
                    g2d.color = Color(100, 150, 255)
                    g2d.stroke = BasicStroke(2f)
                    g2d.drawRect(panelRect.x.toInt(), panelRect.y.toInt(), panelRect.width.toInt(), panelRect.height.toInt())

                    if (m.config.displayProcessInfoWhenHovering) {
                        g2d.color = Color(0, 0, 0, 100)
                        g2d.drawString("${hoveredWindow.processName} (${hoveredWindow.pid})", panelRect.x.toInt() + 5, panelRect.y.toInt() + g2d.fontMetrics.height + 1)

                        g2d.color = Color(255, 255, 255)
                        g2d.drawString("${hoveredWindow.processName} (${hoveredWindow.pid})", panelRect.x.toInt() + 5, panelRect.y.toInt() + g2d.fontMetrics.height)
                    }
                }

                // Draw selection rectangle if selecting (only in CROP mode)
                if (selectionRect != null && selectionRect.width > 0 && selectionRect.height > 0) {
                    // Re-render drawing operations within selection area
                    val clipBounds = g2d.clipBounds
                    g2d.setClip(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height)
                    for (op in drawingOperations) {
                        renderDrawingOperation(g2d, op)
                    }
                    g2d.clip = clipBounds

                    // Draw selection border
                    g2d.color = Color.WHITE
                    g2d.stroke = BasicStroke(2f)
                    g2d.drawRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height)

                    // Draw size indicator
                    val sizeText = "${(selectionRect.width * scaleX).toInt()} x ${(selectionRect.height * scaleY).toInt()}"
                    g2d.font = Font("SansSerif", Font.BOLD, 14)
                    val metrics = g2d.fontMetrics
                    val textWidth = metrics.stringWidth(sizeText)
                    val textX = selectionRect.x + (selectionRect.width - textWidth) / 2
                    val textY = selectionRect.y + selectionRect.height + 20

                    g2d.color = Color(0, 0, 0, 180)
                    g2d.fillRoundRect(textX - 5, textY - 15, textWidth + 10, 20, 5, 5)
                    g2d.color = Color.WHITE
                    g2d.drawString(sizeText, textX, textY)
                }

                // Draw crosshair at current cursor position (only during keyboard mode)
                if (currentCursorPosition != null && isKeyboardSelecting) {
                    g2d.color = Color(255, 255, 255, 200)
                    g2d.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(5f), 0f)
                    g2d.drawLine(currentCursorPosition!!.x, 0, currentCursorPosition!!.x, height)
                    g2d.drawLine(0, currentCursorPosition!!.y, width, currentCursorPosition!!.y)

                    // Draw magnifier for precision pixel selection
                    if (currentTool == ToolMode.CROP) {
                        drawMagnifier(g2d, currentCursorPosition!!, width, height)
                    }
                }

                // Draw instructions at top
                val instructions = when {
                    isKeyboardSelecting && selectionStart != null ->
                        "Arrows to adjust selection (Shift=faster) | Enter to confirm | ESC to cancel"
                    isKeyboardSelecting ->
                        "Arrows to move (Shift=faster) | Space to start selection | Enter on window | ESC to cancel"
                    currentTool == ToolMode.CROP ->
                        "Click+drag to select | Click on window | Arrow keys for precision | ESC to cancel"
                    currentTool == ToolMode.BRUSH ->
                        "Click+drag to draw | ESC to cancel"
                    currentTool == ToolMode.TEXT ->
                        "Click to add text | ESC to cancel"
                    currentTool == ToolMode.RECTANGLE ->
                        "Click+drag to draw rectangle | ESC to cancel"
                    else ->
                        "ESC to cancel"
                }
                g2d.font = Font("SansSerif", Font.PLAIN, 12)
                val instructionMetrics = g2d.fontMetrics
                val instructionWidth = instructionMetrics.stringWidth(instructions)

                g2d.color = Color(0, 0, 0, 200)
                g2d.fillRoundRect((width - instructionWidth) / 2 - 10, 10, instructionWidth + 20, 25, 10, 10)
                g2d.color = Color.WHITE
                g2d.drawString(instructions, (width - instructionWidth) / 2, 27)

                // Draw toolbar
                drawToolbar(g2d, width)
            }

            private fun drawMagnifier(g2d: Graphics2D, cursorPos: Point, panelWidth: Int, panelHeight: Int) {
                val radius = m.config.magnifier.size / 2

                // Calculate magnifier position (offset from cursor, flip if near edges)
                var magX = cursorPos.x + m.config.magnifier.offset
                var magY = cursorPos.y + m.config.magnifier.offset

                // Flip to left side if too close to right edge
                if (magX + m.config.magnifier.size > panelWidth) {
                    magX = cursorPos.x - m.config.magnifier.offset - m.config.magnifier.size
                }
                // Flip to top if too close to bottom edge
                if (magY + m.config.magnifier.size > panelHeight) {
                    magY = cursorPos.y - m.config.magnifier.offset - m.config.magnifier.size
                }
                // Clamp to screen bounds
                magX = magX.coerceIn(0, panelWidth - m.config.magnifier.size)
                magY = magY.coerceIn(0, panelHeight - m.config.magnifier.size)

                val centerX = magX + radius
                val centerY = magY + radius

                // Calculate source region from screenshot (in screenshot coordinates)
                val sourcePixels = m.config.magnifier.size / m.config.magnifier.zoom  // How many source pixels we show
                val srcCenterX = (cursorPos.x * scaleX).toInt()
                val srcCenterY = (cursorPos.y * scaleY).toInt()

                // Source region bounds (clamped to screenshot bounds)
                val srcLeft = (srcCenterX - sourcePixels / 2).coerceIn(0, screenshot.width - 1)
                val srcTop = (srcCenterY - sourcePixels / 2).coerceIn(0, screenshot.height - 1)
                val srcRight = (srcLeft + sourcePixels).coerceIn(0, screenshot.width)
                val srcBottom = (srcTop + sourcePixels).coerceIn(0, screenshot.height)

                // Save the current clip and set circular clip for magnifier
                val oldClip = g2d.clip
                g2d.clip = Ellipse2D.Float(magX.toFloat(), magY.toFloat(), m.config.magnifier.size.toFloat(), m.config.magnifier.size.toFloat())

                // Draw the zoomed portion of the screenshot
                // Use nearest-neighbor interpolation for crisp pixels
                val oldHints = g2d.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)

                g2d.drawImage(
                    screenshot,
                    magX, magY, magX + m.config.magnifier.size, magY + m.config.magnifier.size,
                    srcLeft, srcTop, srcRight, srcBottom,
                    null
                )

                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldHints ?: RenderingHints.VALUE_INTERPOLATION_BILINEAR)

                // Draw pixel grid
                g2d.color = Color(128, 128, 128, 100)
                g2d.stroke = BasicStroke(1f)
                val pixelSize = m.config.magnifier.zoom  // Each source pixel becomes this many display pixels

                // Calculate grid offset based on where the source pixels start
                val gridOffsetX = ((srcCenterX - sourcePixels / 2.0) - srcLeft) * pixelSize
                val gridOffsetY = ((srcCenterY - sourcePixels / 2.0) - srcTop) * pixelSize

                // Draw vertical grid lines
                var x = magX + (pixelSize - gridOffsetX % pixelSize).toInt()
                while (x < magX + m.config.magnifier.size) {
                    g2d.drawLine(x, magY, x, magY + m.config.magnifier.size)
                    x += pixelSize
                }

                // Draw horizontal grid lines
                var y = magY + (pixelSize - gridOffsetY % pixelSize).toInt()
                while (y < magY + m.config.magnifier.size) {
                    g2d.drawLine(magX, y, magX + m.config.magnifier.size, y)
                    y += pixelSize
                }

                // Restore clip
                g2d.clip = oldClip

                // Draw center crosshair (target pixel indicator)
                g2d.color = Color.RED
                g2d.stroke = BasicStroke(1f)
                val crossSize = pixelSize / 2
                g2d.drawLine(centerX - crossSize, centerY, centerX + crossSize, centerY)
                g2d.drawLine(centerX, centerY - crossSize, centerX, centerY + crossSize)

                // Draw magnifier border (circle)
                g2d.color = Color.WHITE
                g2d.stroke = BasicStroke(2f)
                g2d.drawOval(magX, magY, m.config.magnifier.size, m.config.magnifier.size)

                // Draw darker outer border for contrast
                g2d.color = Color(0, 0, 0, 150)
                g2d.stroke = BasicStroke(1f)
                g2d.drawOval(magX - 1, magY - 1, m.config.magnifier.size + 2, m.config.magnifier.size + 2)
            }

            private fun drawToolbar(g2d: Graphics2D, panelWidth: Int) {
                val toolbarY = 50
                val buttonHeight = 28
                val buttonWidth = 60
                val spacing = 10
                val centerX = panelWidth / 2

                // Clear button bounds
                toolbarButtonBounds.clear()

                // Calculate total toolbar width
                val tools = listOf("Crop", "Brush", "Text", "Rect")
                val totalToolsWidth = tools.size * buttonWidth + (tools.size - 1) * spacing
                val sizeControlWidth = 80  // For size controls
                val colorButtonWidth = 50
                val fontButtonWidth = 80  // For font button (only shown for Text tool)
                val totalWidth = totalToolsWidth + spacing * 2 + sizeControlWidth + spacing + colorButtonWidth + spacing + fontButtonWidth

                val startX = centerX - totalWidth / 2

                // Draw toolbar background
                g2d.color = Color(0, 0, 0, 180)
                g2d.fillRoundRect(startX - 10, toolbarY - 5, totalWidth + 20, buttonHeight + 10, 10, 10)

                var currentX = startX

                // Draw tool buttons
                for ((index, toolName) in tools.withIndex()) {
                    val toolMode = when (toolName) {
                        "Crop" -> ToolMode.CROP
                        "Brush" -> ToolMode.BRUSH
                        "Text" -> ToolMode.TEXT
                        "Rect" -> ToolMode.RECTANGLE
                        else -> ToolMode.CROP
                    }
                    val isSelected = currentTool == toolMode

                    // Button background
                    if (isSelected) {
                        g2d.color = Color(100, 150, 255)
                    } else {
                        g2d.color = Color(60, 60, 60)
                    }
                    g2d.fillRoundRect(currentX, toolbarY, buttonWidth, buttonHeight, 5, 5)

                    // Button border
                    g2d.color = if (isSelected) Color(150, 200, 255) else Color(100, 100, 100)
                    g2d.stroke = BasicStroke(1f)
                    g2d.drawRoundRect(currentX, toolbarY, buttonWidth, buttonHeight, 5, 5)

                    // Button text
                    g2d.color = Color.WHITE
                    g2d.font = Font("SansSerif", Font.PLAIN, 12)
                    val fm = g2d.fontMetrics
                    val textX = currentX + (buttonWidth - fm.stringWidth(toolName)) / 2
                    val textY = toolbarY + (buttonHeight + fm.ascent - fm.descent) / 2
                    g2d.drawString(toolName, textX, textY)

                    // Store button bounds
                    toolbarButtonBounds[toolName] = Rectangle(currentX, toolbarY, buttonWidth, buttonHeight)

                    currentX += buttonWidth + spacing
                }

                // Draw size controls (only for Brush and Text)
                if (currentTool == ToolMode.BRUSH || currentTool == ToolMode.TEXT) {
                    currentX += spacing

                    // Decrease button
                    g2d.color = Color(60, 60, 60)
                    g2d.fillRoundRect(currentX, toolbarY, 24, buttonHeight, 5, 5)
                    g2d.color = Color(100, 100, 100)
                    g2d.drawRoundRect(currentX, toolbarY, 24, buttonHeight, 5, 5)
                    g2d.color = Color.WHITE
                    g2d.drawString("-", currentX + 8, toolbarY + 19)
                    toolbarButtonBounds["SizeDown"] = Rectangle(currentX, toolbarY, 24, buttonHeight)

                    currentX += 26

                    // Size value
                    val sizeValue = if (currentTool == ToolMode.BRUSH) brushStrokeWidth.toInt().toString() else textFontSize.toString()
                    g2d.color = Color.WHITE
                    g2d.font = Font("SansSerif", Font.BOLD, 12)
                    val sizeFm = g2d.fontMetrics
                    g2d.drawString(sizeValue, currentX + (28 - sizeFm.stringWidth(sizeValue)) / 2, toolbarY + 19)

                    currentX += 28

                    // Increase button
                    g2d.color = Color(60, 60, 60)
                    g2d.fillRoundRect(currentX, toolbarY, 24, buttonHeight, 5, 5)
                    g2d.color = Color(100, 100, 100)
                    g2d.drawRoundRect(currentX, toolbarY, 24, buttonHeight, 5, 5)
                    g2d.color = Color.WHITE
                    g2d.drawString("+", currentX + 7, toolbarY + 19)
                    toolbarButtonBounds["SizeUp"] = Rectangle(currentX, toolbarY, 24, buttonHeight)

                    currentX += 24 + spacing
                } else {
                    currentX += sizeControlWidth + spacing
                }

                // Draw color button
                currentX += spacing
                g2d.color = Color(60, 60, 60)
                g2d.fillRoundRect(currentX, toolbarY, colorButtonWidth, buttonHeight, 5, 5)
                g2d.color = Color(100, 100, 100)
                g2d.drawRoundRect(currentX, toolbarY, colorButtonWidth, buttonHeight, 5, 5)

                // Color preview square
                g2d.color = currentColor
                g2d.fillRect(currentX + 5, toolbarY + 5, buttonHeight - 10, buttonHeight - 10)
                g2d.color = Color.WHITE
                g2d.drawRect(currentX + 5, toolbarY + 5, buttonHeight - 10, buttonHeight - 10)

                toolbarButtonBounds["Color"] = Rectangle(currentX, toolbarY, colorButtonWidth, buttonHeight)

                // Draw font button (only for Text tool)
                currentX += colorButtonWidth + spacing
                if (currentTool == ToolMode.TEXT) {
                    g2d.color = Color(60, 60, 60)
                    g2d.fillRoundRect(currentX, toolbarY, fontButtonWidth, buttonHeight, 5, 5)
                    g2d.color = Color(100, 100, 100)
                    g2d.drawRoundRect(currentX, toolbarY, fontButtonWidth, buttonHeight, 5, 5)

                    // Font name (truncated if too long)
                    g2d.color = Color.WHITE
                    g2d.font = Font("SansSerif", Font.PLAIN, 10)
                    val fontFm = g2d.fontMetrics
                    val displayName = if (currentFontFamily.length > 10) currentFontFamily.take(9) + "..." else currentFontFamily
                    val fontTextX = currentX + (fontButtonWidth - fontFm.stringWidth(displayName)) / 2
                    val fontTextY = toolbarY + (buttonHeight + fontFm.ascent - fontFm.descent) / 2
                    g2d.drawString(displayName, fontTextX, fontTextY)

                    toolbarButtonBounds["Font"] = Rectangle(currentX, toolbarY, fontButtonWidth, buttonHeight)
                }
            }

            private fun renderDrawingOperation(g2d: Graphics2D, op: DrawingOperation) {
                when (op) {
                    is BrushStroke -> {
                        if (op.points.size >= 2) {
                            g2d.color = op.color
                            g2d.stroke = BasicStroke(op.strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                            val path = GeneralPath()
                            path.moveTo(op.points[0].x.toFloat(), op.points[0].y.toFloat())
                            for (i in 1 until op.points.size) {
                                path.lineTo(op.points[i].x.toFloat(), op.points[i].y.toFloat())
                            }
                            g2d.draw(path)
                        }
                    }
                    is TextAnnotation -> {
                        g2d.color = op.color
                        g2d.font = Font(op.fontFamily, Font.BOLD, op.fontSize)
                        g2d.drawString(op.text, op.position.x, op.position.y)
                    }
                    is FilledRectangle -> {
                        g2d.color = op.color
                        g2d.fillRect(op.rect.x.toInt(), op.rect.y.toInt(), op.rect.width.toInt(), op.rect.height.toInt())
                    }
                }
            }
        }

        panel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    isKeyboardSelecting = false

                    // Check if click is on toolbar
                    val clickedButton = toolbarButtonBounds.entries.find { it.value.contains(e.point) }
                    if (clickedButton != null) {
                        handleToolbarClick(clickedButton.key, panel)
                        return
                    }

                    // Handle based on current tool
                    when (currentTool) {
                        ToolMode.CROP -> {
                            selectionStart = e.point
                            selectionEnd = e.point
                            isDragging = true
                        }
                        ToolMode.BRUSH -> {
                            currentBrushPoints.clear()
                            currentBrushPoints.add(e.point)
                            isDragging = true
                        }
                        ToolMode.TEXT -> {
                            // Check if clicking on existing text
                            val g2d = panel.graphics as? Graphics2D
                            val clickedTextIndex = g2d?.let { findTextAnnotationAt(e.point, it) }

                            if (clickedTextIndex != null) {
                                // Start potential drag/edit operation on existing text
                                selectedTextIndex = clickedTextIndex
                                textDragStartPoint = e.point
                                val textOp = drawingOperations[clickedTextIndex] as TextAnnotation
                                textOriginalPosition = textOp.position
                                isDragging = true
                            } else {
                                // New text placement (existing behavior)
                                val text = JOptionPane.showInputDialog(
                                    this@CropOverlayWindow,
                                    "Enter text:",
                                    "Add Text",
                                    JOptionPane.PLAIN_MESSAGE
                                )
                                if (!text.isNullOrBlank()) {
                                    drawingOperations.add(TextAnnotation(text, e.point, currentColor, textFontSize, currentFontFamily))
                                }
                            }
                        }
                        ToolMode.RECTANGLE -> {
                            rectangleStart = e.point
                            rectangleEnd = e.point
                            isDragging = true
                        }
                    }
                    panel.repaint()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && isDragging) {
                    isDragging = false

                    when (currentTool) {
                        ToolMode.CROP -> {
                            selectionEnd = e.point
                            val rect = getSelectionRectangle()
                            if (rect.width > 5 || rect.height > 5) {
                                // This was a drag - crop the selection
                                cropSelection(rect)
                            } else {
                                // This was a click - check for window
                                val clickedWindow = findWindowAt(e.point, adjustedWindowInfos)
                                if (clickedWindow != null) {
                                    selectedWindow = clickedWindow
                                    // Convert window geometry to panel coordinates for cropping
                                    cropSelection(windowToPanelRect(clickedWindow.geometry))
                                } else {
                                    // No window found, reset selection
                                    selectionStart = null
                                    selectionEnd = null
                                    panel.repaint()
                                }
                            }
                        }
                        ToolMode.BRUSH -> {
                            if (currentBrushPoints.size >= 2) {
                                drawingOperations.add(BrushStroke(currentBrushPoints.toList(), currentColor, brushStrokeWidth))
                            }
                            currentBrushPoints.clear()
                            panel.repaint()
                        }
                        ToolMode.TEXT -> {
                            if (selectedTextIndex != null && textDragStartPoint != null) {
                                val dx = abs(e.point.x - textDragStartPoint!!.x)
                                val dy = abs(e.point.y - textDragStartPoint!!.y)
                                val dragThreshold = 5

                                if (dx < dragThreshold && dy < dragThreshold) {
                                    // This was a click, not a drag - edit the text
                                    val textOp = drawingOperations[selectedTextIndex!!] as TextAnnotation
                                    // Restore original position (in case of minor movement)
                                    drawingOperations[selectedTextIndex!!] = textOp.copy(position = textOriginalPosition!!)

                                    val newText = JOptionPane.showInputDialog(
                                        this@CropOverlayWindow,
                                        "Edit text:",
                                        "Edit Text",
                                        JOptionPane.PLAIN_MESSAGE,
                                        null,
                                        null,
                                        textOp.text
                                    ) as? String

                                    if (newText != null) {
                                        if (newText.isBlank()) {
                                            // Delete the text if empty
                                            drawingOperations.removeAt(selectedTextIndex!!)
                                        } else {
                                            drawingOperations[selectedTextIndex!!] = textOp.copy(text = newText, position = textOriginalPosition!!)
                                        }
                                    }
                                }
                                // If it was a drag (dx >= threshold or dy >= threshold), position is already updated

                                // Reset state
                                selectedTextIndex = null
                                textDragStartPoint = null
                                textOriginalPosition = null
                                panel.repaint()
                            }
                        }
                        ToolMode.RECTANGLE -> {
                            rectangleEnd = e.point
                            if (rectangleStart != null && rectangleEnd != null) {
                                val rectX = minOf(rectangleStart!!.x, rectangleEnd!!.x).toDouble()
                                val rectY = minOf(rectangleStart!!.y, rectangleEnd!!.y).toDouble()
                                val rectW = abs(rectangleEnd!!.x - rectangleStart!!.x).toDouble()
                                val rectH = abs(rectangleEnd!!.y - rectangleStart!!.y).toDouble()
                                if (rectW > 2 && rectH > 2) {
                                    drawingOperations.add(FilledRectangle(DoubleRectangle(rectX, rectY, rectW, rectH), currentColor))
                                }
                            }
                            rectangleStart = null
                            rectangleEnd = null
                            panel.repaint()
                        }
                    }
                }
            }
        })

        panel.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (isDragging) {
                    when (currentTool) {
                        ToolMode.CROP -> {
                            selectionEnd = e.point
                        }
                        ToolMode.BRUSH -> {
                            currentBrushPoints.add(e.point)
                        }
                        ToolMode.TEXT -> {
                            // Update text position while dragging
                            if (selectedTextIndex != null && textDragStartPoint != null && textOriginalPosition != null) {
                                val dx = e.point.x - textDragStartPoint!!.x
                                val dy = e.point.y - textDragStartPoint!!.y
                                val oldText = drawingOperations[selectedTextIndex!!] as TextAnnotation
                                val newPosition = Point(textOriginalPosition!!.x + dx, textOriginalPosition!!.y + dy)
                                drawingOperations[selectedTextIndex!!] = oldText.copy(position = newPosition)
                            }
                        }
                        ToolMode.RECTANGLE -> {
                            rectangleEnd = e.point
                        }
                    }
                    panel.repaint()
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                currentCursorPosition = e.point
                if (currentTool == ToolMode.CROP) {
                    hoveredWindow = findWindowAt(e.point, adjustedWindowInfos)
                }
                if (!isKeyboardSelecting) {
                    panel.repaint()
                }
            }
        })

        panel.isFocusable = true
        panel.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ESCAPE -> {
                        if (selectionStart != null || currentBrushPoints.isNotEmpty() || rectangleStart != null) {
                            // Cancel current operation
                            selectionStart = null
                            selectionEnd = null
                            currentBrushPoints.clear()
                            rectangleStart = null
                            rectangleEnd = null
                            panel.repaint()
                        } else {
                            dispose()
                            onCancel()
                        }
                    }
                    KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT -> {
                        if (currentTool == ToolMode.CROP) {
                            handleArrowKey(e.keyCode, e.isShiftDown)
                            panel.repaint()
                        }
                    }
                    KeyEvent.VK_SPACE -> {
                        if (currentTool == ToolMode.CROP && isKeyboardSelecting && currentCursorPosition != null) {
                            if (selectionStart == null) {
                                // Start selection at current cursor position
                                selectionStart = Point(currentCursorPosition)
                                selectionEnd = Point(currentCursorPosition)
                            }
                            panel.repaint()
                        }
                    }
                    KeyEvent.VK_ENTER -> {
                        if (currentTool == ToolMode.CROP) {
                            if (selectionStart != null && selectionEnd != null) {
                                val rect = getSelectionRectangle()
                                if (rect.width > 5 && rect.height > 5) {
                                    cropSelection(rect)
                                }
                            } else if (isKeyboardSelecting && currentCursorPosition != null) {
                                // Capture hovered window with Enter
                                val clickedWindow = findWindowAt(currentCursorPosition!!, adjustedWindowInfos)
                                if (clickedWindow != null) {
                                    selectedWindow = clickedWindow
                                    // Convert window geometry to panel coordinates for cropping
                                    cropSelection(windowToPanelRect(clickedWindow.geometry))
                                }
                            }
                        }
                    }
                    // Tool switching shortcuts
                    KeyEvent.VK_1, KeyEvent.VK_C -> {
                        currentTool = ToolMode.CROP
                        resetToolState()
                        panel.repaint()
                    }
                    KeyEvent.VK_2, KeyEvent.VK_B -> {
                        currentTool = ToolMode.BRUSH
                        resetToolState()
                        panel.repaint()
                    }
                    KeyEvent.VK_3, KeyEvent.VK_T -> {
                        currentTool = ToolMode.TEXT
                        resetToolState()
                        panel.repaint()
                    }
                    KeyEvent.VK_4, KeyEvent.VK_R -> {
                        currentTool = ToolMode.RECTANGLE
                        resetToolState()
                        panel.repaint()
                    }
                    // Size adjustment shortcuts
                    KeyEvent.VK_OPEN_BRACKET -> { // [
                        if (currentTool == ToolMode.BRUSH) {
                            brushStrokeWidth = (brushStrokeWidth - 1f).coerceIn(1f, 20f)
                        } else if (currentTool == ToolMode.TEXT) {
                            textFontSize = (textFontSize - 2).coerceIn(12, 48)
                        }
                        panel.repaint()
                    }
                    KeyEvent.VK_CLOSE_BRACKET -> { // ]
                        if (currentTool == ToolMode.BRUSH) {
                            brushStrokeWidth = (brushStrokeWidth + 1f).coerceIn(1f, 20f)
                        } else if (currentTool == ToolMode.TEXT) {
                            textFontSize = (textFontSize + 2).coerceIn(12, 48)
                        }
                        panel.repaint()
                    }
                }
            }
        })

        contentPane = panel
        isVisible = true
        panel.requestFocusInWindow()

        isAlwaysOnTop = false // We don't want it always on top because that makes kdialog be always "behind" the screen
    }

    private fun handleArrowKey(keyCode: Int, fastMove: Boolean) {
        val moveAmount = if (fastMove) 10 else 1
        isKeyboardSelecting = true

        if (currentCursorPosition == null) {
            currentCursorPosition = Point(width / 2, height / 2)
        }

        when (keyCode) {
            KeyEvent.VK_UP -> currentCursorPosition = Point(
                currentCursorPosition!!.x,
                maxOf(0, currentCursorPosition!!.y - moveAmount)
            )
            KeyEvent.VK_DOWN -> currentCursorPosition = Point(
                currentCursorPosition!!.x,
                minOf(height - 1, currentCursorPosition!!.y + moveAmount)
            )
            KeyEvent.VK_LEFT -> currentCursorPosition = Point(
                maxOf(0, currentCursorPosition!!.x - moveAmount),
                currentCursorPosition!!.y
            )
            KeyEvent.VK_RIGHT -> currentCursorPosition = Point(
                minOf(width - 1, currentCursorPosition!!.x + moveAmount),
                currentCursorPosition!!.y
            )
        }

        // Update selection end if we have a selection in progress
        if (selectionStart != null) {
            selectionEnd = Point(currentCursorPosition)
        }

        // Update hovered window
        hoveredWindow = findWindowAt(currentCursorPosition!!, adjustedWindowInfos)
    }

    private fun handleToolbarClick(buttonName: String, panel: JPanel) {
        when (buttonName) {
            "Crop" -> currentTool = ToolMode.CROP
            "Brush" -> currentTool = ToolMode.BRUSH
            "Text" -> currentTool = ToolMode.TEXT
            "Rect" -> currentTool = ToolMode.RECTANGLE
            "SizeDown" -> {
                if (currentTool == ToolMode.BRUSH) {
                    brushStrokeWidth = (brushStrokeWidth - 1f).coerceIn(1f, 20f)
                } else if (currentTool == ToolMode.TEXT) {
                    textFontSize = (textFontSize - 2).coerceIn(12, 48)
                }
            }
            "SizeUp" -> {
                if (currentTool == ToolMode.BRUSH) {
                    brushStrokeWidth = (brushStrokeWidth + 1f).coerceIn(1f, 20f)
                } else if (currentTool == ToolMode.TEXT) {
                    textFontSize = (textFontSize + 2).coerceIn(12, 48)
                }
            }
            "Color" -> {
                if (m.config.useKDialogForColorPicking) {
                    val process = ProcessBuilder("kdialog", "--getcolor", "--default", ColorUtils.convertFromColorToHex(currentColor.rgb)).start()
                    val exitValue = process.waitFor()
                    if (exitValue == 0) {
                        // If the exit value is != 0, then the user closed the dialog
                        currentColor = Color.decode(process.inputStream.bufferedReader().readLine())
                    }
                } else {
                    val newColor = JColorChooser.showDialog(this, "Choose Color", currentColor)
                    if (newColor != null) {
                        currentColor = newColor
                    }
                }
            }
            "Font" -> {
                val fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
                val selectedFont = JOptionPane.showInputDialog(
                    this,
                    "Select font:",
                    "Choose Font",
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    fonts,
                    currentFontFamily
                )
                if (selectedFont != null) {
                    currentFontFamily = selectedFont as String
                }
            }
        }
        resetToolState()
        panel.repaint()
    }

    private fun resetToolState() {
        selectionStart = null
        selectionEnd = null
        currentBrushPoints.clear()
        rectangleStart = null
        rectangleEnd = null
    }

    /**
     * Convert a point from panel coordinates to window coordinate space
     */
    private fun panelToWindowCoords(point: Point): Point {
        return Point(
            (point.x * panelToWindowScaleX).toInt(),
            (point.y * panelToWindowScaleY).toInt()
        )
    }

    /**
     * Convert a rectangle from window coordinate space to panel coordinates
     */
    private fun windowToPanelRect(rect: DoubleRectangle): DoubleRectangle {
        return DoubleRectangle(
            (rect.x / panelToWindowScaleX),
            (rect.y / panelToWindowScaleY),
            (rect.width / panelToWindowScaleX),
            (rect.height / panelToWindowScaleY)
        )
    }

    private fun findWindowAt(panelPoint: Point, windows: List<WindowInfo>): WindowInfo? {
        // Convert panel coordinates to window coordinate space
        val windowPoint = panelToWindowCoords(panelPoint)
        // Return the FIRST (topmost) window containing the point
        // Windows are already sorted by stacking order (topmost first)
        return windows.firstOrNull {
            it.geometry.contains(windowPoint.x.toDouble(), windowPoint.y.toDouble())
        }
    }

    private fun findTextAnnotationAt(point: Point, g2d: Graphics2D): Int? {
        // Iterate in reverse (topmost/most recent first)
        for (i in drawingOperations.indices.reversed()) {
            val op = drawingOperations[i]
            if (op is TextAnnotation) {
                val font = Font(op.fontFamily, Font.BOLD, op.fontSize)
                val metrics = g2d.getFontMetrics(font)
                val textWidth = metrics.stringWidth(op.text)
                val textHeight = metrics.height
                // Text baseline is at position.y, so bounds extend upward
                val bounds = Rectangle(
                    op.position.x,
                    op.position.y - metrics.ascent,
                    textWidth,
                    textHeight
                )
                if (bounds.contains(point)) {
                    return i
                }
            }
        }
        return null
    }

    private fun getSelectionRectangle(): DoubleRectangle {
        val start = selectionStart ?: return DoubleRectangle(0.0, 0.0, 0.0, 0.0)
        val end = selectionEnd ?: return DoubleRectangle(0.0, 0.0, 0.0, 0.0)

        val x = minOf(start.x, end.x)
        val y = minOf(start.y, end.y)
        val width = abs(end.x - start.x)
        val height = abs(end.y - start.y)

        return DoubleRectangle(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
    }

    private fun cropSelection(screenRect: DoubleRectangle) {
        // Convert screen coordinates to screenshot coordinates
        val imgX = (screenRect.x * scaleX).toInt().coerceIn(0, screenshot.width - 1)
        val imgY = (screenRect.y * scaleY).toInt().coerceIn(0, screenshot.height - 1)
        val imgW = (screenRect.width * scaleX).toInt().coerceIn(1, screenshot.width - imgX)
        val imgH = (screenRect.height * scaleY).toInt().coerceIn(1, screenshot.height - imgY)

        // Create a composited image with screenshot + drawing operations
        val compositedScreenshot = BufferedImage(screenshot.width, screenshot.height, BufferedImage.TYPE_INT_ARGB)
        val g2d = compositedScreenshot.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.drawImage(screenshot, 0, 0, null)

        // Render all drawing operations, scaled from panel to screenshot coords
        for (op in drawingOperations) {
            renderDrawingOperationToScreenshot(g2d, op)
        }
        g2d.dispose()

        val croppedImage = compositedScreenshot.getSubimage(imgX, imgY, imgW, imgH)

        // Create a copy of the subimage (getSubimage returns a view, not a copy)
        val copiedImage = BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB)
        val g = copiedImage.createGraphics()
        g.drawImage(croppedImage, 0, 0, null)
        g.dispose()

        dispose()
        onCropComplete(copiedImage, selectedWindow)
    }

    private fun renderDrawingOperationToScreenshot(g2d: Graphics2D, op: DrawingOperation) {
        when (op) {
            is BrushStroke -> {
                if (op.points.size >= 2) {
                    g2d.color = op.color
                    g2d.stroke = BasicStroke(op.strokeWidth * scaleX.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    val path = GeneralPath()
                    path.moveTo((op.points[0].x * scaleX).toFloat(), (op.points[0].y * scaleY).toFloat())
                    for (i in 1 until op.points.size) {
                        path.lineTo((op.points[i].x * scaleX).toFloat(), (op.points[i].y * scaleY).toFloat())
                    }
                    g2d.draw(path)
                }
            }
            is TextAnnotation -> {
                g2d.color = op.color
                val scaledFontSize = (op.fontSize * scaleX).toInt()
                g2d.font = Font(op.fontFamily, Font.BOLD, scaledFontSize)
                g2d.drawString(op.text, (op.position.x * scaleX).toInt(), (op.position.y * scaleY).toInt())
            }
            is FilledRectangle -> {
                g2d.color = op.color
                g2d.fillRect(
                    (op.rect.x * scaleX).toInt(),
                    (op.rect.y * scaleY).toInt(),
                    (op.rect.width * scaleX).toInt(),
                    (op.rect.height * scaleY).toInt()
                )
            }
        }
    }

    enum class ToolMode { CROP, BRUSH, TEXT, RECTANGLE }
}