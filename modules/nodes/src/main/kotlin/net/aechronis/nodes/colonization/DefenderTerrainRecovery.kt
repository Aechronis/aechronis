package net.aechronis.nodes.colonization

import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.war.Attack
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import kotlin.math.hypot

private const val BASE_PLACED_TERRAIN_BLOCKS_PER_ATTACK = 24

// a staircase level takes a support and a step, with some left over to repair what attackers knock out
private const val PLACED_TERRAIN_BLOCKS_PER_CLIMB_LEVEL = 3
private const val MAX_STAIRCASE_START_DISTANCE = 8.0
private const val STAIRCASE_PLACEMENT_MILLIS = 250L

// a defender is 0.6 wide, so this keeps it out of the neighbouring column it is about to build in
private const val BUILD_POSITION_TOLERANCE = 0.15
private const val BUILD_CENTERING_SPEED = 0.1
private val BUILDING_BLOCK: ItemStack = ItemStack.of(Material.COBBLESTONE)

/** Breaks or places terrain when a defender cannot reach an active flag by walking. */
internal object DefenderTerrainRecovery {
    fun stopTerrainRecovery(
        session: DefenseSession,
        defender: AiDefender,
    ) {
        if (defender.blockBreaker.kind == DefenderBlockBreakKind.TERRAIN) {
            defender.blockBreaker.stop(session.instance)
        }
        defender.navigation.clearTerrainRecovery()
    }

    fun recoverFlagRouteTerrain(
        session: DefenseSession,
        defender: AiDefender,
        goal: NavigationGoal,
        now: Long,
    ): Boolean {
        val attack = goal.attack ?: return false
        if (!isAttackActive(attack)) {
            if (defender.blockBreaker.kind == DefenderBlockBreakKind.TERRAIN) {
                defender.blockBreaker.stop(session.instance)
            }
            defender.navigation.clearTerrainRecovery()
            return false
        }
        if (!defender.entity.isOnGround) {
            if (defender.navigation.airborneRecoveryDue(now)) {
                defender.entity.velocity = defender.entity.velocity.withY(0.0)
                defender.navigation.restartForTerrainRecovery(now)
            }
            return true
        }

        if (defender.blockBreaker.kind == DefenderBlockBreakKind.TERRAIN) {
            return tickTerrainBreaking(session, defender, attack, now)
        }
        val protectedBlocks = sessionProtectedBlocks(session)
        val canEdit: (BlockVec) -> Boolean = { position ->
            isTerrainEditable(session, position, protectedBlocks)
        }
        if (startStaircase(session, defender, attack, canEdit)) return true
        val edit = aiTerrainEdit(
            defender.entity.position,
            goal.position,
            blockAt = session.instance::getBlock,
            canEdit = canEdit,
        )?.takeIf { edit ->
            edit.kind != AiTerrainEditKind.PLACE ||
                (canPlaceTerrain(session, defender, attack) && canPlaceLikeVanilla(session.instance, defender.entity, edit.position))
        }
        if (edit == null) {
            discardUnreachableStaircase(session, defender, attack)
            defender.navigation.advanceApproach()
            defender.navigation.clearTerrainRecovery()
            defender.navigation.cancel()
            return false
        }

        return when (edit.kind) {
            AiTerrainEditKind.BREAK -> {
                beginTerrainBreaking(session, defender, attack, edit.position)
                true
            }
            AiTerrainEditKind.PLACE -> {
                placeTerrainBlock(session, defender, attack, edit.position)
                defender.navigation.clearTerrainRecovery()
                defender.navigation.navigateOntoPlacedTerrain(goal, edit.position, now)
                true
            }
        }
    }

    /** Lets a flag runner on the highest finished step build the next one, returning true while it is busy doing so. */
    fun continueStaircase(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
        now: Long,
    ): Boolean {
        val staircase = session.staircases[attack] ?: return false
        val building = defender.flagRunner &&
            defender.blockBreaker.kind != DefenderBlockBreakKind.TERRAIN &&
            defender.entity.isOnGround &&
            staircase.isLoaded(session.instance) &&
            buildNextStep(session, defender, attack, staircase, now)
        if (!building && defender.entity.getEquipment(EquipmentSlot.MAIN_HAND).material() == BUILDING_BLOCK.material()) {
            defender.blockBreaker.stop(session.instance)
        }
        return building
    }

    private fun buildNextStep(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
        staircase: DefenderStaircase,
        now: Long,
    ): Boolean {
        val instance = session.instance
        val entity = defender.entity
        val lastIntact = staircase.lastIntactStep(instance)
        if (lastIntact == staircase.topStep || staircase.stepAt(entity.position) != lastIntact) return false

        val next = lastIntact + 1
        val obstruction = staircase.clearanceFor(next).firstOrNull { position -> instance.getBlock(position).blocksMotion() }
        if (obstruction != null) {
            val canEdit: (BlockVec) -> Boolean = { position -> isTerrainEditable(session, position) }
            if (!canBreakTerrain(instance.getBlock(obstruction), obstruction, canEdit)) return false
            beginTerrainBreaking(session, defender, attack, obstruction)
            return true
        }

        if (defender.navigation.hasGoal) defender.navigation.cancel()
        val standing = staircase.standingPosition(lastIntact)
        if (hypot(entity.position.x - standing.x, entity.position.z - standing.z) > BUILD_POSITION_TOLERANCE) {
            defender.motor.moveTowards(standing, BUILD_CENTERING_SPEED, standing)
            return true
        }

        entity.setEquipment(EquipmentSlot.MAIN_HAND, BUILDING_BLOCK)
        if (now < staircase.nextPlacementAtMillis) return true
        val tread = staircase.tread(next)
        val placement = if (hasSolidFace(instance, tread)) tread else staircase.support(next)
        if (!isTerrainEditable(session, placement) || !canPlaceTerrain(session, defender, attack)) return false
        if (!canPlaceLikeVanilla(instance, entity, placement)) return true

        placeTerrainBlock(session, defender, attack, placement)
        staircase.nextPlacementAtMillis = now + STAIRCASE_PLACEMENT_MILLIS
        return true
    }

    private fun startStaircase(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
        canEdit: (BlockVec) -> Boolean,
    ): Boolean {
        if (!defender.flagRunner || attack in session.staircases) return false
        val position = defender.entity.position
        if (attack.flagBase.blockY - position.blockY() < MINIMUM_ELEVATION_TO_BUILD) return false
        val flagDistance = hypot(position.x - (attack.flagBase.blockX + 0.5), position.z - (attack.flagBase.blockZ + 0.5))
        if (flagDistance > MAX_STAIRCASE_START_DISTANCE) return false

        session.staircases[attack] = planDefenderStaircase(session.instance, attack.flagBase, position, canEdit) ?: return false
        defender.navigation.clearTerrainRecovery()
        defender.navigation.cancel()
        return true
    }

    // a start nobody can walk to would otherwise block the climb for good, so an untouched plan is made again
    private fun discardUnreachableStaircase(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
    ) {
        val staircase = session.staircases[attack] ?: return
        if (
            staircase.isLoaded(session.instance) &&
            staircase.lastIntactStep(session.instance) == 0 &&
            staircase.stepAt(defender.entity.position) == null
        ) {
            session.staircases.remove(attack)
        }
    }

    private fun placeTerrainBlock(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
        position: BlockVec,
    ) {
        session.instance.setBlock(position, Block.COBBLESTONE)
        session.placedTerrainBlocks[attack] = placedTerrainBlocks(session, attack) + 1
        session.placedTerrainPositions.add(position)
        defender.entity.lookAt(position.add(0.5, 0.5, 0.5))
        defender.entity.swingMainHand()
    }

    private fun canPlaceTerrain(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
    ): Boolean = defender.flagRunner && placedTerrainBlocks(session, attack) < terrainBlockLimit(session, defender, attack)

    private fun placedTerrainBlocks(
        session: DefenseSession,
        attack: Attack,
    ): Int = session.placedTerrainBlocks[attack] ?: 0

    // sized once from where the first builder starts, because the height left to climb shrinks as blocks are placed
    private fun terrainBlockLimit(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
    ): Int = session.terrainBlockLimits.getOrPut(attack) {
        val climbLevels = (attack.flagBase.blockY - defender.entity.position.blockY()).coerceAtLeast(0)
        BASE_PLACED_TERRAIN_BLOCKS_PER_ATTACK + climbLevels * PLACED_TERRAIN_BLOCKS_PER_CLIMB_LEVEL
    }

    private fun beginTerrainBreaking(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
        position: BlockVec,
    ) {
        defender.blockBreaker.begin(
            instance = session.instance,
            kind = DefenderBlockBreakKind.TERRAIN,
            attack = attack,
            position = position,
        )
    }

    private fun tickTerrainBreaking(
        session: DefenseSession,
        defender: AiDefender,
        attack: Attack,
        now: Long,
    ): Boolean {
        val position = defender.blockBreaker.position ?: return false
        if (defender.blockBreaker.attack !== attack) {
            defender.blockBreaker.stop(session.instance)
            defender.navigation.requestTerrainRecovery()
            return false
        }
        val protectedBlocks = sessionProtectedBlocks(session)
        return when (
            defender.blockBreaker.tick(
                instance = session.instance,
                nowMillis = now,
                restartWhenBlockChanges = false,
                canContinue = { candidate, _ -> isTerrainEditable(session, candidate, protectedBlocks) },
            )
        ) {
            DefenderBlockBreakTickResult.IN_PROGRESS -> true
            DefenderBlockBreakTickResult.COMPLETED -> {
                session.instance.setBlock(position, Block.AIR)
                defender.navigation.clearTerrainRecovery()
                defender.navigation.cancel()
                true
            }
            DefenderBlockBreakTickResult.INVALID -> {
                defender.navigation.requestTerrainRecovery()
                false
            }
        }
    }

    private fun sessionProtectedBlocks(session: DefenseSession): Set<BlockVec> = session.attacks
        .asSequence()
        .filter(::isAttackActive)
        .flatMap { attack -> attackProtectedBlocks(attack).asSequence() }
        .toSet()

    private fun isTerrainEditable(
        session: DefenseSession,
        position: BlockVec,
        protectedBlocks: Set<BlockVec> = sessionProtectedBlocks(session),
    ): Boolean = position !in protectedBlocks &&
        !isStandingPlacedTerrain(session, position) &&
        !isActiveFlagColumn(session, position) &&
        session.instance.worldBorder.inBounds(position) &&
        session.instance.isChunkLoaded(position) &&
        Territory.fromBlock(position.blockX(), position.blockZ())?.town === session.targetTown

    // checks the block is still there so a staircase a player knocked out can be rebuilt
    private fun isStandingPlacedTerrain(
        session: DefenseSession,
        position: BlockVec,
    ): Boolean = position in session.placedTerrainPositions &&
        session.instance.getBlock(position).compare(Block.COBBLESTONE)

    private fun isActiveFlagColumn(
        session: DefenseSession,
        position: BlockVec,
    ): Boolean = session.attacks.any { attack ->
        isAttackActive(attack) &&
            position.blockX() == attack.flagBase.blockX &&
            position.blockZ() == attack.flagBase.blockZ
    }
}
