package net.aechronis.logger.params

data class FeatureLookupParams(
    val source: String, // s:
    val users: List<String> = emptyList(), // u:
    val since: Long? = null, // t: cutoff timestamp
    val radius: Int? = null, // r:
    val chunkRadius: Int? = null, // cr: 1 = own chunk, n = (2n-1)x(2n-1) chunks
    val actions: List<String> = emptyList(), // a: free-form, empty = all actions
) {
    fun human(): String {
        val parts = mutableListOf("source=$source")
        if (users.isNotEmpty()) parts += "users=${users.joinToString(",")}"
        since?.let { parts += "since=${(System.currentTimeMillis() - it) / 1000}s ago" }
        radius?.let { parts += "radius=$it" }
        chunkRadius?.let { parts += "chunkRadius=$it" }
        if (actions.isNotEmpty()) parts += "actions=${actions.joinToString(",")}"
        return parts.joinToString("  ")
    }
}
