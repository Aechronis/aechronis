package net.aechronis.watchdog

import net.aechronis.watchdog.alert.StaffAlert
import net.aechronis.watchdog.checks.FlagSink
import net.aechronis.watchdog.objects.PlayerState
import net.aechronis.watchdog.objects.PlayerStateReg
import net.aechronis.watchdog.probe.TranslationProbe
import net.aechronis.watchdog.runtime.AttackRecorder
import net.aechronis.watchdog.runtime.FlagReporter
import net.aechronis.watchdog.runtime.WatchdogEventHandler
import net.aechronis.watchdog.runtime.WatchdogTicker
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.concurrent.atomic.AtomicBoolean

object Watchdog {
    val eventNode: EventNode<Event> = EventNode.all("watchdog")

    private val initialized = AtomicBoolean()
    private lateinit var config: WatchdogConfig
    private var tickTask: Task? = null

    fun initialize(config: WatchdogConfig = WatchdogConfig()) {
        check(initialized.compareAndSet(false, true)) { "Watchdog is already initialized" }
        this.config = config

        val state: (Player) -> PlayerState = PlayerStateReg::getOrCreate
        val isBypassed: (Player) -> Boolean = config.bypass
        val reporter = FlagReporter(config, state)
        val flag: FlagSink = reporter::report
        val alerts = StaffAlert(config)
        val probes = TranslationProbe(config, { PlayerStateReg.currentTick }, alerts)
        val attacks = AttackRecorder(config, state, flag)
        recorder = attacks
        val events = WatchdogEventHandler(config, state, isBypassed, attacks, probes, flag)
        val ticker = WatchdogTicker(config, state, isBypassed, probes, flag)

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
        events.register(eventNode)
        tickTask =
            MinecraftServer
                .getSchedulerManager()
                .buildTask(ticker::tick)
                .repeat(TaskSchedule.tick(1))
                .schedule()

        println("Watchdog enabled")
    }

    fun state(player: Player): PlayerState = PlayerStateReg.getOrCreate(player)

    fun exemptMovement(
        player: Player,
        ticks: Int = 2,
    ) {
        require(ticks > 0)
        state(player).movementExemptUntilTick = PlayerStateReg.currentTick + ticks
    }

    fun expectVelocity(
        player: Player,
        ticks: Int = 10,
    ) {
        require(ticks > 0)
        state(player).velocityExemptUntilTick = PlayerStateReg.currentTick + ticks
    }

    fun reportAttack(
        attacker: Player,
        target: Player,
    ) = attackRecorder().reportAttack(attacker, target)

    fun recordAttackAttempt(
        attacker: Player,
        target: Player,
    ) = attackRecorder().recordAttackAttempt(attacker, target)

    fun recordSwing(player: Player) = attackRecorder().recordSwing(player)

    fun recordKnockback(
        player: Player,
        expectedVelocity: Vec,
        source: String = "unknown",
    ) = attackRecorder().recordKnockback(player, expectedVelocity, source)

    private var recorder: AttackRecorder? = null

    private fun attackRecorder(): AttackRecorder {
        check(initialized.get()) { "Watchdog is not initialized" }
        return recorder ?: error("Watchdog attack recorder is not ready")
    }
}
