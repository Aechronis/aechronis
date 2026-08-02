package net.aechronis.logger.commands

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.PendingRollbackRegistry
import net.aechronis.logger.objects.RollbackDomain
import net.aechronis.logger.objects.RollbackOperationKind
import net.aechronis.logger.objects.RollbackSafety
import net.aechronis.logger.objects.showRollbackPreview
import net.aechronis.logger.params.LookupQuery
import net.aechronis.logger.params.LookupSuggestions
import net.aechronis.logger.params.ParamManager
import net.aechronis.logger.params.ParseResult
import net.aechronis.logger.utils.RollbackExecution
import net.aechronis.logger.utils.parseMutation
import net.aechronis.utils.Command
import net.aechronis.utils.hasPermission
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

const val MUTATION_USAGE =
    "Usage: /logger <rollback|restore> u:<user> t:<time> r:<radius> a:<action> i:<include> e:<exclude> [#preview] [#force]"

open class LoggerMutationCommand(
    name: String,
    permission: String,
    alias: String,
    private val kind: RollbackOperationKind,
) : Command(name, permission, alias) {
    init {
        setDefaultExecutor { sender, _ -> sender.sendMessage(Component.text(MUTATION_USAGE, NamedTextColor.GOLD)) }
        val params =
            ArgumentType.StringArray("params").setSuggestionCallback { sender, context, suggestion ->
                LookupSuggestions.suggest(sender, context, suggestion)
            }

        addSyntax({ sender: Player, context ->
            val tokens = context.get(params)
            if (tokens.size == 1 && tokens[0].startsWith("confirm:")) {
                confirm(sender, tokens[0].removePrefix("confirm:"))
                return@addSyntax
            }
            if (tokens.size == 1 && tokens[0].startsWith("cancel:")) {
                val cancelled = PendingRollbackRegistry.cancel(sender.uuid, tokens[0].removePrefix("cancel:"))
                sender.sendMessage(
                    Component.text(if (cancelled) "[Logger] operation cancelled" else "[Logger] nothing to cancel", NamedTextColor.GRAY),
                )
                return@addSyntax
            }

            val flags = tokens.filter { it.startsWith('#') }.map { it.lowercase() }.toSet()
            val unknownFlags = flags - setOf("#preview", "#force", "#verbose", "#silent")
            if (unknownFlags.isNotEmpty()) {
                sender.sendMessage(Component.text("Unknown flag: ${unknownFlags.first()}", NamedTextColor.RED))
                return@addSyntax
            }
            if ("#force" in flags && !sender.hasPermission("logger.rollback.force")) {
                sender.sendMessage(Component.text("You don't have permission to force operations", NamedTextColor.RED))
                return@addSyntax
            }

            val mutation = parseMutation(tokens.filterNot { it.startsWith('#') }.toTypedArray())
            if (mutation.error != null) {
                sender.sendMessage(Component.text(mutation.error, NamedTextColor.RED))
                return@addSyntax
            }
            when (val parsed = ParamManager.parse(mutation.parserTokens)) {
                is ParseResult.Err -> {
                    sender.sendMessage(Component.text(parsed.message, NamedTextColor.RED))
                    sender.sendMessage(Component.text(MUTATION_USAGE, NamedTextColor.GRAY))
                }

                is ParseResult.Ok -> {
                    val query = parsed.query
                    if (query !is LookupQuery.Block) {
                        sender.sendMessage(Component.text("Only block history can currently be mutated", NamedTextColor.RED))
                        return@addSyntax
                    }
                    val targetTs = query.params.since
                    if (targetTs == null) {
                        sender.sendMessage(Component.text("t:<duration> is required", NamedTextColor.RED))
                        return@addSyntax
                    }
                    val instance = sender.instance
                    if (instance == null) {
                        sender.sendMessage(Component.text("[Logger] you must be in an instance", NamedTextColor.RED))
                        return@addSyntax
                    }
                    val effectiveParams =
                        if (query.params.radius == null && query.params.chunkRadius == null && !query.params.global) {
                            query.params.copy(radius = Logger.config.defaultRollbackRadius)
                        } else {
                            query.params
                        }
                    if (RollbackDomain.STORAGE in mutation.selection.domains && !effectiveParams.global) {
                        sender.sendMessage(
                            Component.text("[Logger] storage operations require r:#global", NamedTextColor.RED),
                        )
                        return@addSyntax
                    }
                    val sourceScopedStorage =
                        mutation.selection.domains == setOf(RollbackDomain.STORAGE) && effectiveParams.source != null
                    if (
                        effectiveParams.global &&
                        effectiveParams.users.isEmpty() &&
                        mutation.selection.domains != setOf(RollbackDomain.ENTITY) &&
                        !sourceScopedStorage
                    ) {
                        sender.sendMessage(Component.text("[Logger] global operations require u:<user>", NamedTextColor.RED))
                        return@addSyntax
                    }
                    if (
                        RollbackDomain.STORAGE in mutation.selection.domains &&
                        effectiveParams.users.isEmpty() &&
                        effectiveParams.source == null
                    ) {
                        sender.sendMessage(Component.text("[Logger] storage operations require u:<user> or c:<source>", NamedTextColor.RED))
                        return@addSyntax
                    }
                    sender.sendMessage(Component.text("[Logger] searching history...", NamedTextColor.GRAY))
                    Logger.rollbackService
                        .computePlanAsync(
                            kind,
                            effectiveParams,
                            targetTs,
                            instance.uuid,
                            sender.position,
                            safeMode = "#force" !in flags,
                            selection = mutation.selection,
                        ).whenComplete { plan, failure ->
                            if (failure != null) {
                                RollbackExecution.reportFailure(sender, "preview failed", failure)
                                return@whenComplete
                            }
                            when {
                                plan.totalChangeCount == 0 -> {
                                    sender.sendMessage(Component.text("[Logger] no matching changes", NamedTextColor.GRAY))
                                }

                                "#preview" in flags -> {
                                    showRollbackPreview(sender, plan, null)
                                }

                                RollbackSafety.enabled(sender.uuid) -> {
                                    val token = PendingRollbackRegistry.register(sender.uuid, plan)
                                    showRollbackPreview(sender, plan, token)
                                }

                                else -> {
                                    RollbackExecution.execute(sender, plan)
                                }
                            }
                        }
                }
            }
        }, params)
    }

    private fun confirm(
        sender: Player,
        token: String,
    ) {
        if (!sender.hasPermission("logger.rollback.confirm")) {
            sender.sendMessage(Component.text("You don't have permission to confirm operations", NamedTextColor.RED))
            return
        }
        val plan = PendingRollbackRegistry.consume(sender.uuid, token)
        if (plan == null) {
            sender.sendMessage(Component.text("[Logger] confirmation expired or invalid", NamedTextColor.RED))
            return
        }
        RollbackExecution.execute(sender, plan)
    }
}

class Restore : LoggerMutationCommand("restore", "logger.restore", "rs", RollbackOperationKind.RESTORE)

class Rollback : LoggerMutationCommand("rollback", "logger.rollback", "rb", RollbackOperationKind.ROLLBACK)
