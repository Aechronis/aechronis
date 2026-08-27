package net.aechronis.votifier

import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.logging.Logger

class VotifierOptions(
    val dataDirectory: Path = Path.of("votifier"),
    val bindAddress: InetSocketAddress = InetSocketAddress("0.0.0.0", DEFAULT_PORT),
    val logger: Logger = Logger.getLogger("Votifier"),
    val debug: Boolean = false,
    val dispatchTimeoutMillis: Long = 10_000,
) {
    init {
        require(dispatchTimeoutMillis > 0) { "dispatchTimeoutMillis must be positive" }
    }

    companion object {
        const val DEFAULT_PORT = 8192
    }
}
