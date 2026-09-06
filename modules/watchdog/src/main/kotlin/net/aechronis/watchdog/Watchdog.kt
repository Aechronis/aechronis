package net.aechronis.watchdog

import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleEvents
import net.aechronis.server.modules.ModuleScheduler
import net.aechronis.watchdog.alert.StaffAlert
import net.aechronis.watchdog.checks.FlagSink
import net.aechronis.watchdog.commands.WatchdogCommand
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
    lateinit var eventNode: EventNode<Event>
        private set

    private val initialized = AtomicBoolean()
    private lateinit var config: WatchdogConfig
    private lateinit var alerts: StaffAlert
    private var tickTask: Task? = null
    private var command: WatchdogCommand? = null
    private var probes: TranslationProbe? = null

    @Synchronized
    fun initialize(config: WatchdogConfig = WatchdogConfig()) {
        check(initialized.compareAndSet(false, true)) { "Watchdog is already initialized" }
        try {
            this.config = config
            eventNode = EventNode.all("watchdog")

            val state: (Player) -> PlayerState = PlayerStateReg::getOrCreate
            val isBypassed: (Player) -> Boolean = config.bypass
            alerts = StaffAlert(config)
            val reporter = FlagReporter(config, state, alerts)
            val flag: FlagSink = reporter::report
            val probeService = TranslationProbe(config, { PlayerStateReg.currentTick }, alerts)
            probes = probeService
            val attacks = AttackRecorder(config, state, flag)
            recorder = attacks
            val events = WatchdogEventHandler(config, state, isBypassed, attacks, probeService, alerts, flag)
            val ticker = WatchdogTicker(config, state, isBypassed, probeService, flag)

            events.register(eventNode)
            ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
            command = WatchdogCommand(config.staffAlertPermission)
            ModuleCommands
                .register(command!!)
            tickTask =
                ModuleScheduler
                    .buildTask(ticker::tick)
                    .repeat(TaskSchedule.tick(1))
                    .schedule()

            println("Watchdog enabled")
        } catch (error: Throwable) {
            shutdown()
            throw error
        }
    }

    @Synchronized
    fun shutdown() {
        if (!initialized.get()) return

        tickTask?.let { task ->
            task.cancel()
            tickTask = null
        }
        probes?.let { activeProbes ->
            activeProbes.endAll(notify = false)
            probes = null
        }
        if (this::eventNode.isInitialized) {
            MinecraftServer.getGlobalEventHandler().removeChild(eventNode)
        }
        command?.let { registered ->
            ModuleCommands
                .unregister(registered)
            command = null
        }
        PlayerStateReg.clear()
        if (this::alerts.isInitialized) alerts.clear()
        recorder = null
        initialized.set(false)
    }

    fun state(player: Player): PlayerState = PlayerStateReg.getOrCreate(player)

    fun toggleAlerts(player: Player): Boolean {
        check(initialized.get()) { "Watchdog is not initialized" }
        return alerts.toggle(player)
    }

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
