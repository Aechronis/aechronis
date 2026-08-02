package net.aechronis.watchdog.checks

import net.aechronis.watchdog.objects.FlagType
import net.minestom.server.entity.Player

internal typealias FlagSink = (Player, FlagType, Double, String) -> Unit
