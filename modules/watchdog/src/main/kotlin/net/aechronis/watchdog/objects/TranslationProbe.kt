package net.aechronis.watchdog.objects

internal data class TranslationProbe(
    val resolvedKeys: List<String>,
    val forbiddenKeys: List<String>,
)
