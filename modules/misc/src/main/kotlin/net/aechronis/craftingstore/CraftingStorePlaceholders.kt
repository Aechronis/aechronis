package net.aechronis.craftingstore

import java.util.concurrent.atomic.AtomicReference

/** Non-blocking placeholder resolver backed by immutable published strings. */
class CraftingStorePlaceholders {
    private val donators = AtomicReference<List<String>>(emptyList())
    private val payments = AtomicReference<List<String>>(emptyList())

    fun resolve(identifier: String): String = replace(identifier)

    fun replace(identifier: String): String {
        val key = identifier.removePrefix("craftingstore_").lowercase()
        return when {
            key == "donator" -> donators.get().joinToString(", ")
            key.startsWith("donator_") -> indexed(donators.get(), key.removePrefix("donator_").toIntOrNull())
            key == "payment" -> payments.get().joinToString(", ")
            key.startsWith("payment_") -> indexed(payments.get(), key.removePrefix("payment_").toIntOrNull())
            else -> ""
        }
    }

    fun publishDonators(values: List<Pair<String, String>>) {
        donators.set(values.map { "${it.first}: ${it.second}" }.toList())
    }

    fun publishPayments(values: List<Pair<String, String>>) {
        payments.set(values.map { "${it.first}: ${it.second}" }.toList())
    }

    private fun indexed(
        values: List<String>,
        index: Int?,
    ): String = index?.takeIf { it in 1..5 }?.let { values.getOrNull(it - 1) } ?: ""
}
