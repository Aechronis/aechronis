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
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerEditSignEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.instance.block.rule.BlockPlacementRule
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.OpenSignEditorPacket
import net.minestom.server.tag.Tag
import net.minestom.server.utils.Direction
import org.everbuild.blocksandstuff.common.item.DroppedItemFactory
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Vanilla-compatible placement, support, and text behaviour for every sign family. */
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

    private enum class SignKind {
        STANDING,
        WALL,
        CEILING_HANGING,
        WALL_HANGING,
    }

    private data class Family(
        val standing: Block?,
        val wall: Block?,
        val ceilingHanging: Block?,
        val wallHanging: Block?,
    )

    private val blocks: List<Block>
        get() = Block.staticRegistry().values().filter(::isSign)

    fun init() {
        val manager = MinecraftServer.getBlockManager()
        for (block in blocks) {
            manager.registerHandler(block.key()) { SignHandler(block.defaultState()) }
            manager.registerBlockPlacementRule(SignPlacementRule(block.defaultState()))
        }
        Vanilla.eventNode.addListener(PlayerBlockPlaceEvent::class.java, ::validatePlacement)
        Vanilla.eventNode.addListener(PlayerEditSignEvent::class.java, ::onEdit)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java) { sessions.remove(it.player) }
    }

    /**
     * Minestom consumes a block item after a placement rule returns null. Reject impossible sign
     * placements before that happens, just as vanilla's BlockItem#place does.
     */
    private fun validatePlacement(event: PlayerBlockPlaceEvent) {
        if (!isSign(event.block)) return
        val placed =
            resolve(
                instance = event.instance,
                type = event.block,
                position = event.blockPosition,
                clickedFace = event.blockFace,
                playerPosition = event.player.position,
                sneaking = event.player.isSneaking,
            )
        if (placed == null) event.isCancelled = true
    }

    private fun onEdit(event: PlayerEditSignEvent) {
        val session = sessions.remove(event.player) ?: return
        if (session.instance !== event.instance || session.position != event.blockPosition || session.front != event.isFrontText) return

        val current = event.instance.getBlock(event.blockPosition)
        if (!isSign(current) || current.nbtOrEmpty().getBoolean(WAXED, false)) return
        val nbt =
            current
                .nbtOrEmpty()
                .put(if (event.isFrontText) FRONT_TEXT else BACK_TEXT, text(event.lines))
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

    private fun isSign(block: Block): Boolean = kind(block) != null

    private fun kind(block: Block): SignKind? =
        when {
            block.key().asString().endsWith("_wall_hanging_sign") -> SignKind.WALL_HANGING
            block.key().asString().endsWith("_hanging_sign") -> SignKind.CEILING_HANGING
            block.key().asString().endsWith("_wall_sign") -> SignKind.WALL
            block.key().asString().endsWith("_sign") -> SignKind.STANDING
            else -> null
        }

    private fun family(block: Block): Family? {
        val key = block.key().asString()
        val root =
            when (kind(block)) {
                SignKind.WALL_HANGING -> key.removeSuffix("_wall_hanging_sign")
                SignKind.CEILING_HANGING -> key.removeSuffix("_hanging_sign")
                SignKind.WALL -> key.removeSuffix("_wall_sign")
                SignKind.STANDING -> key.removeSuffix("_sign")
                null -> return null
            }

        fun find(suffix: String): Block? = Block.fromKey(root + suffix)?.defaultState()
        return Family(
            standing = find("_sign"),
            wall = find("_wall_sign"),
            ceilingHanging = find("_hanging_sign"),
            wallHanging = find("_wall_hanging_sign"),
        )
    }

    private fun defaultNbt(): CompoundBinaryTag =
        CompoundBinaryTag
            .builder()
            .put(FRONT_TEXT, text(List(4) { "" }))
            .put(BACK_TEXT, text(List(4) { "" }))
            .putBoolean(WAXED, false)
            .build()

    private fun text(lines: List<String>): CompoundBinaryTag {
        val messages = ListBinaryTag.builder(BinaryTagTypes.STRING)
        lines.forEach { messages.add(StringBinaryTag.stringBinaryTag(it)) }
        val list = messages.build()
        return CompoundBinaryTag
            .builder()
            .put("messages", list)
            .put("filtered_messages", list)
            .putString("color", "black")
            .putBoolean("has_glowing_text", false)
            .build()
    }

    private class SignHandler(
        private val type: Block,
    ) : BlockHandler {
        override fun getKey(): Key = type.key()

        override fun getBlockEntityTags(): Collection<Tag<*>> = listOf(Tag.NBT(FRONT_TEXT), Tag.NBT(BACK_TEXT), Tag.Boolean(WAXED))

        override fun onPlace(placement: BlockHandler.Placement) {
            val playerPlacement = placement as? BlockHandler.PlayerPlacement ?: return
            val block = placement.instance.getBlock(placement.blockPosition)
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
            if (allowsHangingChain(interaction.block, interaction.blockFace, held.material())) return true

            val material = held.material().key().asString()
            val front = frontFor(interaction.player, interaction.blockPosition, interaction.block)
            val target = if (front) FRONT_TEXT else BACK_TEXT
            val nbt = if (interaction.block.nbt() == null) defaultNbt() else interaction.block.nbtOrEmpty()
            val changed =
                when {
                    material.endsWith("_dye") && !nbt.getBoolean(WAXED, false) -> {
                        val signText = nbt.getCompound(target).putString("color", material.removePrefix("minecraft:").removeSuffix("_dye"))
                        nbt.put(target, signText)
                    }

                    material == "minecraft:glow_ink_sac" && !nbt.getBoolean(WAXED, false) -> {
                        nbt.put(target, nbt.getCompound(target).putBoolean("has_glowing_text", true))
                    }

                    material == "minecraft:honeycomb" && !nbt.getBoolean(WAXED, false) -> nbt.putBoolean(WAXED, true)
                    material.endsWith("_axe") && nbt.getBoolean(WAXED, false) -> nbt.putBoolean(WAXED, false)
                    else -> null
                }
            if (changed != null) {
                interaction.instance.setBlock(interaction.blockPosition, interaction.block.withNbt(changed).withHandler(this), false)
                if (material != "minecraft:air" && !material.endsWith("_axe") && interaction.player.gameMode != GameMode.CREATIVE) {
                    interaction.player.setItemInHand(interaction.hand, held.withAmount(held.amount() - 1))
                }
                return false
            }
            if (!interaction.player.isSneaking) {
                openEditor(interaction.player, interaction.instance, interaction.blockPosition, interaction.block, front)
            }
            return false
        }
    }

    /** Vanilla lets a hanging-sign item bypass text interaction in these two chaining cases. */
    private fun allowsHangingChain(
        block: Block,
        clickedFace: BlockFace,
        material: Material,
    ): Boolean {
        if (!material.key().asString().endsWith("_hanging_sign")) return false
        return when (kind(block)) {
            SignKind.CEILING_HANGING -> clickedFace == BlockFace.BOTTOM
            SignKind.WALL_HANGING ->
                block
                    .getProperty("facing")
                    ?.let(::face)
                    ?.let(::axis)
                    ?.let { axis(clickedFace) != it } ?: false
            else -> false
        }
    }

    private fun frontFor(
        player: Player,
        position: Point,
        block: Block,
    ): Boolean {
        val facing = block.getProperty("facing")
        val (normalX, normalZ) =
            if (facing != null) {
                val vector = face(facing).toDirection().vec()
                vector.x() to vector.z()
            } else {
                val angle = Math.toRadians((block.getProperty("rotation")?.toIntOrNull() ?: 0) * 22.5)
                sin(angle) to cos(angle)
            }
        val offsetX = player.position.x - (position.x() + 0.5)
        val offsetZ = player.position.z - (position.z() + 0.5)
        return offsetX * normalX + offsetZ * normalZ > 0.0
    }

    private class SignPlacementRule(
        private val type: Block,
    ) : BlockPlacementRule(type) {
        override fun blockPlace(state: PlacementState): Block? =
            resolve(
                instance = state.instance,
                type = type,
                position = state.placePosition,
                clickedFace = state.blockFace ?: return null,
                playerPosition = state.playerPosition,
                sneaking = state.isPlayerShifting,
            )?.let { placed -> placed.withNbt(defaultNbt()).withHandler(handlerFor(placed)) }

        override fun blockUpdate(state: UpdateState): Block {
            if (canSurvive(state.instance, state.currentBlock, state.blockPosition)) return state.currentBlock
            DroppedItemFactory.maybeDrop(state)
            return Block.AIR
        }
    }

    private fun resolve(
        instance: Block.Getter,
        type: Block,
        position: Point,
        clickedFace: BlockFace,
        playerPosition: Pos?,
        sneaking: Boolean,
    ): Block? {
        val family = family(type) ?: return null
        val waterlogged = instance.getBlock(position).isLiquid.toString()
        val directions = nearestLookingDirections(playerPosition, clickedFace)
        return when (kind(type)) {
            SignKind.STANDING, SignKind.WALL -> {
                for (direction in directions) {
                    val candidate =
                        when (direction) {
                            Direction.DOWN -> family.standing?.withProperty("rotation", signRotation(playerPosition?.yaw() ?: 0f))
                            Direction.UP -> null
                            else -> family.wall?.withProperty("facing", direction.opposite().name.lowercase())
                        }
                    if (candidate != null && canSurvive(instance, candidate, position)) {
                        return candidate.withProperty("waterlogged", waterlogged)
                    }
                }
                null
            }

            SignKind.CEILING_HANGING, SignKind.WALL_HANGING -> {
                for (direction in directions) {
                    val candidate =
                        when (direction) {
                            Direction.UP -> ceilingHangingState(instance, family.ceilingHanging, position, playerPosition, sneaking)
                            Direction.DOWN -> null
                            else -> {
                                if (axis(direction) == axis(clickedFace)) {
                                    null
                                } else {
                                    family.wallHanging?.withProperty("facing", direction.opposite().name.lowercase())
                                }
                            }
                        }
                    if (candidate != null && canSurvive(instance, candidate, position)) {
                        return candidate.withProperty("waterlogged", waterlogged)
                    }
                }
                null
            }

            null -> null
        }
    }

    /** Equivalent to vanilla Direction.orderedByNearest, with BlockPlaceContext's clicked-face promotion. */
    private fun nearestLookingDirections(
        playerPosition: Pos?,
        clickedFace: BlockFace,
    ): List<Direction> {
        if (playerPosition == null) return listOf(clickedFace.oppositeFace.toDirection()) + Direction.entries
        val pitch = Math.toRadians(playerPosition.pitch().toDouble())
        val yaw = Math.toRadians(-playerPosition.yaw().toDouble())
        val pitchSin = sin(pitch).toFloat()
        val pitchCos = cos(pitch).toFloat()
        val yawSin = sin(yaw).toFloat()
        val yawCos = cos(yaw).toFloat()
        val xPositive = yawSin > 0f
        val yPositive = pitchSin < 0f
        val zPositive = yawCos > 0f
        val xYaw = if (xPositive) yawSin else -yawSin
        val yMagnitude = if (yPositive) -pitchSin else pitchSin
        val zYaw = if (zPositive) yawCos else -yawCos
        val xMagnitude = xYaw * pitchCos
        val zMagnitude = zYaw * pitchCos
        val x = if (xPositive) Direction.EAST else Direction.WEST
        val y = if (yPositive) Direction.UP else Direction.DOWN
        val z = if (zPositive) Direction.SOUTH else Direction.NORTH
        val ordered =
            if (xYaw > zYaw) {
                if (yMagnitude >
                    xMagnitude
                ) {
                    directions(y, x, z)
                } else if (zMagnitude > yMagnitude) {
                    directions(x, z, y)
                } else {
                    directions(x, y, z)
                }
            } else if (yMagnitude > zMagnitude) {
                directions(y, z, x)
            } else if (xMagnitude > yMagnitude) {
                directions(z, x, y)
            } else {
                directions(z, y, x)
            }
        val promoted = clickedFace.oppositeFace.toDirection()
        return listOf(promoted) + ordered.filter { it != promoted }
    }

    private fun directions(
        first: Direction,
        second: Direction,
        third: Direction,
    ): List<Direction> = listOf(first, second, third, third.opposite(), second.opposite(), first.opposite())

    private fun ceilingHangingState(
        instance: Block.Getter,
        type: Block?,
        position: Point,
        playerPosition: Pos?,
        sneaking: Boolean,
    ): Block? {
        type ?: return null
        val above = instance.getBlock(position.relative(BlockFace.TOP))
        val playerDirection = horizontalDirection(playerPosition?.yaw() ?: 0f)
        var attached = !isFaceFull(above, BlockFace.BOTTOM) || sneaking
        if (kind(above) == SignKind.CEILING_HANGING || kind(above) == SignKind.WALL_HANGING) {
            if (!sneaking) {
                val aboveFacing = above.getProperty("facing")?.let(::face)
                val aboveRotation = above.getProperty("rotation")?.toIntOrNull()?.let(::cardinalRotation)
                if ((aboveFacing != null && axis(aboveFacing) == axis(playerDirection)) ||
                    (aboveRotation != null && axis(aboveRotation) == axis(playerDirection))
                ) {
                    attached = false
                }
            }
        }
        val rotation = if (attached) signRotation(playerPosition?.yaw() ?: 0f) else cardinalRotation(playerDirection.opposite())
        return type.withProperty("attached", attached.toString()).withProperty("rotation", rotation.toString())
    }

    private fun canSurvive(
        instance: Block.Getter,
        block: Block,
        position: Point,
    ): Boolean =
        when (kind(block)) {
            SignKind.STANDING -> instance.getBlock(position.relative(BlockFace.BOTTOM)).isSolid
            SignKind.WALL -> {
                val facing = block.getProperty("facing")?.let(::face) ?: return false
                instance.getBlock(position.relative(facing.toDirection().opposite().toBlockFace())).isSolid
            }

            SignKind.CEILING_HANGING -> isCenterSupported(instance.getBlock(position.relative(BlockFace.TOP)), BlockFace.BOTTOM)
            SignKind.WALL_HANGING -> canPlaceWallHanging(instance, block, position)
            null -> false
        }

    /** Vanilla WallHangingSignBlock.canPlace: either end can attach to a full face or compatible sign. */
    private fun canPlaceWallHanging(
        instance: Block.Getter,
        block: Block,
        position: Point,
    ): Boolean {
        val facing = block.getProperty("facing")?.let(::face)?.toDirection() ?: return false
        val clockwise = clockwise(facing)
        val counterClockwise = counterClockwise(facing)
        return canAttachWallHanging(instance, block, position.relative(clockwise.toBlockFace()), counterClockwise) ||
            canAttachWallHanging(instance, block, position.relative(counterClockwise.toBlockFace()), clockwise)
    }

    private fun canAttachWallHanging(
        instance: Block.Getter,
        block: Block,
        supportPosition: Point,
        supportFace: Direction,
    ): Boolean {
        val support = instance.getBlock(supportPosition)
        if (kind(support) == SignKind.WALL_HANGING) {
            val supportFacing = support.getProperty("facing")?.let(::face) ?: return false
            val facing = block.getProperty("facing")?.let(::face) ?: return false
            return axis(supportFacing) == axis(facing)
        }
        return isFaceFull(support, supportFace.toBlockFace())
    }

    /** SupportType.CENTER for a DOWN-facing support: the central 10x10 area must be covered. */
    private fun isCenterSupported(
        block: Block,
        face: BlockFace,
    ): Boolean {
        // Hanging-sign support shapes include the board and chains even though their collision
        // shapes do not; Minestom exposes only the latter, so retain vanilla chaining explicitly.
        if (kind(block) == SignKind.CEILING_HANGING || kind(block) == SignKind.WALL_HANGING) return true
        if (face != BlockFace.BOTTOM) return isFaceFull(block, face)
        val boxes =
            block.registry()?.collisionShape()?.let { shape ->
                if (shape is net.minestom.server.collision.ShapeImpl) shape.boundingBoxes() else emptyList()
            } ?: emptyList()
        return boxes.any { box ->
            box.minY() == 0.0 && box.minX() <= 0.1875 && box.maxX() >= 0.8125 && box.minZ() <= 0.1875 && box.maxZ() >= 0.8125
        }
    }

    private fun isFaceFull(
        block: Block,
        face: BlockFace,
    ): Boolean = block.registry()?.collisionShape()?.isFaceFull(face) == true

    private fun horizontalDirection(yaw: Float): Direction =
        when (((floor(yaw / 90f + 0.5f).toInt() % 4) + 4) % 4) {
            0 -> Direction.SOUTH
            1 -> Direction.WEST
            2 -> Direction.NORTH
            else -> Direction.EAST
        }

    private fun cardinalRotation(direction: Direction): Int =
        when (direction) {
            Direction.NORTH -> 0
            Direction.EAST -> 4
            Direction.SOUTH -> 8
            Direction.WEST -> 12
            else -> error("Expected a horizontal direction")
        }

    private fun cardinalRotation(rotation: Int): Direction? =
        when (rotation) {
            0 -> Direction.NORTH
            4 -> Direction.EAST
            8 -> Direction.SOUTH
            12 -> Direction.WEST
            else -> null
        }

    private fun signRotation(yaw: Float): String = ((floor(yaw / 22.5f + 0.5f).toInt() + 8) and 15).toString()

    private fun face(value: String): BlockFace = BlockFace.valueOf(value.uppercase())

    private fun Direction.toBlockFace(): BlockFace = BlockFace.fromDirection(this)

    private fun axis(face: BlockFace): Char = axis(face.toDirection())

    private fun axis(direction: Direction): Char =
        when (direction) {
            Direction.EAST, Direction.WEST -> 'x'
            Direction.UP, Direction.DOWN -> 'y'
            Direction.NORTH, Direction.SOUTH -> 'z'
        }

    private fun clockwise(direction: Direction): Direction =
        when (direction) {
            Direction.NORTH -> Direction.EAST
            Direction.EAST -> Direction.SOUTH
            Direction.SOUTH -> Direction.WEST
            Direction.WEST -> Direction.NORTH
            else -> error("Expected a horizontal direction")
        }

    private fun counterClockwise(direction: Direction): Direction = clockwise(direction.opposite())
}
