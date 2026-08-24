package net.aechronis.craftingstore

/** JSON-only settings. Runtime callbacks deliberately do not live here. */
data class CraftingStoreFileConfig(
    var apiKey: String = "",
    var debug: Boolean = false,
    var disableBuyCommand: Boolean = true,
    var timeBetweenCommandsMillis: Long = 200,
    var notEnoughBalanceMessage: String = "You do not have enough gems.",
    var useAlternativeApi: Boolean = false,
) {
    fun normalized(): CraftingStoreFileConfig {
        timeBetweenCommandsMillis = timeBetweenCommandsMillis.coerceIn(0, 60_000)
        return this
    }
}
