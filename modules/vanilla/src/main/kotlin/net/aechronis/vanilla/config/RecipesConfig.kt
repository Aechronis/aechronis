package net.aechronis.vanilla.config

import net.aechronis.vanilla.objects.Recipe

data class RecipesConfig(
    val recpies: List<Recipe> = emptyList(),
)
