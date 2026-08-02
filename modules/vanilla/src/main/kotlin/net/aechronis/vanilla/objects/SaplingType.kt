package net.aechronis.vanilla.objects

import net.minestom.server.instance.block.Block
import kotlin.math.abs

sealed class SaplingType {
    abstract val saplingBlock: Block
    abstract val logBlock: Block
    abstract val leavesBlock: Block
    abstract val height: Int

    abstract fun build(b: TreeBuilder)

    open val giant: Boolean = false
    open val giantHeight: Int get() = height

    open fun buildGiant(b: TreeBuilder) = build(b)

    data object Oak : SaplingType() {
        override val saplingBlock = Block.OAK_SAPLING
        override val logBlock = Block.OAK_LOG
        override val leavesBlock = Block.OAK_LEAVES
        override val height = 7

        override fun build(b: TreeBuilder) = oakShape(b)
    }

    data object Spruce : SaplingType() {
        override val saplingBlock = Block.SPRUCE_SAPLING
        override val logBlock = Block.SPRUCE_LOG
        override val leavesBlock = Block.SPRUCE_LEAVES
        override val height = 11
        override val giant = true
        override val giantHeight = 18

        override fun build(b: TreeBuilder) {
            val trunkHeight = 9
            b.trunk(trunkHeight)
            var y = 2
            val radii = intArrayOf(2, 1, 2, 1, 1)
            for (radius in radii) {
                b.leafLayer(y, radius, trimCorners = radius >= 2)
                y++
            }
            // pointed top
            b.leafLayer(trunkHeight - 1, 1, trimCorners = true)
            b.leaf(0, trunkHeight, 0)
        }

        override fun buildGiant(b: TreeBuilder) {
            val trunkHeight = 16
            pillar2x2(b, trunkHeight)
            var wide = true
            for (y in 4..trunkHeight) {
                if (wide) {
                    b.leafRect(y, -2, 3, -2, 3)
                } else {
                    b.leafRect(y, 0, 1, 0, 1)
                }
                wide = !wide
            }
            b.leafRect(trunkHeight + 1, 0, 1, 0, 1)
        }
    }

    data object Birch : SaplingType() {
        override val saplingBlock = Block.BIRCH_SAPLING
        override val logBlock = Block.BIRCH_LOG
        override val leavesBlock = Block.BIRCH_LEAVES
        override val height = 7

        override fun build(b: TreeBuilder) = oakShape(b)
    }

    data object Jungle : SaplingType() {
        override val saplingBlock = Block.JUNGLE_SAPLING
        override val logBlock = Block.JUNGLE_LOG
        override val leavesBlock = Block.JUNGLE_LEAVES
        override val height = 14
        override val giant = true
        override val giantHeight = 17

        override fun build(b: TreeBuilder) {
            val trunkHeight = 11
            b.trunk(trunkHeight)
            val top = trunkHeight - 1
            b.leafLayer(top - 1, 2, trimCorners = true)
            b.leafLayer(top, 2, trimCorners = false)
            b.leafLayer(top + 1, 2, trimCorners = true)
            b.leafLayer(top + 2, 1, trimCorners = false)
        }

        override fun buildGiant(b: TreeBuilder) {
            val trunkHeight = 15
            pillar2x2(b, trunkHeight)
            b.log(-1, trunkHeight - 2, 0)
            b.log(2, trunkHeight - 3, 1)
            b.leafRect(trunkHeight - 2, -2, 3, -2, 3)
            b.leafRect(trunkHeight - 1, -2, 3, -2, 3)
            b.leafRect(trunkHeight, -1, 2, -1, 2)
            b.leafRect(trunkHeight + 1, 0, 1, 0, 1)
        }
    }

    data object Acacia : SaplingType() {
        override val saplingBlock = Block.ACACIA_SAPLING
        override val logBlock = Block.ACACIA_LOG
        override val leavesBlock = Block.ACACIA_LEAVES
        override val height = 8

        override fun build(b: TreeBuilder) {
            for (i in 0 until 4) b.log(0, i, 0)
            b.log(1, 4, 0)
            b.log(2, 5, 0)
            val cx = 2
            val cy = 6
            for (dx in -2..2) {
                for (dz in -2..2) {
                    if (abs(dx) == 2 && abs(dz) == 2) continue
                    b.leaf(cx + dx, cy, dz)
                }
            }
            b.leaf(cx, cy + 1, 0)
        }
    }

    data object DarkOak : SaplingType() {
        override val saplingBlock = Block.DARK_OAK_SAPLING
        override val logBlock = Block.DARK_OAK_LOG
        override val leavesBlock = Block.DARK_OAK_LEAVES
        override val height = 8

        override fun build(b: TreeBuilder) {
            b.trunk(6)
            val top = 5
            b.leafLayer(top - 1, 3, trimCorners = true)
            b.leafLayer(top, 3, trimCorners = true)
            b.leafLayer(top + 1, 2, trimCorners = true)
            b.leafLayer(top + 2, 1, trimCorners = false)
        }
    }

    data object Cherry : SaplingType() {
        override val saplingBlock = Block.CHERRY_SAPLING
        override val logBlock = Block.CHERRY_LOG
        override val leavesBlock = Block.CHERRY_LEAVES
        override val height = 9

        override fun build(b: TreeBuilder) {
            b.trunk(6)
            b.log(1, 5, 0)
            b.log(0, 5, -1)
            val top = 6
            b.leafLayer(top - 1, 3, trimCorners = true)
            b.leafLayer(top, 2, trimCorners = true)
            b.leafLayer(top + 1, 1, trimCorners = false)
        }
    }

    data object PaleOak : SaplingType() {
        override val saplingBlock = Block.PALE_OAK_SAPLING
        override val logBlock = Block.PALE_OAK_LOG
        override val leavesBlock = Block.PALE_OAK_LEAVES
        override val height = 7

        override fun build(b: TreeBuilder) = oakShape(b)
    }

    companion object {
        val ALL: List<SaplingType> by lazy {
            listOf(Oak, Spruce, Birch, Jungle, Acacia, DarkOak, Cherry, PaleOak)
        }

        fun fromBlock(block: Block): SaplingType? = ALL.firstOrNull { block.compare(it.saplingBlock) }

        private fun oakShape(b: TreeBuilder) {
            b.trunk(5)
            val top = 4
            b.leafLayer(top - 1, 2, trimCorners = true)
            b.leafLayer(top, 2, trimCorners = true)
            b.leafLayer(top + 1, 1, trimCorners = false)
            b.leafLayer(top + 2, 1, trimCorners = true)
        }

        private fun pillar2x2(
            b: TreeBuilder,
            height: Int,
        ) {
            for (y in 0 until height) {
                b.log(0, y, 0)
                b.log(1, y, 0)
                b.log(0, y, 1)
                b.log(1, y, 1)
            }
        }
    }
}
