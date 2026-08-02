package net.aechronis.vanilla.objects

import net.minestom.server.coordinate.BlockVec
import net.minestom.server.instance.Instance

data class KothConfig(
    val name: String,
    val instance: Instance,
    val cornerOne: BlockVec,
    val cornerTwo: BlockVec,
    val captureSeconds: Long,
    val rewards: List<KothReward> = emptyList(),
) {
    val zone: KothZone = KothZone(cornerOne, cornerTwo)

    fun validate() {
        require(name.isNotBlank()) { "KOTH names cannot be blank" }
        require(captureSeconds > 0) { "KOTH $name must have a positive capture duration" }
        rewards.filterIsInstance<KothReward.Command>().forEach { reward ->
            require(reward.command.isNotBlank()) { "KOTH $name has a blank command reward" }
        }
    }
}
