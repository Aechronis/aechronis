package phonon.nodes.utils

import org.bukkit.configuration.file.YamlConfiguration
import phonon.nodes.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerNameCache() {

    private val file: File = File(Config.pathPlugin, "playercache.yml")
    private val yaml: YamlConfiguration = YamlConfiguration.loadConfiguration(file)
    private val cache: MutableMap<UUID, String> = ConcurrentHashMap()

    fun load() {
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.createNewFile()
        }

        yaml.getKeys(false).forEach { key ->
            try {
                val uuid = UUID.fromString(key)
                val name = yaml.getString(key) ?: return@forEach
                cache[uuid] = name
            } catch (_: Exception) {
                // Ignore invalid UUIDs
            }
        }
    }

    fun save() {
        cache.forEach { (uuid, name) ->
            yaml.set(uuid.toString(), name)
        }
        try {
            yaml.save(file)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun set(uuid: UUID, name: String) {
        cache[uuid] = name
        yaml.set(uuid.toString(), name)
        save() // save immediately
    }

    fun get(uuid: UUID): String? = cache[uuid]
}
