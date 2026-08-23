package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerEditSignEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.instance.block.rule.BlockPlacementRule
import net.minestom.server.network.packet.server.play.OpenSignEditorPacket
import net.minestom.server.tag.Tag
import org.everbuild.blocksandstuff.common.item.DroppedItemFactory
import kotlin.math.atan2

object Signs {
    private const val FRONT_TEXT = "front_text"
    private const val BACK_TEXT = "back_text"
    private const val WAXED = "is_waxed"
    private val sessions = mutableMapOf<Player, EditSession>()

    private data class EditSession(
        val instance: Instance,
        val position: BlockVec,
        val front: Boolean,
    )

    private val blocks: List<Block>
        get() =
            Block.staticRegistry().values().filter { block ->
                val key = block.key().asString()
                key.endsWith("_sign") || key.endsWith("_hanging_sign")
            }

    fun init() {
        val manager = MinecraftServer.getBlockManager()
        for (block in blocks) {
            manager.registerHandler(block.key()) { SignHandler(block.defaultState()) }
            manager.registerBlockPlacementRule(SignPlacementRule(block.defaultState()))
        }
        Vanilla.eventNode.addListener(PlayerEditSignEvent::class.java, ::onEdit)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java) { sessions.remove(it.player) }
    }

    private fun onEdit(event: PlayerEditSignEvent) {
        val session = sessions.remove(event.player) ?: return
        if (session.instance !== event.instance || session.position != event.blockPosition || session.front != event.isFrontText) return

        val current = event.instance.getBlock(event.blockPosition)
        if (!isSign(current) || current.nbtOrEmpty().getBoolean(WAXED, false)) return
        val text = text(event.lines.map(::componentJson))
        val nbt =
            current
                .nbtOrEmpty()
                .put(if (event.isFrontText) FRONT_TEXT else BACK_TEXT, text)
                .putBoolean(WAXED, false)
        event.instance.setBlock(event.blockPosition, current.withNbt(nbt).withHandler(handlerFor(current)))
    }

    private fun openEditor(
        player: Player,
        instance: Instance,
        position: Point,
        block: Block,
        front: Boolean,
    ) {
        if (block.nbtOrEmpty().getBoolean(WAXED, false)) return
        sessions[player] = EditSession(instance, BlockVec(position), front)
        player.sendPacket(OpenSignEditorPacket(position, front))
    }

    private fun handlerFor(block: Block): BlockHandler =
        MinecraftServer.getBlockManager().getHandler(block.key().asString()) ?: SignHandler(block.defaultState())

    private fun isSign(block: Block): Boolean = block.key().asString().let { it.endsWith("_sign") || it.endsWith("_hanging_sign") }

    private fun defaultNbt(): CompoundBinaryTag =
        CompoundBinaryTag
            .builder()
            .put(FRONT_TEXT, text(List(4) { "{\"text\":\"\"}" }))
            .put(BACK_TEXT, text(List(4) { "{\"text\":\"\"}" }))
            .putBoolean(WAXED, false)
            .build()

    private fun text(lines: List<String>): CompoundBinaryTag {
        val messages = ListBinaryTag.builder(BinaryTagTypes.STRING)
        lines.forEach { messages.add(StringBinaryTag.stringBinaryTag(it)) }
        val list = messages.build()
        return CompoundBinaryTag
            .builder()
            .put(
                "messages",
                list,
            ).put("filtered_messages", list)
            .putString("color", "black")
            .putBoolean("has_glowing_text", false)
            .build()
    }

    private fun componentJson(line: String): String =
        if (line.startsWith("{") || line.startsWith("[")) line else "{\"text\":\"${line.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"

    private class SignHandler(
        private val type: Block,
    ) : BlockHandler {
        override fun getKey(): Key = type.key()

        override fun getBlockEntityTags(): Collection<Tag<*>> = listOf(Tag.NBT(FRONT_TEXT), Tag.NBT(BACK_TEXT), Tag.Boolean(WAXED))

        override fun onPlace(placement: BlockHandler.Placement) {
            val playerPlacement = placement as? BlockHandler.PlayerPlacement ?: return
            val block = placement.block
            if (block.nbt() == null) {
                placement.instance.setBlock(placement.blockPosition, block.withNbt(defaultNbt()).withHandler(this), false)
            }
            if (!playerPlacement.player.isSneaking) {
                openEditor(
                    playerPlacement.player,
                    placement.instance,
                    placement.blockPosition,
                    block,
                    frontFor(playerPlacement.player, placement.blockPosition, block),
                )
            }
        }

        override fun onInteract(interaction: BlockHandler.Interaction): Boolean {
            val held = interaction.player.getItemInHand(interaction.hand)
            val material = held.material().key().asString()
            val front = frontFor(interaction.player, interaction.blockPosition, interaction.block)
            val target = if (front) FRONT_TEXT else BACK_TEXT
            val nbt = if (interaction.block.nbt() == null) defaultNbt() else interaction.block.nbtOrEmpty()
            val changed =
                when {
                    material.endsWith("_dye") && !nbt.getBoolean(WAXED, false) -> {
                        val text =
                            (
                                nbt.getCompound(target) ?: text(
                                    List(4) {
                                        "{\"text\":\"\"}"
                                    },
                                )
                            ).putString("color", material.removePrefix("minecraft:").removeSuffix("_dye"))
                        nbt.put(target, text)
                    }

                    material == "minecraft:glow_ink_sac" && !nbt.getBoolean(WAXED, false) -> {
                        val text = (nbt.getCompound(target) ?: text(List(4) { "{\"text\":\"\"}" })).putBoolean("has_glowing_text", true)
                        nbt.put(target, text)
                    }

                    material == "minecraft:honeycomb" && !nbt.getBoolean(WAXED, false) -> {
                        nbt.putBoolean(WAXED, true)
                    }

                    material.endsWith("_axe") && nbt.getBoolean(WAXED, false) -> {
                        nbt.putBoolean(WAXED, false)
                    }

                    else -> {
                        null
                    }
                }
            if (changed != null) {
                interaction.instance.setBlock(interaction.blockPosition, interaction.block.withNbt(changed).withHandler(this), false)
                if (material != "minecraft:air" &&
                    !material.endsWith("_axe") &&
                    interaction.player.gameMode != net.minestom.server.entity.GameMode.CREATIVE
                ) {
                    interaction.player.setItemInHand(interaction.hand, held.withAmount(held.amount() - 1))
                }
                return false
            }
            if (!interaction.player.isSneaking) {
                openEditor(
                    interaction.player,
                    interaction.instance,
                    interaction.blockPosition,
                    interaction.block,
                    front,
                )
            }
            return false
        }
    }

    private fun frontFor(
        player: Player,
        position: Point,
        block: Block,
    ): Boolean {
        val facing = block.getProperty("facing")
        if (facing != null) {
            val face = BlockFace.valueOf(facing.uppercase())
            return player.position.direction().dot(face.toDirection().vec()) < 0
        }
        val rotation = block.getProperty("rotation")?.toIntOrNull() ?: 0
        val angle = Math.toDegrees(atan2(player.position.x - position.x(), position.z() - player.position.z))
        return ((angle - rotation * 22.5 + 360.0) % 360.0) <= 180.0
    }

    private class SignPlacementRule(
        private val type: Block,
    ) : BlockPlacementRule(type) {
        override fun blockPlace(state: PlacementState): Block? {
            val face = state.blockFace ?: return null
            val current = state.instance.getBlock(state.placePosition)
            val key = type.key().asString()
            val hanging = key.endsWith("_hanging_sign")
            val placed =
                if (hanging) {
                    placeHanging(state, face, current)
                } else {
                    placeOrdinary(state, face, current)
                }
            return placed?.withNbt(defaultNbt())?.withHandler(handlerFor(placed))
        }

        override fun blockUpdate(state: UpdateState): Block {
            val block = state.currentBlock
            val key = block.key().asString()
            val supported =
                when {
                    key.endsWith(
                        "_wall_sign",
                    ) -> {
                        state.instance
                            .getBlock(
                                state.blockPosition.relative(BlockFace.valueOf(block.getProperty("facing")!!.uppercase()).oppositeFace),
                            ).isSolid
                    }

                    key.endsWith("_wall_hanging_sign") -> {
                        true
                    }

                    key.endsWith("_hanging_sign") -> {
                        !state.instance.getBlock(state.blockPosition.relative(BlockFace.TOP)).isAir
                    }

                    else -> {
                        state.instance.getBlock(state.blockPosition.relative(BlockFace.BOTTOM)).isSolid
                    }
                }
            if (supported) return block
            DroppedItemFactory.maybeDrop(state)
            return Block.AIR
        }

        private fun placeOrdinary(
            state: PlacementState,
            face: BlockFace,
            current: Block,
        ): Block? =
            when {
                face == BlockFace.TOP -> {
                    type
                        .withProperty(
                            "rotation",
                            (((state.playerPosition()?.yaw() ?: 0f) / 22.5f).toInt() + 8 and 15).toString(),
                        ).withProperty("waterlogged", current.isLiquid.toString())
                }

                face.toDirection().horizontal() -> {
                    val wall = Block.fromKey(type.key().asString().removeSuffix("_sign") + "_wall_sign") ?: return null
                    wall.withProperty("facing", face.name.lowercase()).withProperty("waterlogged", current.isLiquid.toString())
                }

                else -> {
                    null
                }
            }

        private fun placeHanging(
            state: PlacementState,
            face: BlockFace,
            current: Block,
        ): Block? =
            when {
                face == BlockFace.BOTTOM -> {
                    val above = state.instance.getBlock(state.placePosition.relative(BlockFace.TOP))
                    type
                        .withProperty("rotation", (((state.playerPosition()?.yaw() ?: 0f) / 22.5f).toInt() + 8 and 15).toString())
                        .withProperty("attached", (above.isSolid && !state.isPlayerShifting).toString())
                        .withProperty("waterlogged", current.isLiquid.toString())
                }

                face.toDirection().horizontal() -> {
                    val wall = Block.fromKey(type.key().asString().removeSuffix("_hanging_sign") + "_wall_hanging_sign") ?: return null
                    wall.withProperty("facing", face.name.lowercase()).withProperty("waterlogged", current.isLiquid.toString())
                }

                else -> {
                    null
                }
            }
    }
}
