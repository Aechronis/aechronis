package net.aechronis.logger.commands

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.BlockAction
import net.aechronis.logger.objects.BlockChangePlan
import net.aechronis.logger.objects.PendingRollbackRegistry
import net.aechronis.logger.objects.RollbackChange
import net.aechronis.logger.objects.RollbackChangeKind
import net.aechronis.logger.objects.RollbackOperation
import net.aechronis.logger.objects.RollbackPlan
import net.aechronis.logger.objects.RollbackStatus
import net.aechronis.logger.objects.showFeatureLookup
import net.aechronis.logger.objects.showLookup
import net.aechronis.logger.objects.showRollbackPreview
import net.aechronis.logger.objects.showRollbackResult
import net.aechronis.logger.params.LookupParams
import net.aechronis.logger.params.LookupQuery
import net.aechronis.logger.params.LookupSuggestions
import net.aechronis.logger.params.ParamManager
import net.aechronis.logger.params.ParseResult
import net.aechronis.logger.utils.Command
import net.aechronis.logger.utils.ItemCodec
import net.aechronis.logger.utils.Pages
import net.aechronis.logger.utils.hasPermission
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player
import net.minestom.server.instance.block.Block
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

val playerInspectMode = ConcurrentHashMap<UUID, Boolean>()

private val lastRollbackOperation = ConcurrentHashMap<UUID, Long>()
private val redoableOperation = ConcurrentHashMap<UUID, Long>()
private val historyLock = Any()
private val historyGenerations = HashMap<UUID, Long>()
private val historyOperationsInFlight = HashMap<UUID, Long>()

private val rollbackExecutor = Executors.newVirtualThreadPerTaskExecutor()

private const val LOOKUP_USAGE =
    "Usage: /logger lookup u:<user> t:<time> r:<radius> a:<action> i:<include> e:<exclude>" +
        "  |  s:<source> u:<user> t:<time> r:<radius> a:<action>"

private const val ROLLBACK_USAGE =
    "Usage: /logger rollback u:<user> t:<time> r:<radius> a:<action>  (t: is required)"

class LoggerCommand : Command("logger", "logger", "lo") {
    init {
        setDefaultExecutor { sender, _ ->
            sender.sendMessage(
                Component.text("Usage: /logger inspect | lookup | rollback | undo | redo", NamedTextColor.GOLD),
            )
        }
        addSubcommand(LoggerInspectCommand())
        addSubcommand(LoggerLookupCommand())
        addSubcommand(LoggerPageCommand())
        addSubcommand(LoggerRollbackCommand())
        addSubcommand(LoggerUndoCommand())
        addSubcommand(LoggerRedoCommand())
    }
}

class LoggerPageCommand : Command("page", "logger") {
    init {
        val number = ArgumentType.Integer("number")
        addSyntax({ sender: Player, context ->
            Pages.showPage(sender, context.get(number))
        }, number)
    }
}

class LoggerLookupCommand : Command("lookup", "logger", "l") {
    init {
        setDefaultExecutor { sender, _ ->
            sender.sendMessage(Component.text(LOOKUP_USAGE, NamedTextColor.GOLD))
        }

        val params =
            ArgumentType.StringArray("params").setSuggestionCallback { sender, context, suggestion ->
                LookupSuggestions.suggest(sender, context, suggestion)
            }

        addSyntax({ sender: Player, context ->
            val tokens = context.get(params)
            when (val result = ParamManager.parse(tokens)) {
                is ParseResult.Err -> {
                    sender.sendMessage(Component.text(result.message, NamedTextColor.RED))
                    sender.sendMessage(Component.text(LOOKUP_USAGE, NamedTextColor.GRAY))
                }

                is ParseResult.Ok -> {
                    val pos = sender.position
                    when (val query = result.query) {
                        is LookupQuery.Block -> {
                            Logger.repository
                                .searchAsync(query.params, pos.blockX(), pos.blockY(), pos.blockZ())
                                .whenComplete { entries, ex ->
                                    if (ex != null) {
                                        println("lookup failed: $ex")
                                        sender.sendMessage(Component.text("[Logger] lookup failed", NamedTextColor.RED))
                                        return@whenComplete
                                    }
                                    showLookup(sender, entries, query.params.human())
                                }
                        }

                        is LookupQuery.Feature -> {
                            Logger.featureRepository
                                .searchAsync(query.params, pos.blockX(), pos.blockY(), pos.blockZ())
                                .whenComplete { entries, ex ->
                                    if (ex != null) {
                                        println("lookup failed: $ex")
                                        sender.sendMessage(Component.text("[Logger] lookup failed", NamedTextColor.RED))
                                        return@whenComplete
                                    }
                                    showFeatureLookup(sender, entries, query.params.human())
                                }
                        }
                    }
                }
            }
        }, params)
    }
}

class LoggerInspectCommand : Command("inspect", "logger", "i") {
    init {
        addSyntax({ sender: Player, _ ->
            val enabled = playerInspectMode.compute(sender.uuid) { _, current -> current != true } == true

            sender.sendMessage(
                Component.text("Inspect mode ${if (enabled) "enabled" else "disabled"}.", NamedTextColor.GOLD),
            )
        })
    }
}

class LoggerRollbackCommand : Command("rollback", "logger.rollback", "rb") {
    init {
        setDefaultExecutor { sender, _ ->
            sender.sendMessage(Component.text(ROLLBACK_USAGE, NamedTextColor.GOLD))
        }

        val params =
            ArgumentType.StringArray("params").setSuggestionCallback { sender, context, suggestion ->
                LookupSuggestions.suggest(sender, context, suggestion)
            }

        addSyntax({ sender: Player, context ->
            val tokens = context.get(params)

            if (tokens.size == 1 && tokens[0].startsWith("confirm:")) {
                handleConfirm(sender, tokens[0].removePrefix("confirm:"))
                return@addSyntax
            }
            if (tokens.size == 1 && tokens[0].startsWith("cancel:")) {
                handleCancel(sender, tokens[0].removePrefix("cancel:"))
                return@addSyntax
            }

            when (val result = ParamManager.parse(tokens)) {
                is ParseResult.Err -> {
                    sender.sendMessage(Component.text(result.message, NamedTextColor.RED))
                    sender.sendMessage(Component.text(ROLLBACK_USAGE, NamedTextColor.GRAY))
                }

                is ParseResult.Ok -> {
                    when (val query = result.query) {
                        is LookupQuery.Feature -> {
                            sender.sendMessage(Component.text("rollback of s:<source> lookups is not supported", NamedTextColor.RED))
                        }

                        is LookupQuery.Block -> {
                            val targetTs = query.params.since
                            if (targetTs == null) {
                                sender.sendMessage(Component.text("t:<duration> is required for rollback", NamedTextColor.RED))
                                sender.sendMessage(Component.text(ROLLBACK_USAGE, NamedTextColor.GRAY))
                                return@addSyntax
                            }
                            if (sender.instance == null) {
                                sender.sendMessage(Component.text("[Logger] you must be in a world to run rollback", NamedTextColor.RED))
                                return@addSyntax
                            }

                            computeRollbackPlanAsync(sender, query.params, targetTs, query.params.human())
                                .whenComplete { plan, ex ->
                                    if (ex != null) {
                                        println("rollback preview failed: $ex")
                                        sender.sendMessage(Component.text("[Logger] rollback preview failed", NamedTextColor.RED))
                                        return@whenComplete
                                    }
                                    if (plan.totalChangeCount == 0) {
                                        sender.sendMessage(
                                            Component.text("[Logger] nothing to roll back for that query", NamedTextColor.GRAY),
                                        )
                                        return@whenComplete
                                    }
                                    val token = PendingRollbackRegistry.register(sender.uuid, plan)
                                    showRollbackPreview(sender, plan, token)
                                }
                        }
                    }
                }
            }
        }, params)
    }

    private fun handleConfirm(
        sender: Player,
        token: String,
    ) {
        if (!hasPermission(sender, "logger.rollback.confirm")) {
            sender.sendMessage(Component.text("You don't have permission to confirm rollbacks", NamedTextColor.RED))
            return
        }
        val plan = PendingRollbackRegistry.consume(sender.uuid, token)
        if (plan == null) {
            sender.sendMessage(Component.text("[Logger] confirmation expired or invalid, run the rollback again", NamedTextColor.RED))
            return
        }
        applyRollback(sender, plan)
    }

    private fun handleCancel(
        sender: Player,
        token: String,
    ) {
        val cancelled = PendingRollbackRegistry.cancel(sender.uuid, token)
        sender.sendMessage(
            Component.text(if (cancelled) "[Logger] rollback cancelled" else "[Logger] nothing to cancel", NamedTextColor.GRAY),
        )
    }
}

class LoggerUndoCommand : Command("undo", "logger.undo") {
    init {
        setDefaultExecutor { sender, _ ->
            val claim = claimOperation(lastRollbackOperation, sender.uuid)
            if (claim == null) {
                sender.sendMessage(Component.text("[Logger] nothing to undo", NamedTextColor.GRAY))
                return@setDefaultExecutor
            }
            val operationId = claim.operationId
            replayOperation(
                sender,
                operationId,
                useBefore = true,
                generation = claim.generation,
                onDone = {
                    finishHistoryOperation(
                        sender,
                        operationId,
                        claim.generation,
                        RollbackStatus.UNDONE,
                        redoableOperation,
                    ) { canRedo ->
                        if (canRedo) "[Logger] undo complete. Use /logger redo to reapply." else "[Logger] undo complete."
                    }
                },
                onFailure = { restoreOperation(lastRollbackOperation, sender.uuid, claim) },
            )
        }
    }
}

class LoggerRedoCommand : Command("redo", "logger.redo") {
    init {
        setDefaultExecutor { sender, _ ->
            val claim = claimOperation(redoableOperation, sender.uuid)
            if (claim == null) {
                sender.sendMessage(Component.text("[Logger] nothing to redo", NamedTextColor.GRAY))
                return@setDefaultExecutor
            }
            val operationId = claim.operationId
            replayOperation(
                sender,
                operationId,
                useBefore = false,
                generation = claim.generation,
                onDone = {
                    finishHistoryOperation(
                        sender,
                        operationId,
                        claim.generation,
                        RollbackStatus.APPLIED,
                        lastRollbackOperation,
                    ) { "[Logger] redo complete." }
                },
                onFailure = { restoreOperation(redoableOperation, sender.uuid, claim) },
            )
        }
    }
}

private data class ClaimedOperation(
    val operationId: Long,
    val generation: Long,
)

private fun nextHistoryGeneration(uuid: UUID): Long {
    val generation = (historyGenerations[uuid] ?: 0L) + 1L
    historyGenerations[uuid] = generation
    return generation
}

private fun isCurrentHistoryOperation(
    uuid: UUID,
    generation: Long,
): Boolean = historyGenerations[uuid] == generation && historyOperationsInFlight[uuid] == generation

private fun beginRollbackHistory(uuid: UUID): Long? =
    synchronized(historyLock) {
        if (historyOperationsInFlight.containsKey(uuid)) return@synchronized null
        val generation = nextHistoryGeneration(uuid)
        historyOperationsInFlight[uuid] = generation
        lastRollbackOperation.remove(uuid)
        redoableOperation.remove(uuid)
        generation
    }

private fun completeRollbackHistory(
    uuid: UUID,
    generation: Long,
    operationId: Long,
) {
    synchronized(historyLock) {
        if (!isCurrentHistoryOperation(uuid, generation)) return@synchronized
        lastRollbackOperation[uuid] = operationId
        redoableOperation.remove(uuid)
        historyOperationsInFlight.remove(uuid)
    }
}

private fun cancelHistoryOperation(
    uuid: UUID,
    generation: Long,
) {
    synchronized(historyLock) {
        if (isCurrentHistoryOperation(uuid, generation)) {
            historyOperationsInFlight.remove(uuid)
        }
    }
}

private fun claimOperation(
    operations: MutableMap<UUID, Long>,
    uuid: UUID,
): ClaimedOperation? =
    synchronized(historyLock) {
        if (historyOperationsInFlight.containsKey(uuid)) return@synchronized null
        val operationId = operations.remove(uuid) ?: return@synchronized null
        val generation = nextHistoryGeneration(uuid)
        historyOperationsInFlight[uuid] = generation
        ClaimedOperation(operationId, generation)
    }

private fun restoreOperation(
    operations: MutableMap<UUID, Long>,
    uuid: UUID,
    claim: ClaimedOperation,
) {
    synchronized(historyLock) {
        if (!isCurrentHistoryOperation(uuid, claim.generation)) return@synchronized
        operations.putIfAbsent(uuid, claim.operationId)
        historyOperationsInFlight.remove(uuid)
    }
}

private fun moveOperation(
    destination: MutableMap<UUID, Long>,
    uuid: UUID,
    operationId: Long,
    generation: Long,
): Boolean =
    synchronized(historyLock) {
        if (!isCurrentHistoryOperation(uuid, generation)) return@synchronized false
        destination[uuid] = operationId
        historyOperationsInFlight.remove(uuid)
        true
    }

private fun finishHistoryOperation(
    sender: Player,
    operationId: Long,
    generation: Long,
    status: RollbackStatus,
    destination: MutableMap<UUID, Long>,
    successMessage: (Boolean) -> String,
) {
    fun complete(exception: Throwable?) {
        val moved = moveOperation(destination, sender.uuid, operationId, generation)
        if (exception == null) {
            sender.sendMessage(Component.text(successMessage(moved), NamedTextColor.GOLD))
        } else {
            println("failed to update rollback operation status: $exception")
            sender.sendMessage(Component.text("[Logger] operation complete, but failed to update its saved status", NamedTextColor.RED))
        }
    }

    try {
        Logger.rollbackRepository.updateStatusAsync(operationId, status).whenComplete { _, exception -> complete(exception) }
    } catch (exception: Exception) {
        complete(exception)
    }
}

private fun computeRollbackPlanAsync(
    sender: Player,
    params: LookupParams,
    targetTs: Long,
    queryDesc: String,
): CompletableFuture<RollbackPlan> =
    CompletableFuture.supplyAsync({
        computeRollbackPlan(sender, params, targetTs, queryDesc)
    }, rollbackExecutor)

private fun computeRollbackPlan(
    sender: Player,
    params: LookupParams,
    targetTs: Long,
    queryDesc: String,
): RollbackPlan {
    val instanceUuid = sender.instance.uuid
    val pos = sender.position

    val blockRows =
        Logger.repository
            .searchForRollbackAsync(params, targetTs, instanceUuid, pos.blockX(), pos.blockY(), pos.blockZ())
            .get()

    val seenPositions = mutableSetOf<Triple<Int, Int, Int>>()
    val blockChanges = mutableListOf<BlockChangePlan>()
    var skipped = 0
    for (row in blockRows) {
        if (row.action == BlockAction.INTERACT) continue
        val key = Triple(row.x, row.y, row.z)
        if (!seenPositions.add(key)) continue
        if (row.instanceUuid == null) {
            skipped++
            continue
        }
        blockChanges += BlockChangePlan(row.x, row.y, row.z, row.blockOldState, row.blockOld, row.blockOldNbt)
    }

    return RollbackPlan(instanceUuid, targetTs, queryDesc, blockChanges, skipped)
}

private class AppliedBlockChange(
    val x: Int,
    val y: Int,
    val z: Int,
    val before: Block,
    val after: Block,
)

private fun applyRollback(
    sender: Player,
    plan: RollbackPlan,
) {
    MinecraftServer.getSchedulerManager().scheduleNextTick {
        val instance = MinecraftServer.getInstanceManager().getInstance(plan.instanceUuid)
        if (instance == null) {
            sender.sendMessage(Component.text("[Logger] rollback failed: instance no longer exists", NamedTextColor.RED))
            return@scheduleNextTick
        }

        val generation = beginRollbackHistory(sender.uuid)
        if (generation == null) {
            sender.sendMessage(Component.text("[Logger] another rollback history operation is still in progress", NamedTextColor.GRAY))
            return@scheduleNextTick
        }
        val appliedBlocks = mutableListOf<AppliedBlockChange>()
        try {
            for (change in plan.blockChanges) {
                val before = instance.getBlock(change.x, change.y, change.z)
                val base = change.restoreState?.let { Block.fromState(it) } ?: Block.fromKey(change.restoreMaterialKey) ?: continue
                val after = base.withNbt(ItemCodec.decodeBlockNbt(change.restoreNbt))
                instance.setBlock(change.x, change.y, change.z, after)
                appliedBlocks += AppliedBlockChange(change.x, change.y, change.z, before, after)
            }
        } catch (exception: Exception) {
            cancelHistoryOperation(sender.uuid, generation)
            println("failed to apply rollback operation: $exception")
            sender.sendMessage(Component.text("[Logger] failed to apply rollback", NamedTextColor.RED))
            return@scheduleNextTick
        }

        persistAndReport(sender, plan, appliedBlocks, generation)
    }
}

private fun persistAndReport(
    sender: Player,
    plan: RollbackPlan,
    appliedBlocks: List<AppliedBlockChange>,
    generation: Long,
) {
    CompletableFuture
        .runAsync({
            val changes = mutableListOf<RollbackChange>()

            for (b in appliedBlocks) {
                changes +=
                    RollbackChange(
                        operationId = 0,
                        changeKind = RollbackChangeKind.BLOCK,
                        x = b.x,
                        y = b.y,
                        z = b.z,
                        beforeBlockState = b.before.state(),
                        beforeBlockNbt = ItemCodec.encodeBlockNbt(b.before.nbt()),
                        afterBlockState = b.after.state(),
                        afterBlockNbt = ItemCodec.encodeBlockNbt(b.after.nbt()),
                    )
            }

            val operation =
                RollbackOperation(
                    timestamp = System.currentTimeMillis(),
                    actorUuid = sender.uuid,
                    actorName = sender.username,
                    instanceUuid = plan.instanceUuid,
                    queryDesc = plan.queryDesc,
                    targetTs = plan.targetTs,
                    status = RollbackStatus.APPLIED,
                    blockChangeCount = appliedBlocks.size,
                )

            val operationId = Logger.rollbackRepository.insertOperationAsync(operation, changes).get()

            completeRollbackHistory(sender.uuid, generation, operationId)

            showRollbackResult(sender, plan)
        }, rollbackExecutor)
        .exceptionally { exception ->
            cancelHistoryOperation(sender.uuid, generation)
            println("failed to persist rollback operation: $exception")
            sender.sendMessage(Component.text("[Logger] rollback applied but failed to save undo history", NamedTextColor.RED))
            null
        }
}

private fun replayOperation(
    sender: Player,
    operationId: Long,
    useBefore: Boolean,
    generation: Long,
    onDone: () -> Unit,
    onFailure: () -> Unit,
) {
    CompletableFuture
        .supplyAsync({
            val operation = Logger.rollbackRepository.findOperationAsync(operationId).get() ?: return@supplyAsync null
            val changes = Logger.rollbackRepository.findChangesAsync(operationId).get()
            Pair(operation, changes)
        }, rollbackExecutor)
        .thenAccept { result ->
            if (result == null) {
                sender.sendMessage(Component.text("[Logger] operation no longer exists", NamedTextColor.RED))
                onFailure()
                return@thenAccept
            }
            val (operation, changes) = result

            MinecraftServer.getSchedulerManager().scheduleNextTick {
                val isCurrent = synchronized(historyLock) { isCurrentHistoryOperation(sender.uuid, generation) }
                if (!isCurrent) return@scheduleNextTick

                try {
                    val instance = MinecraftServer.getInstanceManager().getInstance(operation.instanceUuid)
                    if (instance == null) {
                        onFailure()
                        sender.sendMessage(Component.text("[Logger] failed: instance no longer exists", NamedTextColor.RED))
                        return@scheduleNextTick
                    }

                    for (change in changes) {
                        when (change.changeKind) {
                            RollbackChangeKind.BLOCK -> {
                                val x = change.x ?: continue
                                val y = change.y ?: continue
                                val z = change.z ?: continue
                                val stateStr = if (useBefore) change.beforeBlockState else change.afterBlockState
                                val nbtBytes = if (useBefore) change.beforeBlockNbt else change.afterBlockNbt
                                val base = stateStr?.let { Block.fromState(it) } ?: continue
                                instance.setBlock(x, y, z, base.withNbt(ItemCodec.decodeBlockNbt(nbtBytes)))
                            }
                        }
                    }
                } catch (exception: Exception) {
                    println("failed to replay rollback operation: $exception")
                    onFailure()
                    sender.sendMessage(Component.text("[Logger] failed to replay operation", NamedTextColor.RED))
                    return@scheduleNextTick
                }

                onDone()
            }
        }.exceptionally { exception ->
            println("failed to replay rollback operation: $exception")
            onFailure()
            sender.sendMessage(Component.text("[Logger] failed to replay operation", NamedTextColor.RED))
            null
        }
}
