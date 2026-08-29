package net.aechronis.vanilla.managers

import net.aechronis.utils.VisibilityRules
import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VanishTest : ManagerTest() {
    @Test
    fun `unvanishing restores the default view rule`() {
        val permissionFlag = "aechronis.dangerously-enable-all-permissions"
        val previousPermissionFlag = System.getProperty(permissionFlag)
        System.setProperty(permissionFlag, "true")
        val viewer = VanillaTest.createPlayer(Pos(0.5, 40.0, 0.5))
        val target = VanillaTest.createPlayer(Pos(2.5, 40.0, 0.5))

        try {
            assertTrue(viewer in target.viewers)

            Vanish.toggle(target)
            assertFalse(viewer in target.viewers)

            Vanish.toggle(target)
            assertTrue(viewer in target.viewers)
        } finally {
            if (Vanish.isVanished(target)) Vanish.toggle(target)
            if (previousPermissionFlag == null) {
                System.clearProperty(permissionFlag)
            } else {
                System.setProperty(permissionFlag, previousPermissionFlag)
            }
            VanillaTest.remove(target)
            VanillaTest.remove(viewer)
        }
    }

    @Test
    fun `players without vanish permission cannot be vanished`() {
        val target = VanillaTest.createPlayer(Pos(12.5, 40.0, 0.5))

        try {
            Vanish.toggle(target)
            assertFalse(Vanish.isVanished(target))
        } finally {
            VanillaTest.remove(target)
        }
    }

    @Test
    fun `spectators are hidden until they leave spectator mode`() {
        val viewer = VanillaTest.createPlayer(Pos(4.5, 40.0, 0.5))
        val target = VanillaTest.createPlayer(Pos(6.5, 40.0, 0.5))

        try {
            assertTrue(viewer in target.viewers)

            target.gameMode = GameMode.SPECTATOR
            assertFalse(viewer in target.viewers)

            target.gameMode = GameMode.SURVIVAL
            assertTrue(viewer in target.viewers)
        } finally {
            VanillaTest.remove(target)
            VanillaTest.remove(viewer)
        }
    }

    @Test
    fun `unvanishing preserves other visibility restrictions`() {
        val permissionFlag = "aechronis.dangerously-enable-all-permissions"
        val previousPermissionFlag = System.getProperty(permissionFlag)
        System.setProperty(permissionFlag, "true")
        val viewer = VanillaTest.createPlayer(Pos(16.5, 40.0, 0.5))
        val target = VanillaTest.createPlayer(Pos(18.5, 40.0, 0.5))
        val externalRule = "test:external"

        try {
            VisibilityRules.set(target, externalRule) { false }
            assertFalse(viewer in target.viewers)

            Vanish.toggle(target)
            VisibilityRules.remove(target, externalRule)
            assertFalse(viewer in target.viewers)

            Vanish.toggle(target)
            assertTrue(viewer in target.viewers)
        } finally {
            VisibilityRules.remove(target, externalRule)
            if (Vanish.isVanished(target)) Vanish.toggle(target)
            if (previousPermissionFlag == null) {
                System.clearProperty(permissionFlag)
            } else {
                System.setProperty(permissionFlag, previousPermissionFlag)
            }
            VanillaTest.remove(target)
            VanillaTest.remove(viewer)
        }
    }

    @Test
    fun `vanished spectators remain hidden until unvanished`() {
        val permissionFlag = "aechronis.dangerously-enable-all-permissions"
        val previousPermissionFlag = System.getProperty(permissionFlag)
        System.setProperty(permissionFlag, "true")
        val viewer = VanillaTest.createPlayer(Pos(8.5, 40.0, 0.5))
        val target = VanillaTest.createPlayer(Pos(10.5, 40.0, 0.5))

        try {
            Vanish.toggle(target)
            assertFalse(viewer in target.viewers)

            target.gameMode = GameMode.SPECTATOR
            target.gameMode = GameMode.SURVIVAL
            assertFalse(viewer in target.viewers)

            Vanish.toggle(target)
            assertTrue(viewer in target.viewers)
        } finally {
            if (Vanish.isVanished(target)) Vanish.toggle(target)
            if (previousPermissionFlag == null) {
                System.clearProperty(permissionFlag)
            } else {
                System.setProperty(permissionFlag, previousPermissionFlag)
            }
            VanillaTest.remove(target)
            VanillaTest.remove(viewer)
        }
    }

    @Test
    fun `vanish state survives a module state handoff`() {
        val permissionFlag = "aechronis.dangerously-enable-all-permissions"
        val previousPermissionFlag = System.getProperty(permissionFlag)
        System.setProperty(permissionFlag, "true")
        val viewer = VanillaTest.createPlayer(Pos(20.5, 40.0, 0.5))
        val target = VanillaTest.createPlayer(Pos(22.5, 40.0, 0.5))

        try {
            Vanish.toggle(target)
            val payload = Vanish.captureTransientState()
            Vanish.toggle(target)

            Vanish.restoreTransientState(payload, listOf(viewer, target))

            assertTrue(Vanish.isVanished(target))
            assertFalse(viewer in target.viewers)
        } finally {
            if (Vanish.isVanished(target)) Vanish.toggle(target)
            if (previousPermissionFlag == null) {
                System.clearProperty(permissionFlag)
            } else {
                System.setProperty(permissionFlag, previousPermissionFlag)
            }
            VanillaTest.remove(target)
            VanillaTest.remove(viewer)
        }
    }

    @Test
    fun `corrupt vanish handoff fails closed`() {
        assertFailsWith<IllegalArgumentException> {
            Vanish.restoreTransientState(byteArrayOf(0, 0, 0, 99), emptyList())
        }
    }

    @Test
    fun `vanish handoff preserves the game mode behind spectator toggle`() {
        val permissionFlag = "aechronis.dangerously-enable-all-permissions"
        val previousPermissionFlag = System.getProperty(permissionFlag)
        System.setProperty(permissionFlag, "true")
        val target = VanillaTest.createPlayer(Pos(24.5, 40.0, 0.5))

        try {
            Vanish.toggle(target)
            target.refreshInput(false, false, false, false, false, true, false)
            target.refreshInput(false, false, false, false, false, false, false)
            target.refreshInput(false, false, false, false, false, true, false)
            assertTrue(target.gameMode == GameMode.SPECTATOR)

            val payload = Vanish.captureTransientState()
            Vanish.toggle(target)
            assertTrue(target.gameMode == GameMode.SURVIVAL)

            Vanish.restoreTransientState(payload, listOf(target))
            assertTrue(Vanish.isVanished(target))
            assertTrue(target.gameMode == GameMode.SPECTATOR)

            Vanish.toggle(target)
            assertTrue(target.gameMode == GameMode.SURVIVAL)
        } finally {
            if (Vanish.isVanished(target)) Vanish.toggle(target)
            if (previousPermissionFlag == null) {
                System.clearProperty(permissionFlag)
            } else {
                System.setProperty(permissionFlag, previousPermissionFlag)
            }
            VanillaTest.remove(target)
        }
    }
}
