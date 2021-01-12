package io.github.openminigameserver.worldedit

import net.minestom.server.Bootstrap

object MinestomWorldEditBootstrapper {
    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("minestom.extension.indevfolder.classes", "build/classes/java")
        System.setProperty("minestom.extension.indevfolder.resources", "build/resources/main/")
        Bootstrap.bootstrap("io.github.openminigameserver.worldedit.MinestomWorldEditServer", args)
    }
}