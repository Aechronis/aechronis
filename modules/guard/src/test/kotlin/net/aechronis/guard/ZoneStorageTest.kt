package net.aechronis.guard

import net.aechronis.guard.flags.BooleanFlagValue
import net.aechronis.guard.flags.FlagName
import net.aechronis.guard.flags.StringListFlagValue
import net.aechronis.guard.objects.Zone
import net.aechronis.guard.objects.ZoneBounds
import net.aechronis.guard.storage.ZoneRegistry
import net.aechronis.guard.storage.ZoneStorage
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ZoneStorageTest {
    @Test
    fun `saves and reloads the protection flags`() {
        val path = Files.createTempDirectory("guard-flag-storage-test").resolve("zones.json")
        val zone =
            Zone(
                "protection",
                ZoneBounds(0, 0, 0, 4, 4, 4),
                flags =
                    mapOf(
                        FlagName.DAMAGE to BooleanFlagValue(false),
                        FlagName.EXPLOSION to BooleanFlagValue(false),
                        FlagName.VEHICLE_SPAWN to BooleanFlagValue(false),
                        FlagName.OTHER_DAMAGE to BooleanFlagValue(false),
                    ),
            )

        ZoneStorage().save(path, listOf(zone))

        assertEquals(listOf(zone), ZoneStorage().load(path))
    }

    @Test
    fun `loads legacy instance-scoped zones as global zones`() {
        val path = Files.createTempDirectory("guard-instance-migration-test").resolve("zones.json")
        Files.writeString(
            path,
            """{"zones":[{"name":"spawn","instanceId":"d5ae5978-ac5d-395a-9872-e2ba4ae1b7c3","priority":0,"bounds":{"minX":0,"minY":0,"minZ":0,"maxX":4,"maxY":4,"maxZ":4},"flags":{}}]}""",
        )
        val storage = ZoneStorage()

        val registry = ZoneRegistry()
        registry.replaceAll(storage.load(path))
        assertEquals("spawn", registry.find(2, 2, 2)?.name)

        storage.save(path, registry.all())
        assertFalse(Files.readString(path).contains("instanceId"))
    }

    @Test
    fun `saves and reloads zones with typed flags`() {
        val path =
            Files.createTempDirectory("guard-storage-test").resolve("zones.json")
        val zone =
            Zone(
                "spawn",
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
