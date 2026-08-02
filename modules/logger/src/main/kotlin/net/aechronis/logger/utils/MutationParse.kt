package net.aechronis.logger.utils

import net.aechronis.logger.objects.EntityChangeAction
import net.aechronis.logger.objects.RollbackDomain
import net.aechronis.logger.objects.RollbackSelection
import net.aechronis.logger.objects.StorageChangeAction

internal data class MutationParse(
    val parserTokens: Array<String>,
    val selection: RollbackSelection,
    val error: String? = null,
)

internal fun parseMutation(tokens: Array<String>): MutationParse {
    val actionTokens =
        tokens
            .filter { it.substringBefore(':').equals("a", true) || it.substringBefore(':').equals("actions", true) }
            .flatMap { it.substringAfter(':', "").split(',') }
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
    if (actionTokens.isEmpty()) {
        return MutationParse(tokens, RollbackSelection(setOf(RollbackDomain.BLOCK)))
    }

    val domains = mutableSetOf<RollbackDomain>()
    val blockActions = mutableListOf<String>()
    var storageActions: MutableSet<StorageChangeAction>? = null
    var entityActions: MutableSet<EntityChangeAction>? = null
    for (action in actionTokens) {
        when (action) {
            "block" -> {
                domains += RollbackDomain.BLOCK
                blockActions += "block"
            }

            "+block", "place" -> {
                domains += RollbackDomain.BLOCK
                blockActions += "place"
            }

            "-block", "break" -> {
                domains += RollbackDomain.BLOCK
                blockActions += "break"
            }

            "container", "storage", "item" -> {
                domains += RollbackDomain.STORAGE
                storageActions = (storageActions ?: mutableSetOf()).apply { addAll(StorageChangeAction.entries) }
            }

            "+container", "+storage", "deposit" -> {
                domains += RollbackDomain.STORAGE
                storageActions = (storageActions ?: mutableSetOf()).apply { add(StorageChangeAction.DEPOSIT) }
            }

            "-container", "-storage", "withdraw" -> {
                domains += RollbackDomain.STORAGE
                storageActions = (storageActions ?: mutableSetOf()).apply { add(StorageChangeAction.WITHDRAW) }
            }

            "entity" -> {
                domains += RollbackDomain.ENTITY
                entityActions = (entityActions ?: mutableSetOf()).apply { addAll(EntityChangeAction.entries) }
            }

            "despawn" -> {
                domains += RollbackDomain.ENTITY
                entityActions = (entityActions ?: mutableSetOf()).apply { add(EntityChangeAction.DESPAWN) }
            }

            "spawn" -> {
                domains += RollbackDomain.ENTITY
                entityActions = (entityActions ?: mutableSetOf()).apply { add(EntityChangeAction.SPAWN) }
            }

            else -> {
                return MutationParse(tokens, RollbackSelection(), "Invalid rollback action: '$action'")
            }
        }
    }

    val parserTokens =
        tokens
            .filterNot {
                it.substringBefore(':').equals("a", true) || it.substringBefore(':').equals("actions", true)
            }.toMutableList()
    if (blockActions.isNotEmpty()) parserTokens += "a:${blockActions.joinToString(",")}"
    return MutationParse(
        parserTokens.toTypedArray(),
        RollbackSelection(domains = domains, storageActions = storageActions, entityActions = entityActions),
    )
}
