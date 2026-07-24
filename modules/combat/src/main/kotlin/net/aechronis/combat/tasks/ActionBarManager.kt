package net.aechronis.combat.tasks

import net.aechronis.combat.Combat
import net.aechronis.combat.objects.Car
import net.aechronis.combat.objects.Drone
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Item
import net.aechronis.combat.objects.Plane
import net.aechronis.combat.objects.Vehicle
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.timer.TaskSchedule
import java.util.Locale
import kotlin.math.abs

object ActionBarManager {
    // run scheduler updating action bar
    fun start() {
        MinecraftServer
            .getSchedulerManager()
            .buildTask {
                for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                    updateActionBar(player)
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }

    fun updateActionBar(player: Player) {
        val vehicleTelemetry = vehicleTelemetry(player)
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun
        val ammo = gun?.takeIf { Combat.reloadTasks[player] == null }?.ammoText(player)

        val actionBar =
            when {
                vehicleTelemetry != null && ammo != null -> vehicleTelemetry.append(ammo)
                vehicleTelemetry != null -> vehicleTelemetry
                ammo != null -> ammo
                else -> return
            }
        player.sendActionBar(actionBar)
    }

    private fun vehicleTelemetry(player: Player): Component? {
        val vehicle = Vehicle.playerVehicle[player] ?: return null
        val text =
            when (vehicle) {
                is Drone -> return null
                is Plane ->
                    formatPlaneTelemetry(
                        vehicle.speed,
                        Plane.playerThrottle[player] ?: 0f,
                        vehicle.maxThrottle,
                    )
                is Car -> formatCarTelemetry(Car.playerSpeed[player] ?: 0f)
                else -> return null
            }
        return Component.text(text, NamedTextColor.GRAY)
    }

    internal fun formatPlaneTelemetry(
        topSpeedPerTick: Double,
        throttle: Float,
        maxThrottle: Float,
    ): String {
        val throttleFraction = throttle / maxThrottle
        return String.format(
            Locale.ROOT,
            "[%.1f blocks/s | %.0f%% throttle]",
            topSpeedPerTick * throttleFraction * TICKS_PER_SECOND,
            throttleFraction * 100,
        )
    }

    internal fun formatCarTelemetry(speedPerTick: Float): String =
        String.format(
            Locale.ROOT,
            "[%.1f blocks/s]",
            abs(speedPerTick) * TICKS_PER_SECOND,
        )

    private const val TICKS_PER_SECOND = 20
}
