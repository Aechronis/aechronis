package net.aechronis.combat.utils

import net.aechronis.server.modules.ModuleContext
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerSkin
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** A texture-only pack for the local player's animated hands. All image and HTTP work runs off tick. */
object GunHandSkins {
    const val HAND_TEXTURE = "assets/aechronis/textures/item/hands.png"

    @Volatile private var template: ByteArray? = null
    private const val RETRY_MILLIS = 30_000L
    private val packMetadata =
        """{"pack":{"description":"§6§lAechronis\n§7Gun hands","min_format":[75,0],"max_format":[97,1]}}"""
            .toByteArray()

    private class SkinRequest(
        val skin: PlayerSkin?,
        val sleeves: Int,
    ) {
        @Volatile var retryAfter = Long.MAX_VALUE
    }

    private val requests = ConcurrentHashMap<Player, SkinRequest>()

    // Player.skin is only set after configuration and Player hides its login profile, so the
    // textures are captured at pre-login for the configuration-phase pack build.
    // ponytail: a login rejected after pre-login leaves one small entry until that player rejoins.
    private val loginSkins = ConcurrentHashMap<UUID, PlayerSkin>()

    @Volatile private var context: ModuleContext? = null
    private var registration: AutoCloseable? = null

    /** The active iteration registers its own atlas during configure, before combat starts. */
    fun registerTemplate(readTemplate: () -> ByteArray) {
        check(template == null) { "A gun hand atlas is already registered" }
        // Retain bytes, never a callback into an iteration's retiring classloader.
        replaceTemplate(readTemplate())
    }

    fun replaceTemplate(bytes: ByteArray) {
        if (template?.contentEquals(bytes) == true) return
        template = bytes.copyOf()
        requests.values.forEach { it.retryAfter = 0L }
    }

    fun initialize(context: ModuleContext) {
        check(this.context == null) { "Gun hand skins are already initialized" }
        this.context = context
        registration = context.registerPlayerResourcePack("gun-hands", ::pack)
        // The configuration-phase request seeds the skin from the login profile; this
        // spawn check only refreshes when Player.skin or the sleeves really differ.
        context.addListener(PlayerSpawnEvent::class.java) { update(it.player) }
        context.addListener(AsyncPlayerPreLoginEvent::class.java) { event ->
            val textures = event.gameProfile.properties.firstOrNull { it.name == "textures" }
            if (textures == null) {
                loginSkins.remove(event.playerUuid)
            } else {
                loginSkins[event.playerUuid] = PlayerSkin(textures.value, textures.signature)
            }
        }
        context.addListener(PlayerDisconnectEvent::class.java) {
            requests.remove(it.player)
            loginSkins.remove(it.player.uuid)
        }
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
        val template = template ?: return null
        // Building during configuration ships the hands with the base stack. Pushing them
        // after spawn would force a second full client reload while chunks stream in.
        val request =
            requests.computeIfAbsent(player) {
                SkinRequest(
                    it.skin ?: loginSkins.remove(it.uuid),
                    it.settings.displayedSkinParts().toInt() and 12,
                )
            }
        // A disconnect may have cleared the map just before this worker re-seeded it.
        if (!player.isOnline) {
            requests.remove(player, request)
            return null
        }
        var prepared = false
        try {
            val resolved = GunSkinTexture.resolve(player.uuid, request.skin) ?: return null
            val assets =
                mapOf(
                    "pack.mcmeta" to packMetadata,
                    HAND_TEXTURE to personalize(template, resolved.image, resolved.slim, request.sleeves),
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
        template = null
        registration?.close()
        registration = null
        requests.clear()
        loginSkins.clear()
    }
}
