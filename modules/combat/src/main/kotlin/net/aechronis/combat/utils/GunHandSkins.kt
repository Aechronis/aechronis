package net.aechronis.combat.utils

import net.aechronis.server.modules.ModuleContext
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerSkin
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** A texture-only pack for the local player's animated hands. All image and HTTP work runs off tick. */
object GunHandSkins {
    const val HAND_TEXTURE = "assets/aechronis/textures/item/hands.png"

    @Volatile private var readTemplate: (() -> ByteArray)? = null
    private const val RETRY_MILLIS = 30_000L
    private val packMetadata =
        """{"pack":{"description":"           §6§lAechronis\n§7   Gun hands resource pack","min_format":[75,0],"max_format":[88,0]}}"""
            .toByteArray()

    private class SkinRequest(
        val skin: PlayerSkin?,
        val sleeves: Int,
    ) {
        @Volatile var retryAfter = Long.MAX_VALUE
    }

    private val requests = ConcurrentHashMap<Player, SkinRequest>()

    @Volatile private var context: ModuleContext? = null
    private var registration: AutoCloseable? = null

    /** The active iteration registers its own atlas during configure, before combat starts. */
    fun registerTemplate(readTemplate: () -> ByteArray) {
        check(context == null) { "Register the hand atlas before combat initialization" }
        check(this.readTemplate == null) { "A gun hand atlas is already registered" }
        this.readTemplate = readTemplate
    }

    fun initialize(context: ModuleContext) {
        check(this.context == null) { "Gun hand skins are already initialized" }
        this.context = context
        registration = context.registerPlayerResourcePack("gun-hands", ::pack)
        // Minestom initializes Player.skin after configuration, before the first spawn.
        context.addListener(PlayerSpawnEvent::class.java) { update(it.player) }
        context.addListener(PlayerDisconnectEvent::class.java) { requests.remove(it.player) }
    }

    /** Cheap identity/state check; only a changed skin or a failed download schedules work. */
    fun update(player: Player) {
        val context = context ?: return
        if (player.instance == null) return
        val skin = player.skin
        val sleeves = player.settings.displayedSkinParts().toInt() and 12
        var changed = false
        requests.compute(player) { _, previous ->
            if (previous == null ||
                previous.skin != skin ||
                previous.sleeves != sleeves ||
                System.currentTimeMillis() >= previous.retryAfter
            ) {
                changed = true
                SkinRequest(skin, sleeves)
            } else {
                previous
            }
        }
        if (changed) context.refreshPlayerResourcePacks(player)
    }

    private fun pack(player: Player): Map<String, ByteArray>? {
        if (context == null) return null
        val readTemplate = readTemplate ?: return null
        val request = requests[player] ?: return null
        var prepared = false
        try {
            val resolved = GunSkinTexture.resolve(player.uuid, request.skin) ?: return null
            val assets =
                mapOf(
                    "pack.mcmeta" to packMetadata,
                    HAND_TEXTURE to personalize(readTemplate(), resolved.image, resolved.slim, request.sleeves),
                )
            if (requests[player] !== request) return null
            prepared = true
            return assets
        } finally {
            if (!prepared && requests[player] === request) {
                request.retryAfter = System.currentTimeMillis() + RETRY_MILLIS
            }
        }
    }

    internal fun personalize(
        template: ByteArray,
        skin: java.awt.image.BufferedImage,
        slim: Boolean,
        sleeves: Int = 12,
    ): ByteArray {
        val atlas = checkNotNull(ImageIO.read(ByteArrayInputStream(template))) { "Missing gun animation atlas" }
        require(
            atlas.width == 1024 && atlas.height >= 512 && atlas.height.countOneBits() == 1,
        ) { "Unexpected gun animation atlas dimensions" }
        require(atlas.getRGB(256, 0) == 0xff534b49.toInt()) { "Gun animation atlas has no skin layout marker" }
        require(skin.width == 64 && skin.height == 64) { "Gun hand skin must be normalized to 64x64" }
        val pixels = skin.getRGB(0, 0, 64, 64, null, 0, 64)
        // Skin Customization settings also apply to the replacement first-person sleeves.
        for ((bit, origin) in listOf(8 to (40 to 32), 4 to (48 to 48))) {
            if (sleeves and bit != 0) continue
            for (y in origin.second until origin.second + 16) {
                for (x in origin.first until origin.first + 16) pixels[y * 64 + x] = 0
            }
        }
        atlas.setRGB(256, 16, 64, 64, pixels, 0, 64)
        atlas.setRGB(257, 0, if (slim) 0xff010000.toInt() else 0xff000000.toInt())
        atlas.setRGB(258, 0, 0xff010000.toInt())
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(atlas, "png", output)) { "PNG writer is unavailable" }
            output.toByteArray()
        }
    }

    fun shutdown() {
        context = null
        readTemplate = null
        registration?.close()
        registration = null
        requests.clear()
    }
}
