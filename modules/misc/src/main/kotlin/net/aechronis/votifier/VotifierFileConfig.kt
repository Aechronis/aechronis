package net.aechronis.votifier

data class VotifierFileConfig(
    var protocolV1Enabled: Boolean = true,
    var tokens: Map<String, String> = emptyMap(),
) {
    fun normalized(): VotifierFileConfig {
        tokens =
            tokens
                .map { (service, token) -> service.trim() to token.trim() }
                .filter { (service, token) -> service.isNotEmpty() && token.isNotEmpty() }
                .toMap()
        return this
    }
}
