package net.aechronis.combat.listeners

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.server.modules.ModuleScheduler
import net.minestom.server.entity.Player
import net.minestom.server.entity.PlayerHand
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.attribute.AttributeModifier
import net.minestom.server.entity.attribute.AttributeOperation
import net.minestom.server.event.player.PlayerChangeHeldSlotEvent
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.event.player.PlayerTickEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.event.player.PlayerUseItemOnBlockEvent
import net.minestom.server.timer.TaskSchedule

object AimingListener {
    private fun onPlayerUseItem(event: PlayerUseItemEvent) {
        val player = event.player
        // crossbow material is only for its pose; don't start vanilla charging
        if (Item.getFromItemStack(event.itemStack) is Gun) event.itemUseTime = 0
        if (event.hand != PlayerHand.MAIN || Item.getFromItemStack(player.itemInMainHand) !is Gun) return
        refreshRightClickAim(player)
    }

    private fun onPlayerUseItemOnBlock(event: PlayerUseItemOnBlockEvent) {
        if (event.hand != PlayerHand.MAIN || Item.getFromItemStack(event.player.itemInMainHand) !is Gun) return
        refreshRightClickAim(event.player)
    }

    private fun refreshRightClickAim(player: Player) {
        val state = Combat.playerStates.getOrCreate(player)
        state.aiming = true
        state.cancelAimingReset()

        // right click repeats every four ticks, including during automatic fire
        state.aimingResetTask =
            ModuleScheduler
                .buildTask {
                    Combat.playerStates[player]?.aimingResetTask = null
                    Combat.playerStates.getOrCreate(player).aiming = player.isSneaking
                }.delay(TaskSchedule.tick(6))
                .schedule()
    }

    private fun onPlayerInput(event: PlayerInputEvent) {
        val state = Combat.playerStates.getOrCreate(event.player)
        state.aiming = event.isHoldingShiftKey || state.aimingResetTask != null
    }

    private fun onPlayerChangeHeldSlot(event: PlayerChangeHeldSlotEvent) {
        if (event.isCancelled || event.oldSlot == event.newSlot) return
        val state = Combat.playerStates.getOrCreate(event.player)
        state.cancelAimingReset()
        state.aiming = event.player.isSneaking
    }

    // Sprinting while right-click aiming: the speed modifier cancels the vanilla +30% sprint bonus, and
    // jumping is disabled because the client adds its sprint-jump boost itself. Spread scales with
    // measured speed, so this keeps ADS spread from being taken at sprint pace.
    private val aimSprintModifiers =
        mapOf(
            Attribute.MOVEMENT_SPEED to
                AttributeModifier("aechronis:aim_no_sprint", 1.0 / 1.3 - 1.0, AttributeOperation.ADD_MULTIPLIED_TOTAL),
            Attribute.JUMP_STRENGTH to
                AttributeModifier("aechronis:aim_no_sprint_jump", -1.0, AttributeOperation.ADD_MULTIPLIED_TOTAL),
        )

    // Polled each tick because aimingResetTask is also cleared by catalogue resets and combat tags;
    // attributes are only touched when the state flips.
    private fun onPlayerTick(event: PlayerTickEvent) {
        val player = event.player
        val state = Combat.playerStates[player] ?: return
        val blocked = player.isSprinting && state.aimingResetTask != null
        if (blocked == state.aimSprintBlocked) return
        state.aimSprintBlocked = blocked
        if (blocked) {
            for ((attribute, modifier) in aimSprintModifiers) player.getAttribute(attribute).addModifier(modifier)
        } else {
            clearAimSprintBlock(player)
        }
    }

    /** Removes the aim-while-sprinting modifiers; safe to call when they are not applied. */
    internal fun clearAimSprintBlock(player: Player) {
        for ((attribute, modifier) in aimSprintModifiers) player.getAttribute(attribute).removeModifier(modifier)
        Combat.playerStates[player]?.aimSprintBlocked = false
    }

    fun init() {
        Combat.eventNode.addListener(PlayerTickEvent::class.java, AimingListener::onPlayerTick)
        Combat.eventNode.addListener(PlayerUseItemEvent::class.java, AimingListener::onPlayerUseItem)
        Combat.eventNode.addListener(PlayerUseItemOnBlockEvent::class.java, AimingListener::onPlayerUseItemOnBlock)
        Combat.eventNode.addListener(PlayerInputEvent::class.java, AimingListener::onPlayerInput)
        Combat.eventNode.addListener(PlayerChangeHeldSlotEvent::class.java, AimingListener::onPlayerChangeHeldSlot)
    }
}
