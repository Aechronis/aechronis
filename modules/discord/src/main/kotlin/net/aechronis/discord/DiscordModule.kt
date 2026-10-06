package net.aechronis.discord

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.aechronis.server.modules.AechronisModule
import net.aechronis.server.modules.ModuleCommands
import net.aechronis.server.modules.ModuleContext
import java.nio.file.Path

class DiscordModule : AechronisModule {
    override val id = "discord"
    override val dependencies = setOf("nodes", "luckperms")

    private var job: Job? = null

    override fun initialize(context: ModuleContext) {
        val token = System.getenv("DISCORD_BOT_TOKEN")?.trim().orEmpty()
        if (token.isEmpty()) {
            println("[Discord] Disabled: set DISCORD_BOT_TOKEN to enable the bot")
            return
        }
        val guild = System.getenv("DISCORD_GUILD_ID")?.trim()?.takeIf { it.isNotEmpty() }
        val guildId = guild?.toULongOrNull()?.takeIf { it > 0u }?.let(::Snowflake)
        if (guild != null && guildId == null) {
            System.err.println("[Discord] Disabled: DISCORD_GUILD_ID must be a positive Discord server ID")
            return
        }

        // Invalid storage disables the integration instead of silently discarding account links.
        val links = AccountLinks(Path.of("discord", "accounts.json"))
        ModuleCommands.register(DiscordAccountCommand(links))
        val commands = NodesCommands(links)
        val supervisor = SupervisorJob()
        job = supervisor
        val scope = CoroutineScope(supervisor + Dispatchers.IO + CoroutineName("discord"))
        scope.launch { DiscordBot.run(token, guildId, scope, links, commands) }
    }

    override fun prepareForShutdown(context: ModuleContext) = stop()

    override fun shutdown(context: ModuleContext) = stop()

    private fun stop() {
        val active = job ?: return
        // Cancel suspended tick reads before waiting, since reload may have paused gameplay.
        runBlocking {
            withTimeout(30_000) { active.cancelAndJoin() }
        }
        job = null
    }
}
