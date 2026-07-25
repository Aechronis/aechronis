package net.aechronis.logger

import net.aechronis.logger.objects.PendingRollbackRegistry
import net.aechronis.logger.objects.RollbackOperationKind
import net.aechronis.logger.objects.RollbackPlan
import net.aechronis.logger.params.LookupQuery
import net.aechronis.logger.params.ParamManager
import net.aechronis.logger.params.ParseResult
import net.aechronis.logger.utils.parseMutation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RollbackParsingTest {
    @Test
    fun `normal rollback rejects player inventory actions`() {
        assertNotNull(parseMutation(arrayOf("a:inventory", "t:1h")).error)
        assertNotNull(parseMutation(arrayOf("a:+inventory", "t:1h")).error)
        assertNotNull(parseMutation(arrayOf("a:-inventory", "t:1h")).error)
    }

    @Test
    fun `time ranges and global radius parse`() {
        val result = ParamManager.parse(arrayOf("t:1.5h-30m", "r:#global", "a:block"))
        val query = (result as ParseResult.Ok).query as LookupQuery.Block
        val now = System.currentTimeMillis()

        assertTrue(query.params.global)
        assertNull(query.params.radius)
        assertTrue(now - query.params.since!! in 90 * 60 * 1000L..90 * 60 * 1000L + 1000)
        assertTrue(now - query.params.until!! in 30 * 60 * 1000L..30 * 60 * 1000L + 1000)
    }

    @Test
    fun `feature time ranges retain upper bound`() {
        val result = ParamManager.parse(arrayOf("s:test", "t:1h-30m"))
        val query = (result as ParseResult.Ok).query as LookupQuery.Feature
        val until = requireNotNull(query.params.until)

        assertTrue(query.params.since!! < until)
    }

    @Test
    fun `disconnect cleanup invalidates confirmation`() {
        val playerUuid = UUID.randomUUID()
        val plan =
            RollbackPlan(
                kind = RollbackOperationKind.ROLLBACK,
                instanceUuid = UUID.randomUUID(),
                targetTs = 0,
                queryDesc = "test",
                safeMode = true,
                blockChanges = emptyList(),
                skippedBlockCount = 0,
            )
        val token = PendingRollbackRegistry.register(playerUuid, plan)

        PendingRollbackRegistry.clearPlayer(playerUuid)

        assertNull(PendingRollbackRegistry.consume(playerUuid, token))
    }
}
