package net.aechronis.vanilla

import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener

internal class KeepRunningListener : LauncherSessionListener {
    override fun launcherSessionClosed(session: LauncherSession) {
        if (System.getProperty("keepRunning") == "true") {
            Thread.currentThread().join()
        }
    }
}
