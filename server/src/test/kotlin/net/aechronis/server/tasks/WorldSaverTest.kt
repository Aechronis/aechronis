package net.aechronis.server.tasks

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldSaverTest {
    @Test
    fun `checkpoint waits for module and chunk persistence`() {
        val modules = CompletableFuture<Void>()
        val chunks = CompletableFuture<Void>()
        var chunksStarted = false

        val checkpoint =
            saveCheckpointAsync(
                { modules },
                {
                    chunksStarted = true
                    chunks
                },
            )
        assertFalse(checkpoint.isDone)
        assertFalse(chunksStarted)

        modules.complete(null)
        assertFalse(checkpoint.isDone)
        assertTrue(chunksStarted)

        chunks.complete(null)
        checkpoint.join()
        assertTrue(checkpoint.isDone)
    }

    @Test
    fun `checkpoint skips chunk persistence when module preparation throws`() {
        var chunksStarted = false
        val checkpoint =
            saveCheckpointAsync(
                saveModules = { error("module save failed") },
                saveChunks = {
                    chunksStarted = true
                    CompletableFuture.completedFuture(null)
                },
            )

        assertFalse(chunksStarted)
        assertFailsWith<CompletionException> { checkpoint.join() }
    }

    @Test
    fun `serial queue waits for the previous save even after a failure`() {
        val queue = SerialFutureQueue()
        val first = CompletableFuture<Void>()
        var secondStarted = false

        val firstResult = queue.submit { first }
        val secondResult =
            queue.submit {
                secondStarted = true
                CompletableFuture.completedFuture(null)
            }

        assertFalse(secondStarted)
        first.completeExceptionally(IllegalStateException("failed"))
        assertFailsWith<CompletionException> { firstResult.join() }
        secondResult.join()
        assertTrue(secondStarted)
        assertEquals(true, secondResult.isDone)
    }
}
