package net.aechronis.logger.params

import net.aechronis.logger.objects.BlockAction

data class LookupParams(
    val users: List<String> = emptyList(), // u:
    val since: Long? = null, // t: cutoff timestamp
    val radius: Int? = null, // r:
    val chunkRadius: Int? = null, // cr: 1 = own chunk, n = (2n-1)x(2n-1) chunks
    val actions: Set<BlockAction>? = null, // a: null = all actions
    val include: List<String> = emptyList(), // i: block keys
    val exclude: List<String> = emptyList(), // e: block keys
) {
    fun human(): String {
        val parts = mutableListOf<String>()
        if (users.isNotEmpty()) parts += "users=${users.joinToString(",")}"
        since?.let { parts += "since=${(System.currentTimeMillis() - it) / 1000}s ago" }
        radius?.let { parts += "radius=$it" }
        chunkRadius?.let { parts += "chunkRadius=$it" }
        actions?.let { parts += "actions=${it.joinToString(",")}" }
        if (include.isNotEmpty()) parts += "include=${include.joinToString(",")}"
        if (exclude.isNotEmpty()) parts += "exclude=${exclude.joinToString(",")}"
        return if (parts.isEmpty()) "(no filters)" else parts.joinToString("  ")
    }
}
