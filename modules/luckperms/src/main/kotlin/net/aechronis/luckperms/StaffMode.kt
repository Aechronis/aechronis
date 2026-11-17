package net.aechronis.luckperms

import net.aechronis.server.CommandOutput
import net.aechronis.server.hasPermission
import net.aechronis.server.modules.ModulePermissions
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.luckperms.api.LuckPerms
import net.luckperms.api.context.ContextCalculator
import net.luckperms.api.context.ContextConsumer
import net.luckperms.api.context.ContextSet
import net.luckperms.api.context.ImmutableContextSet
import net.minestom.server.command.builder.Command
import net.minestom.server.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Supplies the `staffmode` context so staff groups can grant their powers only while it is on
 * (e.g. `/lp group admin permission set guard.bypass true staffmode=true`).
 * Off by default and in memory only, so a restart or reload puts staff back on equal footing.
 */
internal class StaffMode(
    val api: LuckPerms,
) : ContextCalculator<Player> {
    private val active = ConcurrentHashMap.newKeySet<UUID>()

    val command =
        Command("staffmode").apply {
            ModulePermissions.register(PERMISSION)
            setDefaultExecutor { sender, _ ->
                if (sender !is Player) {
                    CommandOutput.send(sender, Component.text("Only players can use staff mode.", NamedTextColor.RED))
                } else if (sender.uuid !in active && !sender.hasPermission(PERMISSION)) {
                    CommandOutput.send(sender, Component.text("You don't have permission to use this command", NamedTextColor.RED))
                } else {
                    val enabled = active.add(sender.uuid) || !active.remove(sender.uuid)
                    api.contextManager.signalContextUpdate(sender)
                    CommandOutput.send(
                        sender,
                        Component.text(
                            "Staff mode ${if (enabled) "enabled" else "disabled"}.",
                            if (enabled) NamedTextColor.GREEN else NamedTextColor.YELLOW,
                        ),
                    )
                }
            }
        }

    override fun calculate(
        target: Player,
        consumer: ContextConsumer,
    ) = consumer.accept(KEY, (target.uuid in active).toString())

    override fun estimatePotentialContexts(): ContextSet =
        ImmutableContextSet
            .builder()
            .add(KEY, "true")
            .add(KEY, "false")
            .build()

    private companion object {
        const val KEY = "staffmode"
        const val PERMISSION = "aechronis.staffmode"
    }
}
