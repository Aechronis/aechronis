package net.aechronis.vanilla.objects

interface TreeBuilder {
    fun log(
        dx: Int,
        dy: Int,
        dz: Int,
    )

    fun leaf(
        dx: Int,
        dy: Int,
        dz: Int,
    )

    fun trunk(height: Int) {
        for (i in 0 until height) log(0, i, 0)
    }

    fun leafLayer(
        dy: Int,
        radius: Int,
        trimCorners: Boolean,
    )

    fun leafRect(
        dy: Int,
        minDx: Int,
        maxDx: Int,
        minDz: Int,
        maxDz: Int,
    ) {
        for (dx in minDx..maxDx) {
            for (dz in minDz..maxDz) {
                leaf(dx, dy, dz)
            }
        }
    }
}
