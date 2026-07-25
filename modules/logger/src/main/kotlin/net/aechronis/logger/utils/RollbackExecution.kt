package net.aechronis.logger.utils

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.RollbackActor
import net.aechronis.logger.objects.RollbackExecutionResult
import net.aechronis.logger.objects.RollbackPlan
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player

internal object RollbackExecution {
    fun execute(
        sender: Player,
        plan: RollbackPlan,
    ) {
        sender.sendMessage(Component.text("[Logger] ${plan.kind.value} started...", NamedTextColor.GRAY))
        Logger.rollbackService
            .applyAsync(
                RollbackActor(
                    sender.uuid,
                    sender.username,
                ),
                plan,
            ).whenComplete { result, failure ->
                if (failure == null) {
                    reportSuccess(sender, result, plan.kind.value.replaceFirstChar(Char::uppercase))
                } else {
                    reportFailure(sender, "${plan.kind.value} failed", failure)
                }
            }
    }

    fun reportSuccess(
        sender: Player,
        result: RollbackExecutionResult,
        label: String,
    ) {
        sender.sendMessage(
            Component.text(
                "[Logger] $label complete: ${result.appliedCount} applied, ${result.skippedCount} skipped. Use /logger undo to reverse it.",
                NamedTextColor.GOLD,
            ),
        )
    }

    fun reportFailure(
        sender: Player,
        message: String,
        failure: Throwable,
    ) {
        val cause = generateSequence(failure) { it.cause }.last()
        println("[Logger] $message: $failure")
        sender.sendMessage(Component.text("[Logger] $message: ${cause.message ?: cause::class.simpleName}", NamedTextColor.RED))
    }
}
