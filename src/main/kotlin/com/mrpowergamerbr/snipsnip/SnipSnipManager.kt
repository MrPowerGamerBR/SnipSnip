package com.mrpowergamerbr.snipsnip

import com.mrpowergamerbr.snipsnip.kscreendoctor.KScreenConfig
import com.mrpowergamerbr.snipsnip.kscreendoctor.MonitorInfo
import kotlinx.serialization.json.Json
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import kotlin.math.roundToInt
import kotlin.system.exitProcess

class SnipSnipManager(val config: SnipSnipConfig) {
    private val screenshotsFolder = File(config.screenshotsFolder)

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

        val fullMonitorScreenshot = captureFullScreenViaSpectacle()
        val monitorScreenshot = cropImageToCurrentMonitor(fullMonitorScreenshot, monitorInfo)

        // Show the crop overlay window
        CropOverlayWindow(
            m = this,
            screenshot = monitorScreenshot,
            monitorGeometry = monitorInfo.geometry,
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

        println("Scaled Width: $scaledWidth, Scaled Height: $scaledHeight")
        return MonitorInfo(
            geometry = Rectangle(
                output.pos.x,
                output.pos.y,
                (output.size.width / output.scale).roundToInt(),
                (output.size.height / output.scale).roundToInt()
            ),
            logical = Rectangle(
                (output.pos.x * output.scale).roundToInt(),
                (output.pos.y * output.scale).roundToInt(),
                output.size.width,
                output.size.height
            ),
            scale = output.scale
        )
    }

    /**
     * Capture a full-screen screenshot using Spectacle.
     *
     * This captures the entire virtual desktop (all monitors).
     */
    private fun captureFullScreenViaSpectacle(): BufferedImage {
        // Using the desktop portal would be cool, but there's a smol bug:
        // When taking the screenshot via the portal in a non-interactive manner, a dummy app with the "KDE" logo shows up for ~5 seconds
        // So we will use spectacle OKAY SPECTACLE WON BECAUSE OF WOKE......... (just kidding)
        val spectacleProcess = ProcessBuilder("spectacle", "--fullscreen", "--background", "--nonotify", "--output", "/proc/self/fd/1")
            .start()
        val screenshotImageAsBytes = spectacleProcess.inputStream.readAllBytes()
        val exitValue = spectacleProcess.waitFor()

        if (exitValue != 0)
            error("Spectacle failed with exit code $exitValue")

        return ImageIO.read(screenshotImageAsBytes.inputStream())
    }

    private fun cropImageToCurrentMonitor(fullImage: BufferedImage, activeMonitorInfo: MonitorInfo): BufferedImage {
        println("Full screenshot size: ${fullImage.width}x${fullImage.height}")

        // Crop to current monitor (physical pixel coordinates)
        val croppedImage = fullImage.getSubimage(
            activeMonitorInfo.logical.x,
            activeMonitorInfo.logical.y,
            activeMonitorInfo.logical.width,
            activeMonitorInfo.logical.height
        )

        return croppedImage
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
            SnipSnipManager::class.java.getResourceAsStream("/script.js")
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

        val outputFile = File(screenshotsFolder, filename)
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