package net.aechronis.guard

import net.aechronis.guard.flags.BooleanFlagValue
import net.aechronis.guard.flags.FlagName
import net.aechronis.guard.flags.StringListFlagValue
import net.aechronis.guard.objects.Zone
import net.aechronis.guard.objects.ZoneBounds
import net.aechronis.guard.storage.ZoneStorage
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals

class ZoneStorageTest {
    @Test
    fun `saves and reloads zones with typed flags`() {
        val path =
            Files.createTempDirectory("guard-storage-test").resolve("zones.json")
        val zone =
            Zone(
                "spawn",
                UUID.randomUUID(),
                ZoneBounds(-2, 0, -2, 2, 80, 2),
                priority = 4,
                flags =
                    mapOf(
                        FlagName.BLOCK_PLACE to BooleanFlagValue(false),
                        FlagName.BLOCK_INTERACT to
                            StringListFlagValue(
                                listOf("woah", "wow"),
                            ),
                    ),
            )

        ZoneStorage().save(path, listOf(zone))

        assertEquals(listOf(zone), ZoneStorage().load(path))
    }
}
