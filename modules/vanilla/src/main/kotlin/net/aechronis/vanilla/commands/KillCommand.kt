package net.aechronis.vanilla.commands

class KillCommand : Command("kill", "vanilla.kill") {
    init {
        setDefaultExecutor { player, _ ->
            player.kill()
        }
    }
}
