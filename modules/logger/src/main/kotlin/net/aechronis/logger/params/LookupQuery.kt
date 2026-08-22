package net.aechronis.logger.params

import net.aechronis.logger.objects.StorageChangeAction

sealed interface LookupQuery {
    data class Block(
        val params: LookupParams,
    ) : LookupQuery

    data class Feature(
        val params: FeatureLookupParams,
    ) : LookupQuery

    data class Storage(
        val params: LookupParams,
        val actions: Set<StorageChangeAction>,
    ) : LookupQuery {
        fun human(): String {
            val actionSummary = "actions=${actions.joinToString(",") { it.value }}"
            val commonSummary = params.human()
            return if (commonSummary == "(no filters)") actionSummary else "$commonSummary  $actionSummary"
        }
    }
}
