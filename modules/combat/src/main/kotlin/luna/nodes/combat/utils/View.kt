package luna.nodes.combat.utils

object View {
    fun addYaw(
        currentYaw: Float,
        delta: Float,
    ): Float {
        var newYaw: Float = currentYaw + delta

        // Normalize to -180 to 180
        while (newYaw <= -180) newYaw += 360f
        while (newYaw > 180) newYaw -= 360f

        return newYaw
    }
}
