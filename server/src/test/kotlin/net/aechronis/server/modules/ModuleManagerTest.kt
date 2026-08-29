package net.aechronis.server.modules

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class ModuleManagerTest {
    @Test
    fun `generation staging rejects an empty module directory`(
        @TempDir directory: Path,
    ) {
        val error =
            assertFailsWith<IllegalArgumentException> {
                ModuleManager.moduleJarsForGeneration(directory)
            }

        assertContains(error.message.orEmpty(), "No runtime module JARs")
    }
}
