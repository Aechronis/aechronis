package net.aechronis.server.config

import net.aechronis.combat.objects.ArmorPiece
import net.aechronis.combat.objects.Gun
import net.aechronis.combat.objects.Melee
import net.aechronis.duels.Kit
import net.aechronis.duels.KitContents
import net.aechronis.server.constants.Armor
import net.aechronis.server.constants.Guns
import net.aechronis.server.constants.Melees
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

object VictorianDuelsKits {
    fun create(): List<Kit> =
        listOf(
            kit(
                "rifleman",
                "Rifleman",
                Guns.dreyseNeedleGun,
                listOf(Guns.dreyseNeedleGun to 64),
                Melees.cavalrySabre,
                listOf(Armor.prussiaJacket, Armor.prussiaTrousers, Armor.prussiaBoots),
            ),
            kit(
                "marksman",
                "Marksman",
                Guns.berdanII,
                listOf(Guns.berdanII to 64, Guns.adamsMkIII to 24),
                null,
                listOf(Armor.franceJacket, Armor.franceTrousers, Armor.franceBoots),
                sidearm = Guns.adamsMkIII,
            ),
            kit(
                "gunslinger",
                "Gunslinger",
                Guns.coltSingleActionArmy,
                listOf(Guns.coltSingleActionArmy to 48, Guns.coachGun to 24),
                null,
                listOf(Armor.britainJacket, Armor.britainTrousers, Armor.britainBoots),
                sidearm = Guns.coachGun,
            ),
            kit(
                "skirmisher",
                "Skirmisher",
                Guns.chassepotCarbine,
                listOf(Guns.chassepotCarbine to 48),
                Melees.navalCutlass,
                listOf(Armor.austriaJacket, Armor.austriaTrousers, Armor.austriaBoots),
            ),
        )

    /** Slot 0 melee (if any), then the guns, then the ammo for each gun; armor is jacket/trousers/boots. */
    private fun kit(
        id: String,
        name: String,
        icon: Gun,
        ammo: List<Pair<Gun, Int>>,
        melee: Melee?,
        armor: List<ArmorPiece>,
        sidearm: Gun? = null,
    ): Kit =
        Kit(id, name, { icon.toLoadedItemStack() }) {
            val items = LinkedHashMap<Int, ItemStack>()
            var slot = 0
            melee?.let { items[slot++] = it.toItemStack() }
            items[slot++] = icon.toLoadedItemStack()
            sidearm?.let { items[slot++] = it.toLoadedItemStack() }
            ammo.map { it.first.ammo to it.second }.distinctBy { it.first.name }.forEach { (a, n) ->
                items[slot++] = a.toItemStack().withAmount(n.coerceAtMost(64))
            }
            items[8] = ItemStack.of(Material.COOKED_BEEF, 16)
            KitContents(
                items,
                chestplate = armor[0].toItemStack(),
                leggings = armor[1].toItemStack(),
                boots = armor[2].toItemStack(),
            )
        }
}
