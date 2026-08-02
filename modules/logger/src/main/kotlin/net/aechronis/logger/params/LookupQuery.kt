package net.aechronis.logger.params

sealed interface LookupQuery {
    data class Block(
        val params: LookupParams,
    ) : LookupQuery

    data class Feature(
        val params: FeatureLookupParams,
    ) : LookupQuery
}
