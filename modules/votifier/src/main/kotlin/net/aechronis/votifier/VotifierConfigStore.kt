package net.aechronis.votifier

import com.vexsoftware.votifier.net.protocol.v1crypto.RSAIO
import com.vexsoftware.votifier.net.protocol.v1crypto.RSAKeygen
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyPair

internal class VotifierConfigStore(
    private val directory: Path,
) {
    private val file = directory.resolve("config.json")
    private val lock = Any()

    @Volatile private var config = VotifierFileConfig()

    fun current(): VotifierFileConfig =
        synchronized(lock) {
            config.copy().normalized()
        }

    fun reload() =
        synchronized(lock) {
            Files.createDirectories(directory)
            if (!Files.exists(file)) {
                config = VotifierFileConfig()
                saveLocked()
                return@synchronized
            }
            config = Json.decodeFromString<VotifierFileConfig>(Files.readString(file)).normalized()
        }

    fun loadOrCreateProtocolV1Key(): KeyPair =
        synchronized(lock) {
            Files.createDirectories(directory)
            val publicKey = directory.resolve("public.key")
            val privateKey = directory.resolve("private.key")
            check(Files.exists(publicKey) == Files.exists(privateKey)) {
                "Votifier V1 keys are incomplete; both public.key and private.key are required"
            }
            if (Files.exists(publicKey)) {
                return@synchronized RSAIO.load(directory.toFile())
            }

            RSAKeygen.generate(2048).also { RSAIO.save(directory.toFile(), it) }
        }

    private fun saveLocked() {
        val temporary = file.resolveSibling("config.json.tmp")
        Files.writeString(temporary, Json.encodeToString(config))
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
