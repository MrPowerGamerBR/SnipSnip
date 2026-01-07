package net.perfectdreams.snipsnip

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.math.roundToInt
import kotlin.system.exitProcess

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

fun main() {
    SwingUtilities.invokeLater {
        SnipSnip().start()
    }
}

data class MonitorInfo(
    val geometry: Rectangle,
    val scale: Double
)

class SnipSnip {
    private val screenshotsDir = File(System.getProperty("user.home"), "Imagens/SnipSnip").apply { mkdirs() }

    fun start() {
        // Get the active monitor name and geometry
        val activeOutput = getActiveOutputName()
        val monitorInfo = getMonitorInfo(activeOutput)

        if (monitorInfo == null) {
            JOptionPane.showMessageDialog(null, "Could not detect monitor geometry", "Error", JOptionPane.ERROR_MESSAGE)
            exitProcess(1)
        }

        // Get visible window geometries before taking the screenshot
        val windowInfos = getVisibleWindowInfos()

        // Take screenshot of the current monitor
        val screenshotFile = File.createTempFile("snipsnip_", ".png")
        screenshotFile.deleteOnExit()

        val captureSuccess = captureCurrentMonitor(screenshotFile, activeOutput)
        if (!captureSuccess) {
            JOptionPane.showMessageDialog(null, "Failed to capture screenshot", "Error", JOptionPane.ERROR_MESSAGE)
            exitProcess(1)
        }

        val screenshot = ImageIO.read(screenshotFile)
        if (screenshot == null) {
            JOptionPane.showMessageDialog(null, "Failed to load screenshot", "Error", JOptionPane.ERROR_MESSAGE)
            exitProcess(1)
        }

        // Show the crop overlay window
        CropOverlayWindow(
            screenshot = screenshot,
            monitorGeometry = monitorInfo.geometry,
            displayScale = monitorInfo.scale,
            windowInfos = windowInfos,
            onCropComplete = { croppedImage, selectedWindow ->
                saveCroppedImage(croppedImage, selectedWindow)
                exitProcess(0)
            },
            onCancel = {
                exitProcess(0)
            }
        )
    }

    private fun getActiveOutputName(): String {
        val process = ProcessBuilder(
            "qdbus6", "org.kde.KWin", "/KWin", "org.kde.KWin.activeOutputName"
        ).start()
        return process.inputStream.bufferedReader().readText().trim()
    }

    private fun getMonitorInfo(outputName: String): MonitorInfo? {
        val process = ProcessBuilder("kscreen-doctor", "--json").start()
        val jsonOutput = process.inputStream.bufferedReader().readText()

        val json = Json { ignoreUnknownKeys = true }
        val config = json.decodeFromString<KScreenConfig>(jsonOutput)

        val output = config.outputs.find { it.name == outputName && it.enabled }
            ?: return null

        // Calculate scaled dimensions (the actual display size)
        val scaledWidth = (output.size.width / output.scale).roundToInt()
        val scaledHeight = (output.size.height / output.scale).roundToInt()

        return MonitorInfo(
            geometry = Rectangle(output.pos.x, output.pos.y, scaledWidth, scaledHeight),
            scale = output.scale
        )
    }

    /**
     * Get monitor bounds in physical pixel coordinates for cropping the portal screenshot.
     * The portal screenshot is in physical pixels, so we need physical coordinates.
     */
    private fun getMonitorPhysicalBounds(outputName: String): Rectangle? {
        val process = ProcessBuilder("kscreen-doctor", "--json").start()
        val jsonOutput = process.inputStream.bufferedReader().readText()

        val json = Json { ignoreUnknownKeys = true }
        val config = json.decodeFromString<KScreenConfig>(jsonOutput)

        val output = config.outputs.find { it.name == outputName && it.enabled }
            ?: return null

        // Position is in logical coordinates, convert to physical by multiplying by scale
        // Size is already in physical pixels
        return Rectangle(
            (output.pos.x * output.scale).roundToInt(),
            (output.pos.y * output.scale).roundToInt(),
            output.size.width,
            output.size.height
        )
    }

    /**
     * Capture a full-screen screenshot using the XDG Desktop Portal Screenshot API.
     * This captures the entire virtual desktop (all monitors).
     */
    private fun captureFullScreenViaPortal(): File? {
        val handleToken = "snipsnip${System.currentTimeMillis()}"

        // Start monitoring for the response signal in the background
        val monitorProcess = ProcessBuilder(
            "gdbus", "monitor", "--session",
            "--dest", "org.freedesktop.portal.Desktop"
        ).start()

        // Make the screenshot request
        val callProcess = ProcessBuilder(
            "gdbus", "call", "--session",
            "--dest", "org.freedesktop.portal.Desktop",
            "--object-path", "/org/freedesktop/portal/desktop",
            "--method", "org.freedesktop.portal.Screenshot.Screenshot",
            "", "{'handle_token': <'$handleToken'>, 'interactive': <false>}"
        ).start()
        callProcess.waitFor()

        // Read monitor output line by line until we find our response
        val reader = monitorProcess.inputStream.bufferedReader()
        var uri: String? = null
        val timeout = System.currentTimeMillis() + 10000 // 10 second timeout

        while (System.currentTimeMillis() < timeout) {
            if (reader.ready()) {
                val line = reader.readLine() ?: break
                // Look for the Response signal with our handle token
                if (line.contains(handleToken) && line.contains("Response")) {
                    // Parse the URI from the response
                    // Format: ...Response (uint32 0, {'uri': <'file:///path/to/file'>})
                    val uriMatch = Regex("""'uri':\s*<'([^']+)'>""").find(line)
                    if (uriMatch != null) {
                        uri = uriMatch.groupValues[1]
                        break
                    }
                }
            } else {
                Thread.sleep(50)
            }
        }

        // Clean up the monitor process
        monitorProcess.destroy()

        if (uri == null) {
            println("Failed to capture screenshot via portal: no URI received")
            return null
        }

        // Convert file:// URI to File path
        val filePath = uri.removePrefix("file://")
        val screenshotFile = File(filePath)

        if (!screenshotFile.exists()) {
            println("Screenshot file does not exist: $filePath")
            return null
        }

        return screenshotFile
    }

    private fun captureCurrentMonitor(outputFile: File, activeOutputName: String): Boolean {
        // Get physical pixel bounds for cropping
        val physicalBounds = getMonitorPhysicalBounds(activeOutputName)
        if (physicalBounds == null) {
            println("Failed to get physical bounds for monitor: $activeOutputName")
            return false
        }

        // Capture full screen via portal
        val fullScreenshotFile = captureFullScreenViaPortal()
        if (fullScreenshotFile == null) {
            println("Failed to capture screenshot via portal")
            return false
        }

        val fullImage = ImageIO.read(fullScreenshotFile)
        if (fullImage == null) {
            println("Failed to read screenshot image")
            return false
        }

        println("Full screenshot size: ${fullImage.width}x${fullImage.height}")
        println("Cropping to physical bounds: $physicalBounds")

        // Crop to current monitor (physical pixel coordinates)
        val croppedImage = fullImage.getSubimage(
            physicalBounds.x,
            physicalBounds.y,
            physicalBounds.width,
            physicalBounds.height
        )

        ImageIO.write(croppedImage, "PNG", outputFile)
        return outputFile.exists()
    }

    /**
     * Get visible windows in stacking order (topmost first) using KWin scripting API.
     * This ensures we can properly handle overlapping windows.
     */
    private fun getVisibleWindowInfos(): List<WindowInfo> {
        // Desktop components to filter out
        val desktopComponents = setOf(
            "plasmashell",
            "krunner",
            "kded5",
            "kded6",
            "kwin_wayland",
            "kwin_x11",
            "xdg-desktop-portal",
            "xdg-desktop-portal-kde"
        )

        val uuid = UUID.randomUUID()

        println("Script UUID is $uuid")

        // TODO: Use createTempFile later, we are using this because it is easier for debugging purposes
        // We use a randomUUID to avoid hard-to-know debugging sessions of "why tf it isn't working???" because it was loading a previously working result
        val scriptFile = File("scripts", "script_$uuid.js")
        scriptFile.writeText(
            SnipSnip::class.java.getResourceAsStream("/script.js")
                .readAllBytes()
                .toString(Charsets.UTF_8)
                .replace("{randomUUID}", uuid.toString())
        )

        println("Temporary file is at ${scriptFile.absolutePath}")

        // The output will be the script ID (example: 67)
        val scriptId = ProcessBuilder("qdbus6", "org.kde.KWin", "/Scripting", "org.kde.kwin.Scripting.loadScript", scriptFile.absolutePath)
            .start()
            .apply {
                this.waitFor()
            }
            .inputStream
            .readAllBytes()
            .toString(Charsets.UTF_8)
            .trim() // Yeah... you need this

        println("Script ID: $scriptId")

        // And now we run it!
        // This will output to the journalctl (sadly)
        val runProcess = ProcessBuilder("qdbus6", "org.kde.KWin", "/Scripting/Script$scriptId", "org.kde.kwin.Script.run")
            .start()
        val exitValue = runProcess.waitFor()

        println("Status Code: $exitValue")
        println("Run Script Result: ${runProcess.inputStream.bufferedReader().readText()}")

        // Wait a moment for script to execute
        Thread.sleep(100)

        // Read output from journalctl
        val journalProcess = ProcessBuilder("journalctl", "--user", "-t", "kwin_wayland", "-n", "50", "--no-pager", "-o", "cat").start()

        val journalLines = journalProcess.inputStream
            .bufferedReader()
            .readLines()

        println("Journal Lines:")
        for (line in journalLines) {
            println("- $line")
        }

        val scriptOutput = journalLines
            .reversed() // We revert it because we want to get the latest one that matches our magic value
            .first {
                it.startsWith("SNIPSNIP_OUTPUT_$uuid:")
            }
            .removePrefix("SNIPSNIP_OUTPUT_$uuid:")

        println("Stacking output: $scriptOutput")

        // Contrary to popular belief
        // (also known as StackOverflow)
        // the right way to stop the script is with the *gasp* stop argument, which also unloads the script it seems
        // (You can see all parameters with "qdbus6 org.kde.KWin")
        ProcessBuilder("qdbus6", "org.kde.KWin", "/Scripting/Script$scriptId", "org.kde.kwin.Scripting.stop").start().waitFor()

        // Script output is bottom -> top
        val stackingInfo = Json.decodeFromString<List<SnipStackingInfo>>(scriptOutput)

        val visibleWindows = stackingInfo
            .reversed()
            .filterNot { it.minimized }
            .filter { it.resourceName !in desktopComponents }
            .map {
                WindowInfo(
                    id = it.internalId,
                    geometry = DoubleRectangle(it.geometry.x, it.geometry.y, it.geometry.width, it.geometry.height),
                    it.resourceName,
                    it.pid
                )
            }

        println("Visible Windows (top -> bottom):")
        for (window in visibleWindows) {
            println("- $window")
        }

        return visibleWindows
    }

    private fun saveCroppedImage(image: BufferedImage, selectedWindow: WindowInfo?) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))

        val filename = if (selectedWindow?.processName != null) {
            "${timestamp}_${selectedWindow.processName}.png"
        } else {
            "${timestamp}.png"
        }

        val outputFile = File(screenshotsDir, filename)
        ImageIO.write(image, "PNG", outputFile)

        println("Screenshot saved to: ${outputFile.absolutePath}")

        // Now we need to copy it to the clipboard using "wl-copy"
        val process = ProcessBuilder("wl-copy")
            .start()

        // We write the saved file bytes to the InputStream of wl-copy, which works fine (yayy!)
        // Maybe we could write everything to a ByteArray first and then write to the file and to here, but for now, it works :)
        process.outputStream.use {
            it.write(outputFile.readBytes())
        }

        process.waitFor()
    }
}

data class WindowInfo(
    val id: String,
    val geometry: DoubleRectangle,
    val processName: String?,
    val pid: Int?
)

class CropOverlayWindow(
    private val screenshot: BufferedImage,
    private val monitorGeometry: Rectangle,
    private val displayScale: Double,
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

                // Draw semi-transparent overlay
                g2d.color = Color(0, 0, 0, 100)
                g2d.fillRect(0, 0, width, height)

                // Highlight hovered window (only when not selecting)
                if (hoveredWindow != null && !isDragging && !isKeyboardSelecting) {
                    // Convert window coordinates to panel coordinates for drawing
                    val panelRect = windowToPanelRect(hoveredWindow!!.geometry)
                    g2d.color = Color(100, 150, 255, 80)
                    g2d.fillRect(panelRect.x.toInt(), panelRect.y.toInt(), panelRect.width.toInt(), panelRect.height.toInt())
                    g2d.color = Color(100, 150, 255)
                    g2d.stroke = BasicStroke(2f)
                    g2d.drawRect(panelRect.x.toInt(), panelRect.y.toInt(), panelRect.width.toInt(), panelRect.height.toInt())
                }

                // Draw selection rectangle if selecting
                if (selectionStart != null && selectionEnd != null) {
                    val rect = getSelectionRectangle()

                    // Clear the overlay in the selection area to show original screenshot
                    g2d.drawImage(
                        screenshot,
                        rect.x.toInt(), rect.y.toInt(), (rect.x + rect.width).toInt(), (rect.y + rect.height).toInt(),
                        (rect.x * scaleX).toInt(), (rect.y * scaleY).toInt(),
                        ((rect.x + rect.width) * scaleX).toInt(), ((rect.y + rect.height) * scaleY).toInt(),
                        null
                    )

                    // Draw selection border
                    g2d.color = Color.WHITE
                    g2d.stroke = BasicStroke(2f)
                    g2d.drawRect(rect.x.toInt(), rect.y.toInt(), rect.width.toInt(), rect.height.toInt())

                    // Draw size indicator
                    val sizeText = "${(rect.width * scaleX).toInt()} x ${(rect.height * scaleY).toInt()}"
                    g2d.font = Font("SansSerif", Font.BOLD, 14)
                    val metrics = g2d.fontMetrics
                    val textWidth = metrics.stringWidth(sizeText)
                    val textX = rect.x + (rect.width - textWidth) / 2
                    val textY = rect.y + rect.height + 20

                    g2d.color = Color(0, 0, 0, 180)
                    g2d.fillRoundRect((textX - 5).toInt(), (textY - 15).toInt(), textWidth + 10, 20, 5, 5)
                    g2d.color = Color.WHITE
                    g2d.drawString(sizeText, textX.toInt(), textY.toInt())
                }

                // Draw crosshair at current cursor position (only during keyboard mode)
                if (currentCursorPosition != null && isKeyboardSelecting) {
                    g2d.color = Color(255, 255, 255, 200)
                    g2d.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(5f), 0f)
                    g2d.drawLine(currentCursorPosition!!.x, 0, currentCursorPosition!!.x, height)
                    g2d.drawLine(0, currentCursorPosition!!.y, width, currentCursorPosition!!.y)
                }

                // Draw instructions at top
                val instructions = when {
                    isKeyboardSelecting && selectionStart != null ->
                        "Arrows to adjust selection (Shift=faster) | Enter to confirm | ESC to cancel"
                    isKeyboardSelecting ->
                        "Arrows to move (Shift=faster) | Space to start selection | Enter on window | ESC to cancel"
                    else ->
                        "Click+drag to select | Click on window | Arrow keys for precision | ESC to cancel"
                }
                g2d.font = Font("SansSerif", Font.PLAIN, 12)
                val metrics = g2d.fontMetrics
                val instructionWidth = metrics.stringWidth(instructions)

                g2d.color = Color(0, 0, 0, 200)
                g2d.fillRoundRect((width - instructionWidth) / 2 - 10, 10, instructionWidth + 20, 25, 10, 10)
                g2d.color = Color.WHITE
                g2d.drawString(instructions, (width - instructionWidth) / 2, 27)
            }
        }

        panel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    isKeyboardSelecting = false
                    // Always start a potential selection/drag
                    selectionStart = e.point
                    selectionEnd = e.point
                    isDragging = true
                    panel.repaint()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && isDragging) {
                    isDragging = false
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
            }
        })

        panel.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (isDragging) {
                    selectionEnd = e.point
                    panel.repaint()
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                currentCursorPosition = e.point
                hoveredWindow = findWindowAt(e.point, adjustedWindowInfos)
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
                        if (selectionStart != null) {
                            // Cancel current selection
                            selectionStart = null
                            selectionEnd = null
                            panel.repaint()
                        } else {
                            dispose()
                            onCancel()
                        }
                    }
                    KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT -> {
                        handleArrowKey(e.keyCode, e.isShiftDown)
                        panel.repaint()
                    }
                    KeyEvent.VK_SPACE -> {
                        if (isKeyboardSelecting && currentCursorPosition != null) {
                            if (selectionStart == null) {
                                // Start selection at current cursor position
                                selectionStart = Point(currentCursorPosition)
                                selectionEnd = Point(currentCursorPosition)
                            }
                            panel.repaint()
                        }
                    }
                    KeyEvent.VK_ENTER -> {
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
            }
        })

        contentPane = panel
        isVisible = true
        panel.requestFocusInWindow()

        // Use toolkit to try to make this window always on top
        isAlwaysOnTop = true
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

    private fun getSelectionRectangle(): DoubleRectangle {
        val start = selectionStart ?: return DoubleRectangle(0.0, 0.0, 0.0, 0.0)
        val end = selectionEnd ?: return DoubleRectangle(0.0, 0.0, 0.0, 0.0)

        val x = minOf(start.x, end.x)
        val y = minOf(start.y, end.y)
        val width = kotlin.math.abs(end.x - start.x)
        val height = kotlin.math.abs(end.y - start.y)

        return DoubleRectangle(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
    }

    private fun cropSelection(screenRect: DoubleRectangle) {
        // Convert screen coordinates to screenshot coordinates
        val imgX = (screenRect.x * scaleX).toInt().coerceIn(0, screenshot.width - 1)
        val imgY = (screenRect.y * scaleY).toInt().coerceIn(0, screenshot.height - 1)
        val imgW = (screenRect.width * scaleX).toInt().coerceIn(1, screenshot.width - imgX)
        val imgH = (screenRect.height * scaleY).toInt().coerceIn(1, screenshot.height - imgY)

        val croppedImage = screenshot.getSubimage(imgX, imgY, imgW, imgH)

        // Create a copy of the subimage (getSubimage returns a view, not a copy)
        val copiedImage = BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB)
        val g = copiedImage.createGraphics()
        g.drawImage(croppedImage, 0, 0, null)
        g.dispose()

        dispose()
        onCropComplete(copiedImage, selectedWindow)
    }
}

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