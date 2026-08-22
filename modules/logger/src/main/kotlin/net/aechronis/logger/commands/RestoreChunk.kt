package net.aechronis.logger.commands

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.PendingRollbackRegistry
import net.aechronis.logger.objects.RollbackActor
import net.aechronis.logger.objects.RollbackOperationKind
import net.aechronis.logger.objects.showChunkRestorePreview
import net.aechronis.logger.utils.RollbackExecution
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

private const val RESTORE_CHUNK_USAGE =
    "Usage: /logger restorechunk [<chunk-x> <chunk-z> | radius <radius> | radius <chunk-x> <chunk-z> <radius>]"
private const val MIN_CHUNK_COORDINATE = -1_875_000
private const val MAX_CHUNK_COORDINATE = 1_875_000
private const val MAX_CHUNK_RADIUS = 8

class RestoreChunk : Command("restorechunk", "logger.restorechunk", "rc") {
    init {
        setDefaultExecutor { sender: Player, _ ->
            preview(sender, sender.position.blockX() shr 4, sender.position.blockZ() shr 4, 0)
        }

        val chunkX = ArgumentType.Integer("chunk-x").between(MIN_CHUNK_COORDINATE, MAX_CHUNK_COORDINATE)
        val chunkZ = ArgumentType.Integer("chunk-z").between(MIN_CHUNK_COORDINATE, MAX_CHUNK_COORDINATE)
        addSyntax({ sender: Player, context ->
            preview(sender, context.get(chunkX), context.get(chunkZ), 0)
        }, chunkX, chunkZ)

        val radiusLiteral = ArgumentType.Literal("radius")
        val radius = ArgumentType.Integer("chunk-radius").between(1, MAX_CHUNK_RADIUS)
        addSyntax({ sender: Player, context ->
            preview(sender, sender.position.blockX() shr 4, sender.position.blockZ() shr 4, context.get(radius))
        }, radiusLiteral, radius)
        addSyntax({ sender: Player, context ->
            preview(sender, context.get(chunkX), context.get(chunkZ), context.get(radius))
        }, radiusLiteral, chunkX, chunkZ, radius)

        val confirmation = ArgumentType.Word("confirmation")
        addSyntax({ sender: Player, context ->
            val value = context.get(confirmation)
            when {
                value.startsWith("confirm:") -> confirm(sender, value.removePrefix("confirm:"))
                value.startsWith("cancel:") -> cancel(sender, value.removePrefix("cancel:"))
                else -> sender.sendMessage(Component.text(RESTORE_CHUNK_USAGE, NamedTextColor.GOLD))
            }
        }, confirmation)
    }

    private fun preview(
        sender: Player,
        centerChunkX: Int,
        centerChunkZ: Int,
        radius: Int,
    ) {
        if (
            centerChunkX - radius < MIN_CHUNK_COORDINATE ||
            centerChunkX + radius > MAX_CHUNK_COORDINATE ||
            centerChunkZ - radius < MIN_CHUNK_COORDINATE ||
            centerChunkZ + radius > MAX_CHUNK_COORDINATE
        ) {
            sender.sendMessage(Component.text("[Logger] chunk radius extends beyond the world border", NamedTextColor.RED))
            return
        }
        val instance =
            sender.instance
                ?: run {
                    sender.sendMessage(Component.text("[Logger] you must be in an instance", NamedTextColor.RED))
                    return
                }
        val chunkCount = (radius * 2 + 1) * (radius * 2 + 1)
        val selection =
            if (radius == 0) {
                "chunk $centerChunkX,$centerChunkZ"
            } else {
                "$chunkCount chunks within radius $radius of $centerChunkX,$centerChunkZ"
            }
        sender.sendMessage(Component.text("[Logger] reading original $selection...", NamedTextColor.GRAY))
        Logger.originalChunkService.computePlanAsync(instance, centerChunkX, centerChunkZ, radius).whenComplete { plan, failure ->
            if (failure != null) {
                RollbackExecution.reportFailure(sender, "chunk restore preview failed", failure)
                return@whenComplete
            }
            if (plan.totalChangeCount == 0) {
                sender.sendMessage(Component.text("[Logger] $selection already matches the original", NamedTextColor.GRAY))
                return@whenComplete
            }
            val token = PendingRollbackRegistry.register(sender.uuid, plan)
            showChunkRestorePreview(sender, plan, token, centerChunkX, centerChunkZ, radius)
        }
    }

    private fun confirm(
        sender: Player,
        token: String,
    ) {
        val plan = PendingRollbackRegistry.consume(sender.uuid, token)
        if (plan == null || plan.kind != RollbackOperationKind.CHUNK_RESTORE) {
            sender.sendMessage(Component.text("[Logger] confirmation expired or invalid", NamedTextColor.RED))
            return
        }
        sender.sendMessage(Component.text("[Logger] chunk restore started...", NamedTextColor.GRAY))
        Logger.rollbackService
            .applyAsync(RollbackActor(sender.uuid, sender.username), plan)
            .whenComplete { result, failure ->
                if (failure == null) {
                    RollbackExecution.reportSuccess(sender, result, "Chunk restore")
                } else {
                    RollbackExecution.reportFailure(sender, "chunk restore failed", failure)
                }
            }
    }

    private fun cancel(
        sender: Player,
        token: String,
    ) {
        val cancelled = PendingRollbackRegistry.cancel(sender.uuid, token)
        sender.sendMessage(
            Component.text(if (cancelled) "[Logger] chunk restore cancelled" else "[Logger] nothing to cancel", NamedTextColor.GRAY),
        )
    }
}
