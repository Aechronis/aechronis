package net.aechronis.vanilla.objects

import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

sealed class CropType {
    abstract val cropBlock: Block
    abstract val seedMaterial: Material
    abstract val maxAge: Int
    abstract val unripeDrops: CropsSeedToCrop
    abstract val ripeDrops: CropsSeedToCrop

    data object Wheat : CropType() {
        override val cropBlock = Block.WHEAT
        override val seedMaterial = Material.WHEAT_SEEDS
        override val unripeDrops = CropsSeedToCrop(1, 0)
        override val ripeDrops = CropsSeedToCrop(3, 4)
        override val maxAge = 7
    }

    data object Carrots : CropType() {
        override val cropBlock = Block.CARROTS
        override val seedMaterial = Material.CARROT
        override val unripeDrops = CropsSeedToCrop(0, 1)
        override val ripeDrops = CropsSeedToCrop(0, 4)
        override val maxAge = 7
    }

    data object Potatoes : CropType() {
        override val cropBlock = Block.POTATOES
        override val seedMaterial = Material.POTATO
        override val unripeDrops = CropsSeedToCrop(0, 1)
        override val ripeDrops = CropsSeedToCrop(0, 4)
        override val maxAge = 7
    }

    companion object {
        val ALL: List<CropType> by lazy { listOf(Wheat, Carrots, Potatoes) }

        fun fromBlock(block: Block): CropType? = ALL.firstOrNull { block.compare(it.cropBlock) }

        fun fromSeed(material: Material?): CropType? = ALL.firstOrNull { it.seedMaterial == material }

        fun drops(
            type: CropType,
            age: Int,
        ): List<ItemStack> {
            val data = if (age >= type.maxAge) type.ripeDrops else type.unripeDrops
            val result = mutableListOf<ItemStack>()

            if (data.seedDrops > 0) {
                result.add(ItemStack.of(type.seedMaterial, data.seedDrops))
            }

            if (data.cropDrops > 0) {
                result.add(ItemStack.of(produceMaterial(type), data.cropDrops))
            }

            return result
        }

        private fun produceMaterial(type: CropType): Material =
            when (type) {
                Wheat -> Material.WHEAT
                Carrots -> Material.CARROT
                Potatoes -> Material.POTATO
            }
    }
}
