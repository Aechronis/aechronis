package net.aechronis.vanilla.managers

import net.aechronis.vanilla.ManagerTest
import net.aechronis.vanilla.VanillaTest
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhitelistTest : ManagerTest() {
    @Test
    fun `names are added and removed case insensitively`() {
        val name = "manager-test-${UUID.randomUUID()}"
        try {
            Whitelist.add(name)

            assertTrue(Whitelist.isWhitelistedName(name.uppercase()))
            assertTrue(Files.exists(VanillaTest.pluginRoot.resolve("whitelist.json")))

            Whitelist.remove(name.uppercase())

            assertFalse(Whitelist.isWhitelistedName(name))
        } finally {
            Whitelist.remove(name)
        }
    }

    @Test
    fun `entries default to tier one and support tier two`() {
        val weakName = "weak-${UUID.randomUUID()}"
        val adminName = "admin-${UUID.randomUUID()}"
        val unknownUuid = UUID.randomUUID()

        try {
            Whitelist.add(weakName)
            Whitelist.add(adminName, Whitelist.SUPER_ADMIN_TIER)

            assertEquals(Whitelist.WEAK_TIER, Whitelist.whitelistTier(unknownUuid, weakName))
            assertEquals(Whitelist.SUPER_ADMIN_TIER, Whitelist.whitelistTier(unknownUuid, adminName))
            assertFailsWith<IllegalArgumentException> { Whitelist.add("invalid-${UUID.randomUUID()}", 3) }
        } finally {
            Whitelist.remove(weakName)
            Whitelist.remove(adminName)
        }
    }

    @Test
    fun `toggle persists the enabled state`() {
        val initial = Whitelist.enabled

        try {
            val toggled = Whitelist.toggle()

            assertEquals(!initial, toggled)
            assertEquals(toggled, Files.readString(VanillaTest.pluginRoot.resolve("whitelist-enabled.txt")).trim().toBoolean())
        } finally {
            if (Whitelist.enabled != initial) Whitelist.toggle()
        }
    }

    @Test
    fun `corrupt whitelist data is ignored`() {
        val file = VanillaTest.pluginRoot.resolve("whitelist-corrupt.json")
        Files.writeString(file, "not json")

        try {
            Whitelist.init(file)
            assertFalse(Whitelist.isWhitelistedName("not json"))
        } finally {
            Files.deleteIfExists(file)
            Whitelist.init(VanillaTest.pluginRoot.resolve("whitelist.json"))
        }
    }
}
