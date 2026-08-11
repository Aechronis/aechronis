package net.aechronis.vanilla.config

import net.aechronis.vanilla.objects.Crate

data class CratesConfig(
    val crates: List<Crate> = emptyList(),
)
