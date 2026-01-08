package com.mrpowergamerbr.snipsnip

import com.typesafe.config.ConfigFactory
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.hocon.decodeFromConfig
import java.io.File
import javax.swing.SwingUtilities

object SnipSnipLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        val configurationFile = File(System.getProperty("conf") ?: "./snipsnip.conf")
        val config = Hocon.decodeFromConfig<SnipSnipConfig>(ConfigFactory.parseFile(configurationFile).resolve())

        SnipSnipManager(config, args.contains("--daemon")).start()
    }
}