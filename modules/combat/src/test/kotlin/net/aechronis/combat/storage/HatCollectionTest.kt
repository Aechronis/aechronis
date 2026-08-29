package net.aechronis.combat.storage

import net.aechronis.combat.CombatTestServer
import net.aechronis.combat.objects.Hat
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HatCollectionTest {
    @BeforeAll
    fun setup() {
        CombatTestServer.initialize()
    }

    @Test
    fun `replacement store adopts online players without losing hats`(
        @TempDir temporaryDirectory: Path,
    ) {
        val uuid = UUID.randomUUID()
        val originalHat = hat("original")
        val addedAfterReload = hat("after-reload")
        val original = HatCollectionStore(temporaryDirectory)
        original.initialize(emptyList())
        original.give(uuid, originalHat)
        original.shutdown()

        val replacement = HatCollectionStore(temporaryDirectory)
        replacement.initialize(listOf(uuid))

        assertTrue(replacement.owns(uuid, originalHat))
        replacement.give(uuid, addedAfterReload)
        replacement.shutdown()

        val saved = Files.readString(temporaryDirectory.resolve("$uuid.json"))
        assertTrue(saved.contains("\"original\""))
        assertTrue(saved.contains("\"after-reload\""))
    }

    @Test
    fun `give lazily loads an existing file before saving`(
        @TempDir temporaryDirectory: Path,
    ) {
        val uuid = UUID.randomUUID()
        Files.writeString(temporaryDirectory.resolve("$uuid.json"), """{"hats":["original"]}""")
        val store = HatCollectionStore(temporaryDirectory)
        store.initialize(emptyList())

        store.give(uuid, hat("after-reload"))

        assertTrue(store.owns(uuid, hat("original")))
        assertTrue(store.owns(uuid, hat("after-reload")))
        assertFalse(Files.readString(temporaryDirectory.resolve("$uuid.json")).contains("\"hats\":[]"))
    }

    @Test
    fun `malformed existing collection fails before it can be overwritten`(
        @TempDir temporaryDirectory: Path,
    ) {
        val uuid = UUID.randomUUID()
        val file = temporaryDirectory.resolve("$uuid.json")
        val malformed = """{"not-hats":["original"]}"""
        Files.writeString(file, malformed)
        val store = HatCollectionStore(temporaryDirectory)
        store.initialize(emptyList())

        assertFailsWith<IllegalArgumentException> {
            store.give(uuid, hat("replacement"))
        }
        assertTrue(Files.readString(file) == malformed)
    }

    private fun hat(name: String): Hat = Hat(name, Component.text(name))
}
