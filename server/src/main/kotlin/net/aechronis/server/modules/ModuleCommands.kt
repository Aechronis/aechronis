package net.aechronis.server.modules

import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.recipe.Recipe
import java.util.concurrent.ConcurrentHashMap

/** Registers callbacks before publishing commands, and keeps teardown ownership exact. */
object ModuleCommands {
    private val registered = ConcurrentHashMap<Command, Command>()

    fun register(vararg commands: Command) {
        val manager = MinecraftServer.getCommandManager()
        val scope = ModuleRuntime.captureScope()
        check(scope != null || !ModuleRuntime.isManagedRuntime()) { "No module scope is active" }
        synchronized(manager) {
            commands.forEach { command ->
                val published =
                    if (scope == null) {
                        command
                    } else {
                        instrument(command, scope)
                        ScopedCommand(command, scope)
                    }
                manager.register(published)
                if (scope != null) {
                    scope.commands += published
                    registered[command] = published
                }
            }
        }
    }

    fun unregister(command: Command) {
        val manager = MinecraftServer.getCommandManager()
        synchronized(manager) {
            val published = registered.remove(command) ?: command
            if (published is ScopedCommand) registered.remove(published.delegate, published)
            // Old teardown must never remove a replacement with the same name.
            if (manager.getCommand(published.name) === published) manager.unregister(published)
        }
    }

    private fun instrument(
        command: Command,
        scope: ModuleResourceScope,
    ) {
        scopeArguments(command, scope)
        val condition = command.condition
        command.setCondition { sender, input -> scope.dispatchCallback(false) { condition?.canUse(sender, input) ?: true } }
        command.defaultExecutor?.let { executor ->
            command.setDefaultExecutor { sender, context -> scope.dispatchCallback(Unit) { executor.apply(sender, context) } }
        }
        command.syntaxes.forEach { syntax ->
            val executor = syntax.executor
            syntax.executor =
                net.minestom.server.command.builder.CommandExecutor { sender, context ->
                    scope.dispatchCallback(Unit) { executor.apply(sender, context) }
                }
            val syntaxCondition = syntax.commandCondition
            syntax.commandCondition =
                net.minestom.server.command.builder.condition.CommandCondition { sender, input ->
                    scope.dispatchCallback(false) { syntaxCondition?.canUse(sender, input) ?: true }
                }
        }
        command.subcommands.forEach { instrument(it, scope) }
    }

    private class ScopedCommand(
        val delegate: Command,
        private val scope: ModuleResourceScope,
    ) : Command(delegate.name, *(delegate.aliases ?: emptyArray())) {
        init {
            condition = delegate.condition
            defaultExecutor = delegate.defaultExecutor
            syntaxes.addAll(delegate.syntaxes)
            delegate.subcommands.forEach { addSubcommand(ScopedCommand(it, scope)) }
        }

        override fun globalListener(
            sender: CommandSender,
            context: CommandContext,
            command: String,
        ) {
            scope.dispatchCallback(Unit) { delegate.globalListener(sender, context, command) }
        }
    }
}

/** Recipe identity belongs to its registering module even when the recipe class is core-owned. */
object ModuleRecipes {
    fun addRecipe(recipe: Recipe) {
        val scope = ModuleRuntime.captureScope()
        check(scope != null || !ModuleRuntime.isManagedRuntime()) { "No module scope is active" }
        scope?.recipes?.add(recipe)
        MinecraftServer.getRecipeManager().addRecipe(recipe)
    }

    fun removeRecipe(recipe: Recipe) {
        MinecraftServer.getRecipeManager().removeRecipe(recipe)
    }
}
