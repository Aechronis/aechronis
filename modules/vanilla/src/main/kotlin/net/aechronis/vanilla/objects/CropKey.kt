package net.aechronis.vanilla.objects

import net.minestom.server.coordinate.Vec
import net.minestom.server.instance.Instance

data class CropKey(
    val instance: Instance,
    val pos: Vec,
)
