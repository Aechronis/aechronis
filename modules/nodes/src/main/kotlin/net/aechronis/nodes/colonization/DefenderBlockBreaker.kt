package net.aechronis.nodes.colonization

import net.aechronis.nodes.war.Attack
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.network.packet.server.play.BlockBreakAnimationPacket
import net.minestom.server.network.packet.server.play.WorldEventPacket
import net.minestom.server.worldevent.WorldEvent
import kotlin.math.floor

private const val CLEARED_BLOCK_BREAK_STAGE: Byte = -1

internal enum class DefenderBlockBreakKind {
    FLAG,
    TERRAIN,
}

internal enum class DefenderBlockBreakTickResult {
    IN_PROGRESS,
    COMPLETED,
    INVALID,
}

private data class DefenderBlockBreakAction(
    val kind: DefenderBlockBreakKind,
    val attack: Attack,
    val position: BlockVec,
    val expectedBlock: Block,
    val damagePerTick: Float,
    val progress: Float = 0F,
    val animationStage: Byte = CLEARED_BLOCK_BREAK_STAGE,
)

internal class DefenderBlockBreaker(
    private val entity: EntityCreature,
    private val weapon: DefenderWeapon,
    private val pickaxe: ItemStack,
) {
    private var action: DefenderBlockBreakAction? = null
    private var lastSwingAtMillis: Long = 0

    val kind: DefenderBlockBreakKind? get() = action?.kind
    val attack: Attack? get() = action?.attack
    val position: BlockVec? get() = action?.position

    fun begin(
        instance: Instance,
        kind: DefenderBlockBreakKind,
        attack: Attack,
        position: BlockVec,
    ) {
        stop(instance)
        val block = instance.getBlock(position)
        action = DefenderBlockBreakAction(
            kind = kind,
            attack = attack,
            position = position,
            expectedBlock = block,
            damagePerTick = aiBlockBreakDamagePerTick(block, pickaxe),
        )
        entity.navigator.reset()
        entity.setEquipment(EquipmentSlot.MAIN_HAND, pickaxe)
    }

    fun tick(
        instance: Instance,
        nowMillis: Long,
        restartWhenBlockChanges: Boolean,
        canContinue: (BlockVec, Block) -> Boolean,
    ): DefenderBlockBreakTickResult {
        var current = action ?: return DefenderBlockBreakTickResult.INVALID
        val block = instance.getBlock(current.position)
        if (block.isAir || !canContinue(current.position, block)) {
            stop(instance)
            return DefenderBlockBreakTickResult.INVALID
        }
        if (block != current.expectedBlock) {
            if (!restartWhenBlockChanges) {
                stop(instance)
                return DefenderBlockBreakTickResult.INVALID
            }
            clearAnimation(instance)
            current = current.copy(
                expectedBlock = block,
                damagePerTick = aiBlockBreakDamagePerTick(block, pickaxe),
                progress = 0F,
                animationStage = CLEARED_BLOCK_BREAK_STAGE,
            )
            action = current
        }
        if (current.damagePerTick == 0F) {
            if (current.kind == DefenderBlockBreakKind.FLAG) return DefenderBlockBreakTickResult.IN_PROGRESS
            stop(instance)
            return DefenderBlockBreakTickResult.INVALID
        }

        entity.lookAt(current.position.add(0.5, 0.5, 0.5))
        if (nowMillis - lastSwingAtMillis >= PICKAXE_SWING_MILLIS) {
            entity.swingMainHand()
            lastSwingAtMillis = nowMillis
        }
        val progress = current.progress + current.damagePerTick
        if (progress < 1F) {
            val updated = current.copy(progress = progress)
            action = updated
            setAnimation(instance, updated, flagBreakAnimationStage(progress))
            return DefenderBlockBreakTickResult.IN_PROGRESS
        }

        instance.sendGroupedPacket(
            WorldEventPacket(
                WorldEvent.PARTICLES_DESTROY_BLOCK.id(),
                current.position,
                block.stateId(),
                false,
            ),
        )
        stop(instance)
        return DefenderBlockBreakTickResult.COMPLETED
    }

    fun stop(instance: Instance) {
        clearAnimation(instance)
        action = null
        if (!entity.isRemoved) entity.setEquipment(EquipmentSlot.MAIN_HAND, weapon.gun.toItemStack())
    }

    private fun setAnimation(
        instance: Instance,
        current: DefenderBlockBreakAction,
        stage: Byte,
    ) {
        if (current.animationStage == stage || action !== current) return
        instance.sendGroupedPacket(BlockBreakAnimationPacket(entity.entityId, current.position, stage))
        action = current.copy(animationStage = stage)
    }

    private fun clearAnimation(instance: Instance) {
        val current = action ?: return
        if (current.animationStage == CLEARED_BLOCK_BREAK_STAGE) return
        instance.sendGroupedPacket(
            BlockBreakAnimationPacket(entity.entityId, current.position, CLEARED_BLOCK_BREAK_STAGE),
        )
        action = current.copy(animationStage = CLEARED_BLOCK_BREAK_STAGE)
    }

    private companion object {
        const val PICKAXE_SWING_MILLIS = 250L
    }
}

internal fun aiBlockBreakDamagePerTick(
    block: Block,
    item: ItemStack,
): Float {
    val registry = block.registry() ?: return 0F
    val hardness = registry.hardness()
    if (hardness == -1F) return 0F

    val tool = item.get(DataComponents.TOOL)
    val correctTool = !registry.requiresTool() || tool?.isCorrectForDrops(block) == true
    val miningSpeed = if (correctTool) tool?.getSpeed(block) ?: 1F else 1F
    if (miningSpeed == 0F) return 0F
    if (hardness == 0F) return 1F

    var damage = miningSpeed / hardness
    damage /= if (correctTool) 30F else 100F
    return damage.coerceAtMost(1F)
}

internal fun flagBreakAnimationStage(progress: Float): Byte {
    require(progress.isFinite() && progress >= 0F) { "Flag-break progress must be finite and non-negative" }
    return floor(progress * 10).toInt().coerceIn(0, 9).toByte()
}
