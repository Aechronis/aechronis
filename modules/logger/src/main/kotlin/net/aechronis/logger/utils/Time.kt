package net.aechronis.logger.utils

import java.time.Duration

internal fun formatAgo(duration: Duration): String {
    val seconds = duration.seconds
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> "${seconds / 86400}d"
    }
}
