package net.aechronis.votifier

import java.nio.file.Files
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VotifierConfigStoreTest {
    @Test
    fun `creates normalized config and persistent v1 key`() {
        val directory = Files.createTempDirectory("votifier-test")
        try {
            val store = VotifierConfigStore(directory)
            store.reload()
            assertTrue(Files.exists(directory.resolve("config.json")))

            Files.writeString(
                directory.resolve("config.json"),
                """{"protocolV1Enabled":false,"tokens":{"  site-a  ":"  secret  ","":"ignored"}}""",
            )
            store.reload()
            assertEquals(false, store.current().protocolV1Enabled)
            assertEquals(mapOf("site-a" to "secret"), store.current().tokens)

            val created = store.loadOrCreateProtocolV1Key()
            val loaded = store.loadOrCreateProtocolV1Key()
            assertTrue(Files.exists(directory.resolve("public.key")))
            assertTrue(Files.exists(directory.resolve("private.key")))
            assertTrue(created.public.encoded.contentEquals(loaded.public.encoded))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }
}
