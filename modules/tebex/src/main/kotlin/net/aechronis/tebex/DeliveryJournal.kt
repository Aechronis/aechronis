package net.aechronis.tebex

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Write-ahead journal: ambiguous executions require operator reconciliation, never automatic replay. */
internal class DeliveryJournal(
    private val path: Path,
) {
    private val states = mutableMapOf<Long, String>()
    private var failed = false
    var nextPoll: Long = 0
        private set

    init {
        Files.createDirectories(path.parent)
        if (Files.exists(path)) {
            val contents = Files.readString(path)
            check(
                contents.isEmpty() || contents.endsWith('\n'),
            ) { "Incomplete Tebex journal; restore or reconcile it before enabling deliveries" }
            contents.lineSequence().filter(String::isNotEmpty).forEach { line ->
                val parts = line.split(' ')
                check(parts.size == 2 && parts[0] in setOf("P", "D", "A", "R", "N")) { "Invalid Tebex journal" }
                val id = parts[1].toLong()
                check(id >= 0) { "Invalid Tebex journal ID" }
                apply(parts[0], id)
            }
        }
    }

    @Synchronized
    fun state(id: Long): String? = states[id]

    @Synchronized
    fun completed(): List<Long> = states.filterValues { it == "D" }.keys.toList()

    @Synchronized
    fun uncertain(): List<Long> = states.filterValues { it == "P" }.keys.toList()

    @Synchronized
    fun record(
        state: String,
        id: Long,
    ) {
        require(state in setOf("P", "D", "A", "R", "N") && id >= 0)
        check(!failed) { "Tebex journal is unavailable after a write failure" }
        try {
            FileChannel.open(path, CREATE, WRITE, APPEND).use { channel ->
                val bytes = ByteBuffer.wrap("$state $id\n".toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
        } catch (error: Exception) {
            failed = true
            throw error
        }
        apply(state, id)
    }

    private fun apply(
        state: String,
        id: Long,
    ) {
        when (state) {
            "N" -> nextPoll = id
            "R" -> states.remove(id)
            else -> states[id] = state
        }
    }
}
