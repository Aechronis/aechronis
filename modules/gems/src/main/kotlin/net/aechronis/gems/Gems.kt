package net.aechronis.gems

import net.aechronis.nodes.objects.MiningBoostManager
import net.aechronis.server.modules.ModuleEvents
import net.aechronis.utils.Command
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.objects.StorageContents
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.dialog.Dialog
import net.minestom.server.dialog.DialogAction
import net.minestom.server.dialog.DialogActionButton
import net.minestom.server.dialog.DialogAfterAction
import net.minestom.server.dialog.DialogBody
import net.minestom.server.dialog.DialogInput
import net.minestom.server.dialog.DialogMetadata
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.inventory.InventoryCloseEvent
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.player.PlayerCustomClickEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.inventory.Inventory
import net.minestom.server.inventory.InventoryType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val PASTE_CHUNK_COST = 50L
private const val BLOCK_BARREL_COST = 20L
private const val BLOCKS_PER_PAGE = 45
private const val AMOUNT_KEY = "amount"
private const val TOKEN_KEY = "token"
private val PASTE_AMOUNT_ACTION = Key.key("gems", "paste_amount")
private val PASTE_CONFIRM_ACTION = Key.key("gems", "paste_confirm")
private val BOOST_SELECT_ACTION = Key.key("gems", "boost_select")
private val BOOST_CONFIRM_ACTION = Key.key("gems", "boost_confirm")
private val CANCEL_ACTION = Key.key("gems", "cancel")
private val transactionTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

object Gems {
    /** Minimal transaction bridge for integrations such as CraftingStore. */
    fun craftingStoreWithdraw(
        uuid: UUID,
        amount: Long,
        reason: String,
    ): UUID? = repository.purchase(uuid, reason, amount)

    fun craftingStoreRefund(
        uuid: UUID,
        amount: Long,
        reason: String,
    ): Boolean = repository.refund(uuid, amount, reason)

    fun craftingStoreBalance(uuid: UUID): Long? = repository.findPlayerByUuid(uuid)?.balance

    fun rememberPlayer(player: Player) = repository.rememberPlayer(player.uuid, player.username)

    fun grantVoteReward(
        player: Player,
        amount: Long,
        deliver: () -> Boolean,
    ): Boolean {
        require(amount > 0L) { "Vote reward must be positive" }
        repository.rememberPlayer(player.uuid, player.username)
        if (repository.adjust(player.uuid, amount) == null) return false

        var delivered = false
        try {
            delivered = deliver()
            return delivered
        } finally {
            if (!delivered) {
                check(repository.adjust(player.uuid, -amount) != null) {
                    "Could not roll back an undelivered vote reward for ${player.username}"
                }
            }
        }
    }

    private val initialized = AtomicBoolean()
    private val sessions = ConcurrentHashMap<UUID, ShopSession>()
    private val repository = GemRepository()
    lateinit var eventNode: EventNode<net.minestom.server.event.Event>
        private set
    private var commands: List<Command> = emptyList()

    @Synchronized
    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            eventNode = EventNode.all("gems")
            eventNode.addListener(InventoryPreClickEvent::class.java, ::onInventoryClick)
            eventNode.addListener(InventoryCloseEvent::class.java, ::onInventoryClose)
            eventNode.addListener(PlayerCustomClickEvent::class.java, ::onCustomClick)
            ModuleEvents.addChild(MinecraftServer.getGlobalEventHandler(), eventNode)
            commands = listOf(GemCommand(repository), GemShopCommand())
            commands.forEach(MinecraftServer.getCommandManager()::register)
        } catch (error: Throwable) {
            shutdown()
            throw error
        }
    }

    @Synchronized
    fun shutdown() {
        if (!initialized.get()) return

        if (this::eventNode.isInitialized) {
            MinecraftServer.getGlobalEventHandler().removeChild(eventNode)
        }
        val commandManager = MinecraftServer.getCommandManager()
        commands.forEach { command -> commandManager.unregister(command) }
        commands = emptyList()
        val activeSessions = sessions.toMap()
        activeSessions.forEach { (uuid, session) ->
            MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid)?.let { player ->
                if (session.inventory != null && player.openInventory === session.inventory) player.closeInventory()
                player.closeDialog()
            }
        }
        sessions.clear()
        initialized.set(false)
    }

    fun openShop(player: Player) {
        repository.rememberPlayer(player.uuid, player.username)
        val inventory = Inventory(InventoryType.CHEST_3_ROW, Component.text("Gem Shop", NamedTextColor.DARK_AQUA))
        inventory.setItemStack(11, item(Material.FILLED_MAP, "Paste chunks", NamedTextColor.GOLD, "50 gems per chunk", "Choose a quantity"))
        inventory.setItemStack(
            13,
            item(Material.DIAMOND_PICKAXE, "Mining boost", NamedTextColor.GREEN, "2x–5x for one hour", "500–1500 gems"),
        )
        inventory.setItemStack(15, item(Material.GOLDEN_PICKAXE, "Haste", NamedTextColor.YELLOW, "1x–2x for one hour", "500–1500 gems"))
        inventory.setItemStack(
            22,
            item(Material.BARREL, "Barrel of blocks", NamedTextColor.AQUA, "20 gems", "Select a survival block"),
        )
        sessions[player.uuid] = ShopSession(ShopScreen.MAIN, inventory = inventory)
        if (!player.openInventory(inventory)) sessions.remove(player.uuid)
    }

    private fun onInventoryClick(event: InventoryPreClickEvent) {
        val player = event.player
        val session = sessions[player.uuid] ?: return
        val inventory = session.inventory ?: return
        if (player.openInventory !== inventory) return
        event.isCancelled = true
        if (event.inventory !== inventory) return

        when (session.screen) {
            ShopScreen.MAIN -> {
                when (event.slot) {
                    11 -> openPasteAmountDialog(player, session)
                    13 -> openBoostSelector(player, session, BoostType.MINING)
                    15 -> openBoostSelector(player, session, BoostType.HASTE)
                    22 -> buyBlockBarrel(player, session)
                }
            }

            ShopScreen.BLOCKS -> {
                handleBlockClick(player, session, event.slot)
            }

            else -> return
        }
    }

    private fun onInventoryClose(event: InventoryCloseEvent) {
        val session = sessions[event.player.uuid] ?: return
        if (event.inventory === session.inventory) sessions.remove(event.player.uuid, session)
    }

    private fun openPasteAmountDialog(
        player: Player,
        session: ShopSession,
    ) {
        session.inventory = null
        player.closeInventory()
        session.screen = ShopScreen.PASTE_AMOUNT
        session.token = UUID.randomUUID().toString()
        player.showDialog(amountDialog(session.token))
    }

    private fun openBoostSelector(
        player: Player,
        session: ShopSession,
        type: BoostType,
    ) {
        session.inventory = null
        player.closeInventory()
        session.screen = ShopScreen.BOOST_SELECT
        session.boostType = type
        session.token = UUID.randomUUID().toString()
        player.showDialog(boostSelectorDialog(session.token, type))
    }

    private fun onCustomClick(event: PlayerCustomClickEvent) {
        if (event.key !in setOf(PASTE_AMOUNT_ACTION, PASTE_CONFIRM_ACTION, BOOST_SELECT_ACTION, BOOST_CONFIRM_ACTION, CANCEL_ACTION)) return
        val player = event.player
        val session = sessions[player.uuid] ?: return
        val payload = event.payload as? CompoundBinaryTag ?: return
        if (payload.getString(TOKEN_KEY) != session.token) return
        if (event.key == CANCEL_ACTION) {
            sessions.remove(player.uuid, session)
            return
        }

        when (event.key) {
            PASTE_AMOUNT_ACTION -> handlePasteAmount(player, session, payload.getString(AMOUNT_KEY))
            PASTE_CONFIRM_ACTION -> confirmPasteOrder(player, session)
            BOOST_SELECT_ACTION -> handleBoostSelection(player, session, payload.getString(AMOUNT_KEY))
            BOOST_CONFIRM_ACTION -> confirmBoost(player, session)
        }
    }

    private fun handlePasteAmount(
        player: Player,
        session: ShopSession,
        input: String,
    ) {
        val amount = input.trim().toIntOrNull()
        if (amount == null || amount !in 1..1_000 || amount.toLong() > Long.MAX_VALUE / PASTE_CHUNK_COST) {
            player.sendMessage(Component.text("Enter a whole chunk amount from 1 to 1000.", NamedTextColor.RED))
            session.token = UUID.randomUUID().toString()
            player.showDialog(amountDialog(session.token))
            return
        }
        session.amount = amount
        session.screen = ShopScreen.PASTE_CONFIRM
        session.token = UUID.randomUUID().toString()
        player.showDialog(confirmDialog(session.token, "Paste $amount chunk(s)", amount * PASTE_CHUNK_COST))
    }

    private fun confirmPasteOrder(
        player: Player,
        session: ShopSession,
    ) {
        if (session.screen != ShopScreen.PASTE_CONFIRM || session.amount <= 0) return
        val total = session.amount.toLong() * PASTE_CHUNK_COST
        val transaction = repository.purchase(player.uuid, "paste-chunk:${session.amount}", total)
        sessions.remove(player.uuid, session)
        if (transaction == null) {
            player.sendMessage(Component.text("You need $total gems for that paste-chunk order.", NamedTextColor.RED))
            return
        }
        player.sendMessage(
            Component.text("Paste-chunk order confirmed for $total gems. Transaction ID: $transaction", NamedTextColor.GREEN),
        )
    }

    private fun handleBoostSelection(
        player: Player,
        session: ShopSession,
        input: String,
    ) {
        val type = session.boostType ?: return
        val multiplier = input.toIntOrNull()
        if (multiplier == null || multiplier !in type.range) return
        session.amount = multiplier
        session.screen = ShopScreen.BOOST_CONFIRM
        session.token = UUID.randomUUID().toString()
        player.showDialog(confirmDialog(session.token, "${type.label} ${multiplier}x for one hour", type.price(multiplier)))
    }

    private fun confirmBoost(
        player: Player,
        session: ShopSession,
    ) {
        val type = session.boostType ?: return
        val multiplier = session.amount
        if (session.screen != ShopScreen.BOOST_CONFIRM || multiplier !in type.range) return
        val price = type.price(multiplier)
        val transaction = repository.purchase(player.uuid, "${type.command}:$multiplier:1h", price)
        if (transaction == null) {
            sessions.remove(player.uuid, session)
            player.sendMessage(Component.text("You need $price gems for this boost.", NamedTextColor.RED))
            return
        }

        MiningBoostManager
            .addBoost(type.command, multiplier, 3_600_000L)
            .onSuccess {
                sessions.remove(player.uuid, session)
                player.sendMessage(
                    Component.text(
                        "${type.label} ${multiplier}x activated for one hour. Transaction ID: $transaction",
                        NamedTextColor.GREEN,
                    ),
                )
            }.onFailure { error ->
                repository.adjust(player.uuid, price)
                sessions.remove(player.uuid, session)
                player.sendMessage(
                    Component.text("Boost could not be activated; your gems were refunded: ${error.message}", NamedTextColor.RED),
                )
            }
    }

    private fun buyBlockBarrel(
        player: Player,
        session: ShopSession,
    ) {
        openBlocks(player, session, 0)
    }

    private fun openBlocks(
        player: Player,
        session: ShopSession,
        requestedPage: Int,
    ) {
        val lastPage = ((nonOreBlocks.size - 1) / BLOCKS_PER_PAGE).coerceAtLeast(0)
        val page = requestedPage.coerceIn(0, lastPage)
        val inventory =
            Inventory(InventoryType.CHEST_6_ROW, Component.text("Barrel of Blocks ${page + 1}/${lastPage + 1}", NamedTextColor.DARK_AQUA))
        nonOreBlocks.drop(page * BLOCKS_PER_PAGE).take(BLOCKS_PER_PAGE).forEachIndexed { slot, material ->
            inventory.setItemStack(slot, ItemStack.of(material))
        }
        if (page > 0) inventory.setItemStack(45, item(Material.ARROW, "Previous page", NamedTextColor.AQUA))
        inventory.setItemStack(49, item(Material.BARRIER, "Close", NamedTextColor.RED))
        if (page < lastPage) inventory.setItemStack(53, item(Material.ARROW, "Next page", NamedTextColor.AQUA))
        session.screen = ShopScreen.BLOCKS
        session.inventory = inventory
        session.page = page
        player.openInventory(inventory)
    }

    private fun handleBlockClick(
        player: Player,
        session: ShopSession,
        slot: Int,
    ) {
        when (slot) {
            45 -> {
                openBlocks(player, session, session.page - 1)
            }

            49 -> {
                player.closeInventory()
            }

            53 -> {
                openBlocks(player, session, session.page + 1)
            }

            in 0 until BLOCKS_PER_PAGE -> {
                val material = nonOreBlocks.getOrNull(session.page * BLOCKS_PER_PAGE + slot) ?: return
                placeBlockBarrel(player, session, material)
            }
        }
    }

    private fun placeBlockBarrel(
        player: Player,
        session: ShopSession,
        material: Material,
    ) {
        val instance = player.instance ?: return
        val position = player.position.asBlockVec()
        if (!instance.getBlock(position).isAir) {
            player.sendMessage(Component.text("Move so there is empty space at your feet to place the barrel.", NamedTextColor.RED))
            return
        }

        val contents = StorageContents()
        val stack = ItemStack.of(material, material.maxStackSize())
        for (slot in 0 until contents.inventory.size) {
            contents.inventory.setItemStack(slot, stack)
        }

        val transaction = repository.purchase(player.uuid, "block-barrel:${material.key().value()}", BLOCK_BARREL_COST)
        if (transaction == null) {
            player.sendMessage(Component.text("You need $BLOCK_BARREL_COST gems for a barrel of blocks.", NamedTextColor.RED))
            return
        }

        // Only replace air: a shop purchase must never delete a player's block.
        instance.setBlock(position, Storage.withContents(Block.BARREL, contents))
        Storage.register(Storage.keyFor(instance, position), contents)
        sessions.remove(player.uuid, session)
        player.closeInventory()
        player.sendMessage(
            Component.text("Placed a barrel full of ${material.key().value()}. Transaction ID: $transaction", NamedTextColor.GREEN),
        )
    }

    private fun item(
        material: Material,
        name: String,
        color: NamedTextColor,
        vararg lore: String,
    ): ItemStack =
        ItemStack
            .of(material)
            .with(
                DataComponents.CUSTOM_NAME,
                Component.text(name, color),
            ).with(
                DataComponents.LORE,
                lore.map { Component.text(it, NamedTextColor.GRAY) },
            )

    private val nonOreBlocks: List<Material> by lazy {
        Material
            .values()
            .filter(Material::isBlock)
            .filter(::isSurvivalObtainableBlock)
            .sortedBy { it.key().asString() }
    }

    private fun isSurvivalObtainableBlock(material: Material): Boolean {
        val path = material.key().value()
        return path !in unobtainableBlockPaths && !isOreMaterial(path)
    }

    /** Excludes mined ores, raw-ore blocks, and compact blocks made from their drops. */
    private fun isOreMaterial(key: String): Boolean =
        key == "ancient_debris" ||
            key.startsWith("raw_") ||
            key in oreResourceBlockPaths ||
            key in copperBlockPaths ||
            Regex("(^|_)ore(s)?($|_)").containsMatchIn(key)

    private val oreResourceBlockPaths =
        setOf(
            "coal_block",
            "copper_block",
            "diamond_block",
            "emerald_block",
            "gold_block",
            "iron_block",
            "lapis_block",
            "netherite_block",
            "quartz_block",
            "redstone_block",
        )

    // Copper storage blocks have oxidation and waxed variants, all of which are excluded.
    private val copperBlockPaths =
        setOf(
            "copper_block",
            "exposed_copper",
            "oxidized_copper",
            "waxed_copper_block",
            "waxed_exposed_copper",
            "waxed_oxidized_copper",
            "waxed_weathered_copper",
            "weathered_copper",
        )

    /** Blocks with no survival-obtainable item form, plus Ender Chests by shop policy. */
    private val unobtainableBlockPaths =
        setOf(
            "air",
            "barrier",
            "bedrock",
            "budding_amethyst",
            "chain_command_block",
            "chorus_plant",
            "command_block",
            "dirt_path",
            "end_portal_frame",
            "ender_chest",
            "farmland",
            "infested_chiseled_stone_bricks",
            "infested_cobblestone",
            "infested_cracked_stone_bricks",
            "infested_deepslate",
            "infested_mossy_stone_bricks",
            "infested_stone",
            "infested_stone_bricks",
            "jigsaw",
            "large_fern",
            "light",
            "petrified_oak_slab",
            "repeating_command_block",
            "reinforced_deepslate",
            "spawner",
            "structure_block",
            "structure_void",
            "suspicious_gravel",
            "suspicious_sand",
            "tall_grass",
            "test_block",
            "test_instance_block",
            "trial_spawner",
            "vault",
        )
}

private class ShopSession(
    var screen: ShopScreen,
    var inventory: Inventory? = null,
    var token: String = UUID.randomUUID().toString(),
    var amount: Int = 0,
    var page: Int = 0,
    var boostType: BoostType? = null,
)

private enum class ShopScreen { MAIN, PASTE_AMOUNT, PASTE_CONFIRM, BOOST_SELECT, BOOST_CONFIRM, BLOCKS }

private enum class BoostType(
    val label: String,
    val command: String,
    val range: IntRange,
) {
    MINING("Mining boost", "boost", 2..5),
    HASTE("Haste", "haste", 1..2),
    ;

    fun price(multiplier: Int): Long =
        when (this) {
            MINING -> mapOf(2 to 500L, 3 to 1_000L, 4 to 1_250L, 5 to 1_500L).getValue(multiplier)
            HASTE -> mapOf(1 to 500L, 2 to 1_500L).getValue(multiplier)
        }
}

private fun amountDialog(token: String): Dialog.Confirmation =
    Dialog.Confirmation(
        DialogMetadata(
            Component.text("Paste chunks", NamedTextColor.GOLD),
            null,
            true,
            false,
            DialogAfterAction.CLOSE,
            listOf(DialogBody.PlainMessage(Component.text("Paste chunks cost 50 gems each.", NamedTextColor.GRAY), 260)),
            listOf(DialogInput.Text(AMOUNT_KEY, 260, Component.text("Chunk amount"), true, "1", 4, null)),
        ),
        DialogActionButton(
            Component.text("Continue", NamedTextColor.GREEN),
            null,
            120,
            DialogAction.DynamicCustom(PASTE_AMOUNT_ACTION, tokenPayload(token)),
        ),
        DialogActionButton(
            Component.text("Cancel", NamedTextColor.RED),
            null,
            120,
            DialogAction.DynamicCustom(CANCEL_ACTION, tokenPayload(token)),
        ),
    )

private fun boostSelectorDialog(
    token: String,
    type: BoostType,
): Dialog.Confirmation =
    Dialog.Confirmation(
        DialogMetadata(
            Component.text(type.label, NamedTextColor.GOLD),
            null,
            true,
            false,
            DialogAfterAction.CLOSE,
            emptyList(),
            listOf(
                DialogInput.SingleOption(
                    AMOUNT_KEY,
                    260,
                    type.range.map { multiplier ->
                        DialogInput.SingleOption.Option(
                            multiplier.toString(),
                            Component.text("${multiplier}x — ${type.price(multiplier)} gems", NamedTextColor.WHITE),
                            multiplier == type.range.first,
                        )
                    },
                    Component.text("One hour", NamedTextColor.GRAY),
                    true,
                ),
            ),
        ),
        DialogActionButton(
            Component.text("Continue", NamedTextColor.GREEN),
            null,
            120,
            DialogAction.DynamicCustom(BOOST_SELECT_ACTION, tokenPayload(token)),
        ),
        DialogActionButton(
            Component.text("Cancel", NamedTextColor.RED),
            null,
            120,
            DialogAction.DynamicCustom(CANCEL_ACTION, tokenPayload(token)),
        ),
    )

private fun confirmDialog(
    token: String,
    product: String,
    cost: Long,
): Dialog.Confirmation =
    Dialog.Confirmation(
        DialogMetadata(
            Component.text("Confirm purchase", NamedTextColor.GOLD),
            null,
            true,
            false,
            DialogAfterAction.CLOSE,
            listOf(DialogBody.PlainMessage(Component.text("$product will cost $cost gems.", NamedTextColor.WHITE), 260)),
            emptyList(),
        ),
        DialogActionButton(
            Component.text("Confirm", NamedTextColor.GREEN),
            null,
            120,
            DialogAction.DynamicCustom(
                if (product.startsWith("Paste")) PASTE_CONFIRM_ACTION else BOOST_CONFIRM_ACTION,
                tokenPayload(token),
            ),
        ),
        DialogActionButton(
            Component.text("Cancel", NamedTextColor.RED),
            null,
            120,
            DialogAction.DynamicCustom(CANCEL_ACTION, tokenPayload(token)),
        ),
    )

private fun tokenPayload(token: String): CompoundBinaryTag = CompoundBinaryTag.builder().putString(TOKEN_KEY, token).build()

private class GemCommand(
    private val repository: GemRepository,
) : Command("gem", "gems.admin") {
    init {
        val action =
            net.minestom.server.command.builder.arguments.ArgumentType
                .Word("action")
                .from("give", "take")
        val balanceAction =
            net.minestom.server.command.builder.arguments.ArgumentType
                .Literal("balance")
        val transactionsAction =
            net.minestom.server.command.builder.arguments.ArgumentType
                .Literal("transactions")
        val player =
            net.minestom.server.command.builder.arguments.ArgumentType
                .Word("player")
        val amount =
            net.minestom.server.command.builder.arguments.ArgumentType
                .Long("amount")
                .min(1)
        player.setSuggestionCallback { _, _, suggestion ->
            repository.playersMatching(suggestion.input.substringAfterLast(' ')).forEach {
                suggestion.addEntry(
                    net.minestom.server.command.builder.suggestion
                        .SuggestionEntry(it.name),
                )
            }
        }
        setSenderDefaultExecutor { sender, _ ->
            sender.sendMessage(
                Component.text(
                    "Usage: /gem balance <player> | /gem transactions <player> | /gem <give|take> <player> <amount>",
                    NamedTextColor.LIGHT_PURPLE,
                ),
            )
        }
        addSenderSyntax("gems.admin", { sender, context ->
            val target = repository.findPlayer(context[player])
            if (target == null) {
                sender.sendMessage(Component.text("No gem account exists for '${context[player]}'.", NamedTextColor.RED))
                return@addSenderSyntax
            }
            sender.sendMessage(Component.text("${target.name}'s gem balance: ${target.balance}", NamedTextColor.GREEN))
        }, balanceAction, player)
        addSenderSyntax("gems.admin", { sender, context ->
            val target = repository.findPlayer(context[player])
            if (target == null) {
                sender.sendMessage(Component.text("No gem account exists for '${context[player]}'.", NamedTextColor.RED))
                return@addSenderSyntax
            }
            val transactions = repository.transactions(target.uuid)
            if (transactions.isEmpty()) {
                sender.sendMessage(Component.text("${target.name} has no gem transactions.", NamedTextColor.YELLOW))
                return@addSenderSyntax
            }
            sender.sendMessage(Component.text("Latest gem transactions for ${target.name}:", NamedTextColor.GOLD))
            transactions.forEach { transaction ->
                val timestamp = transactionTimeFormat.format(Instant.ofEpochMilli(transaction.createdAt))
                sender.sendMessage(
                    Component.text(
                        "$timestamp | ${transaction.product} | ${transaction.amount} gems | ${transaction.id}",
                        NamedTextColor.GRAY,
                    ),
                )
            }
        }, transactionsAction, player)
        addSenderSyntax("gems.admin", { sender, context ->
            val target = repository.findPlayer(context[player])
            if (target == null) {
                sender.sendMessage(Component.text("No gem account exists for '${context[player]}'.", NamedTextColor.RED))
                return@addSenderSyntax
            }
            val delta = if (context[action] == "give") context[amount] else -context[amount]
            val balance = repository.adjust(target.uuid, delta)
            if (balance == null) {
                sender.sendMessage(Component.text("${target.name} does not have enough gems.", NamedTextColor.RED))
                return@addSenderSyntax
            }
            sender.sendMessage(
                Component.text(
                    "${if (delta > 0) "Gave" else "Took"} ${kotlin.math.abs(
                        delta,
                    )} gems ${if (delta > 0) "to" else "from"} ${target.name}. Balance: $balance",
                    NamedTextColor.GREEN,
                ),
            )
        }, action, player, amount)
    }
}

private class GemShopCommand : Command("gemshop") {
    init {
        setDefaultExecutor { player, _ -> Gems.openShop(player) }
    }
}
