package net.aechronis.guard.objects

import java.util.UUID

data class SelectedRegion(
    val instanceId: UUID,
    val bounds: ZoneBounds,
)
