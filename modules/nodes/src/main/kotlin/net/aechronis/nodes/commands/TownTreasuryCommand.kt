package net.aechronis.nodes.commands

import net.aechronis.economy.Economy
import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Town
import net.aechronis.server.modules.ModuleScheduler
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.dialog.Dialog
import net.minestom.server.dialog.DialogAction
import net.minestom.server.dialog.DialogActionButton
import net.minestom.server.dialog.DialogAfterAction
import net.minestom.server.dialog.DialogBody
import net.minestom.server.dialog.DialogInput
import net.minestom.server.dialog.DialogMetadata
import net.minestom.server.entity.Player
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerCustomClickEvent
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val MAX_MEMBER_SLOTS = 45

private class TreasurySession(val inventory: Inventory, val town: Town, val members: List<Resident>, val collectSlot: Int)

/** `/town treasury`: staff move members' stone coins into the town deposit and withdraw from it. */
class TownTreasuryCommand : NodesCommand("treasury", null, "deposit") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /town treasury [withdraw|collect <amount>]")
        }

        addSyntax({ player, resident, town, _ ->
            if (isTownStaff(resident, town)) open(player, town) else Message.print(player, "${town.name} deposit: ${town.coins} coins")
        })

        val amountArg = ArgumentType.Long("amount").min(1L)

        addSyntax({ player, resident, town, context ->
            if (!requireTownStaff(player, resident, town, "Only leaders and officers can withdraw from the deposit")) return@addSyntax
            val amount = context[amountArg]
            if (!Town.adjustCoins(town, -amount)) {
                Message.error(player, "The deposit only has ${town.coins} coins")
                return@addSyntax
            }
            val credited = runCatching { Economy.addCoins(player, amount) }
            if (credited.isFailure) {
                check(Town.adjustCoins(town, amount)) { "Could not return $amount coins to ${town.name}" }
                Message.error(player, "Could not withdraw coins: ${credited.exceptionOrNull()?.message}")
                return@addSyntax
            }
            Message.print(player, "Withdrew $amount coins. Deposit: ${town.coins} coins")
            println("[Treasury] ${player.username} withdrew $amount coins from ${town.name} (deposit ${town.coins})")
        }, ArgumentType.Literal("withdraw"), amountArg)

        addSyntax({ player, resident, town, context ->
            if (!requireTownStaff(player, resident, town, "Only leaders and officers can collect coins")) return@addSyntax
            collectEvenly(player, town, context[amountArg])
        }, ArgumentType.Literal("collect"), amountArg)

        Nodes.eventNode.addListener(InventoryPreClickEvent::class.java, ::onClick)
        Nodes.eventNode.addListener(PlayerCustomClickEvent::class.java, ::onCollectDialog)
        Nodes.eventNode.addListener(InventoryCloseEvent::class.java) { event ->
            sessions[event.player.uuid]?.takeIf { it.inventory === event.inventory }?.let { sessions.remove(event.player.uuid, it) }
        }
    }

    companion object {
        private val sessions = ConcurrentHashMap<UUID, TreasurySession>()
        private val collectAction = Key.key("nodes", "town_treasury_collect")

        /** Called on module shutdown so no stale menu outlives its listeners. */
        fun closeAll() {
            sessions.forEach { (uuid, session) ->
                MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid)?.takeIf { it.openInventory === session.inventory }?.closeInventory()
            }
            sessions.clear()
        }

        private fun onCollectDialog(event: PlayerCustomClickEvent) {
            if (event.key != collectAction) return
            val player = event.player
            val resident = Resident.fromPlayer(player) ?: return
            val town = resident.town ?: return
            if (!requireTownStaff(player, resident, town, "Only leaders and officers can collect coins")) return
            val amount = (event.payload as? CompoundBinaryTag)?.getString("amount")?.trim()?.toLongOrNull()
            if (amount == null || amount < 1L) return Message.error(player, "Enter a whole number of coins")
            collectEvenly(player, town, amount)
        }

        private fun open(player: Player, town: Town) {
            // ponytail: one page, members past 45 are only reachable through /town treasury collect
            val members = town.residents.sortedBy { it.name.lowercase() }.take(MAX_MEMBER_SLOTS)
            val rows = (members.size + 8) / 9 + 1
            val inventory = Inventory(InventoryType.valueOf("CHEST_${rows}_ROW"), Component.text("${town.name} Treasury", NamedTextColor.GOLD))
            members.forEachIndexed { slot, member ->
                val coins = Economy.coinBalance(member.uuid) ?: 0L
                inventory.setItemStack(slot, item(Material.PLAYER_HEAD, member.name, NamedTextColor.YELLOW, "Coins: $coins", "Click to take all"))
            }
            val controls = (rows - 1) * 9
            inventory.setItemStack(controls + 3, item(Material.GOLD_INGOT, "Deposit: ${town.coins} coins", NamedTextColor.GOLD, "/town treasury withdraw <amount>"))
            inventory.setItemStack(controls + 5, item(Material.EMERALD, "Collect from everyone", NamedTextColor.GREEN, "Split an amount evenly across all members"))
            val session = TreasurySession(inventory, town, members, controls + 5)
            sessions[player.uuid] = session
            if (!player.openInventory(inventory)) sessions.remove(player.uuid, session)
        }

        private fun onClick(event: InventoryPreClickEvent) {
            val player = event.player
            val session = sessions[player.uuid] ?: return
            if (player.openInventory !== session.inventory) return
            event.isCancelled = true
            if (event.inventory !== session.inventory) return
            val resident = Resident.fromPlayer(player) ?: return
            if (resident.town !== session.town || !isTownStaff(resident, session.town)) return player.closeInventory()
            if (event.slot == session.collectSlot) {
                player.closeInventory()
                player.showDialog(collectDialog())
                return
            }
            val member = session.members.getOrNull(event.slot) ?: return
            if (member.town !== session.town) return Message.error(player, "${member.name} is no longer in your town")
            val taken = takeCoins(session.town, member, Long.MAX_VALUE)
            Message.print(player, "Took $taken coins from ${member.name}. Deposit: ${session.town.coins} coins")
            println("[Treasury] ${player.username} took $taken coins from ${member.name} into ${session.town.name} (deposit ${session.town.coins})")
            ModuleScheduler.scheduleNextTick { if (player.isOnline && player.openInventory === session.inventory) open(player, session.town) }
        }

        /** Takes `total` split evenly across members; a member short of their share gives what they have. */
        private fun collectEvenly(player: Player, town: Town, total: Long) {
            val members = town.residents.sortedBy { it.name.lowercase() }
            val share = total / members.size
            val remainder = total % members.size
            val short = mutableListOf<String>()
            var collected = 0L
            members.forEachIndexed { index, member ->
                val due = share + if (index < remainder) 1 else 0
                if (due == 0L) return@forEachIndexed
                val taken = takeCoins(town, member, due)
                collected += taken
                if (taken < due) short += member.name
            }
            Message.print(player, "Collected $collected of $total coins from ${members.size} members. Deposit: ${town.coins} coins")
            println("[Treasury] ${player.username} collected $collected of $total coins into ${town.name} (deposit ${town.coins})")
            if (short.isNotEmpty()) Message.print(player, "Could not pay their full share: ${short.joinToString(", ")}")
        }

        private fun takeCoins(town: Town, member: Resident, limit: Long): Long {
            val amount = minOf(limit, Economy.coinBalance(member.uuid) ?: 0L)
            if (amount <= 0L || !Economy.removeCoins(member.uuid, amount)) return 0L
            check(Town.adjustCoins(town, amount)) { "Could not deposit $amount coins taken from ${member.name} into ${town.name}" }
            return amount
        }

        private fun collectDialog(): Dialog.Confirmation = Dialog.Confirmation(
            DialogMetadata(
                Component.text("Collect from everyone", NamedTextColor.GOLD),
                null,
                true,
                false,
                DialogAfterAction.CLOSE,
                listOf(DialogBody.PlainMessage(Component.text("The total is split evenly across all members.", NamedTextColor.GRAY), 260)),
                listOf(DialogInput.Text("amount", 200, Component.text("Total coins"), true, "", 18, null)),
            ),
            DialogActionButton(Component.text("Collect", NamedTextColor.GREEN), null, 120, DialogAction.DynamicCustom(collectAction, CompoundBinaryTag.empty())),
            DialogActionButton(Component.text("Cancel", NamedTextColor.GRAY), null, 120, null),
        )

        private fun item(material: Material, name: String, color: NamedTextColor, vararg lore: String): ItemStack = ItemStack.of(material)
            .withCustomName(Component.text(name, color))
            .withLore(lore.map { Component.text(it, NamedTextColor.GRAY) })
    }
}
