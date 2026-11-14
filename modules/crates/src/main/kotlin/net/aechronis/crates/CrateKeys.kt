package net.aechronis.crates

import kotlinx.serialization.json.Json
import net.aechronis.utils.JsonRegistryFile
import java.nio.file.Path
import java.util.UUID

/**
 * Digital crate keys, keyed by player UUID then crate id, persisted in crates/keys.json.
 * Other modules grant keys through [give]; it works for offline players too.
 */
object CrateKeys {
    private val keys = linkedMapOf<String, MutableMap<String, Int>>()
    private val storage = JsonRegistryFile("crate keys")

    internal fun init(path: Path) {
        storage.load(path, keys) { Json.decodeFromString<Map<String, MutableMap<String, Int>>>(it) }
    }

    fun count(
        player: UUID,
        crate: String,
    ): Int = keys[player.toString()]?.get(crate) ?: 0

    fun all(player: UUID): Map<String, Int> = keys[player.toString()]?.filterValues { it > 0 }.orEmpty()

    /** Grants [amount] keys for [crate]. Returns false when the crate does not exist or keys cannot be saved. */
    fun give(
        player: UUID,
        crate: String,
        amount: Int = 1,
    ): Boolean {
        if (amount <= 0 || !storage.canSave || !Crates.exists(crate)) return false
        val owned = keys.getOrPut(player.toString()) { linkedMapOf() }
        owned[crate] = Math.addExact(owned[crate] ?: 0, amount)
        save()
        return true
    }

    /** Removes up to [amount] keys and returns how many were removed. */
    fun take(
        player: UUID,
        crate: String,
        amount: Int,
    ): Int {
        if (amount <= 0 || !storage.canSave) return 0
        val owned = keys[player.toString()] ?: return 0
        val removed = minOf(amount, owned[crate] ?: 0)
        if (removed == 0) return 0
        val remaining = owned.getValue(crate) - removed
        if (remaining > 0) owned[crate] = remaining else owned.remove(crate)
        if (owned.isEmpty()) keys.remove(player.toString())
        save()
        return removed
    }

    private fun save() = storage.save { Json.encodeToString(keys) }
}
