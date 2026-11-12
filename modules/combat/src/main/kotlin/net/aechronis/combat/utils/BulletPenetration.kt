package net.aechronis.combat.utils

import net.kyori.adventure.key.Key
import net.minestom.server.instance.block.Block

/** Blocks that handheld gun bullets travel through, matched by id so every variant is included. */
internal object BulletPenetration {
    private val breakableIds =
        setOf(
            "flower_pot",
            "candle",
            "pumpkin",
            "carved_pumpkin",
            "jack_o_lantern",
            "melon",
            "mushroom_stem",
            "hay_block",
            "bamboo",
            "cactus",
            "decorated_pot",
        )

    // Leaves, glass, panes, potted plants, candles and mushroom blocks, plus the ids above.
    private val breakable =
        keysWhere { path ->
            path in breakableIds ||
                path.endsWith("_leaves") ||
                path.endsWith("glass") ||
                path.endsWith("glass_pane") ||
                path.startsWith("potted_") ||
                path.endsWith("_candle") ||
                path.endsWith("_mushroom_block")
        }

    // Iron and copper bars, iron and copper chains, and scaffolding.
    private val passable =
        keysWhere { path -> path.endsWith("bars") || path.endsWith("chain") || path == "scaffolding" }

    /** Bullets break these and keep going; the breaks are temporary. */
    fun breaks(block: Block): Boolean = block.key() in breakable

    /** Bullets pass through these untouched. */
    fun passes(block: Block): Boolean = block.key() in passable

    private fun keysWhere(predicate: (String) -> Boolean): Set<Key> =
        Block
            .values()
            .filter { predicate(it.key().value()) }
            .mapTo(HashSet()) { it.key() }
}
