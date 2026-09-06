package net.aechronis.server.modules

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.luckperms.api.LuckPermsProvider
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.command.builder.suggestion.SuggestionEntry
import net.minestom.server.entity.Player
import java.util.concurrent.CompletableFuture

const val MODULE_MANAGEMENT_PERMISSION = "server.modules.manage"

class ModuleCommand(
    private val administration: ModuleAdministration,
    private val canManage: (CommandSender) -> Boolean = ::hasModuleManagementPermission,
) : Command("modules", "module") {
    private val moduleId =
        ArgumentType.Word("module").setSuggestionCallback { _, _, suggestion ->
            administration.snapshot().modules.forEach { suggestion.addEntry(SuggestionEntry(it.id)) }
        }

    init {
        // Minestom also evaluates command conditions while building a player's command tree, so
        // this predicate must remain free of user-visible side effects.
        setCondition { sender, _ -> canManage(sender) }

        setDefaultExecutor { sender, _ -> usage(sender) }
        addSyntax({ sender, _ -> list(sender) }, ArgumentType.Literal("list"))
        addSyntax({ sender, context -> info(sender, context[moduleId]) }, ArgumentType.Literal("info"), moduleId)
        addSyntax({ sender, context -> enable(sender, context[moduleId]) }, ArgumentType.Literal("load"), moduleId)
        addSyntax({ sender, context -> enable(sender, context[moduleId]) }, ArgumentType.Literal("enable"), moduleId)
        addSyntax({ sender, context -> disable(sender, context[moduleId], false) }, ArgumentType.Literal("unload"), moduleId)
        addSyntax(
            { sender, context -> disable(sender, context[moduleId], true) },
            ArgumentType.Literal("unload"),
            moduleId,
            ArgumentType.Literal("cascade"),
        )
        addSyntax({ sender, context -> disable(sender, context[moduleId], false) }, ArgumentType.Literal("disable"), moduleId)
        addSyntax(
            { sender, context -> disable(sender, context[moduleId], true) },
            ArgumentType.Literal("disable"),
            moduleId,
            ArgumentType.Literal("cascade"),
        )
        addSyntax({ sender, context -> restart(sender, context[moduleId]) }, ArgumentType.Literal("restart"), moduleId)
        addSyntax({ sender, _ -> apply(sender, "reload", administration::reload) }, ArgumentType.Literal("reload"))
        addSyntax({ sender, _ -> apply(sender, "rescan", administration::reload) }, ArgumentType.Literal("rescan"))
    }

    private fun usage(sender: CommandSender) {
        sender.sendMessage(
            Component.text(
                "Usage: /modules <list|info|load|unload|enable|disable|restart|reload|rescan>",
                NamedTextColor.YELLOW,
            ),
        )
    }

    private fun list(sender: CommandSender) {
        val snapshot = administration.snapshot()
        sender.sendMessage(
            Component.text(
                "Modules (generation ${snapshot.generation}; ${snapshot.phase}):",
                NamedTextColor.GOLD,
            ),
        )
        if (snapshot.modules.isEmpty()) {
            sender.sendMessage(Component.text("No modules discovered.", NamedTextColor.GRAY))
            return
        }
        snapshot.modules.sortedBy { it.id }.forEach { module ->
            val state = module.displayState()
            sender.sendMessage(Component.text("- ${module.id}: $state", module.stateColor()))
        }
    }

    private fun info(
        sender: CommandSender,
        id: String,
    ) {
        val snapshot = administration.snapshot()
        val module = snapshot.modules.firstOrNull { it.id == id }
        if (module == null) {
            sender.sendMessage(Component.text("Unknown module '$id'.", NamedTextColor.RED))
            return
        }

        val state = module.displayState()
        sender.sendMessage(Component.text("${module.id}: $state", module.stateColor()))
        sender.sendMessage(Component.text("Dependencies: ${module.dependencies.describeIds()}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("Dependants: ${module.dependants.describeIds()}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("Source: ${module.sourceJar}", NamedTextColor.GRAY))
        module.reason?.let { sender.sendMessage(Component.text(it, NamedTextColor.YELLOW)) }
    }

    private fun enable(
        sender: CommandSender,
        id: String,
    ) = apply(sender, "enable '$id'") { administration.enable(id) }

    private fun disable(
        sender: CommandSender,
        id: String,
        cascade: Boolean,
    ) = apply(sender, "disable '$id'") { administration.disable(id, cascade) }

    private fun restart(
        sender: CommandSender,
        id: String,
    ) = apply(sender, "restart '$id'") { administration.restart(id) }

    private fun apply(
        sender: CommandSender,
        description: String,
        operation: () -> CompletableFuture<ModuleOperationResult>,
    ) {
        sender.sendMessage(Component.text("Starting $description…", NamedTextColor.YELLOW))
        val future = runCatching(operation).getOrElse { CompletableFuture.failedFuture(it) }
        future.whenComplete { completed, error ->
            val result = completed ?: ModuleOperationResult(false, "Failed to $description: ${error?.message}")
            reportResult(sender, result)
        }
    }

    private fun reportResult(
        sender: CommandSender,
        result: ModuleOperationResult,
    ) {
        val suffix =
            result.affectedIds
                .sorted()
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = " (affected: ", postfix = ")")
                .orEmpty()
        val rollback = if (result.rolledBack) " (rolled back)" else ""
        sender.sendMessage(
            Component.text(
                result.message + suffix + rollback,
                if (result.success) NamedTextColor.GREEN else NamedTextColor.RED,
            ),
        )
    }
}

private fun Collection<String>.describeIds(): String = sorted().joinToString().ifEmpty { "none" }

private fun ModuleStatus.displayState(): String = if (enabled) "enabled" else "disabled"

private fun ModuleStatus.stateColor(): NamedTextColor = if (enabled) NamedTextColor.GREEN else NamedTextColor.GRAY

private fun hasModuleManagementPermission(sender: CommandSender): Boolean {
    if (sender !is Player) return true
    if (System.getProperty("aechronis.dangerously-enable-all-permissions").toBoolean()) return true
    return runCatching {
        LuckPermsProvider
            .get()
            .userManager
            .getUser(sender.uuid)
            ?.cachedData
            ?.permissionData
            ?.checkPermission(MODULE_MANAGEMENT_PERMISSION)
            ?.asBoolean() == true
    }.getOrDefault(false)
}
