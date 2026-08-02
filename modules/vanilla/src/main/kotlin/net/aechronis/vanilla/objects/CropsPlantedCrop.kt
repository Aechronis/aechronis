package net.aechronis.vanilla.objects

data class CropsPlantedCrop(
    val cropType: CropType,
    val plantedAt: Long,
    val initialAge: Int,
)
