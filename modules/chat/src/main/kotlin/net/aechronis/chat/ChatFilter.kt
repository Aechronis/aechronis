package net.aechronis.chat

import net.aechronis.server.io.AtomicFiles
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import java.nio.file.Files
import java.nio.file.Path

/**
 * Blocks chat containing slurs, hard swears or sexual terms. Words match whole tokens after leetspeak and
 * punctuation are normalized, so "f.u.c.k" and "fuuuck" are caught but "class" or "cockpit" are not.
 */
object ChatFilter {
    private val DEFAULT_WORDS =
        listOf(
            // slurs
            "nigger",
            "nigga",
            "faggot",
            "fag",
            "retard",
            "tranny",
            "chink",
            "spic",
            "kike",
            "wetback",
            "gook",
            "coon",
            "beaner",
            "dyke",
            // hard swears
            "fuck",
            "motherfucker",
            "cunt",
            "bitch",
            "whore",
            "slut",
            // sexual
            "sex",
            "porn",
            "porno",
            "cum",
            "dildo",
            "blowjob",
            "handjob",
            "rape",
            "rapist",
            "horny",
            "anal",
            "boobs",
            "tits",
            "titties",
            "cock",
            "dick",
            "pussy",
            "penis",
            "vagina",
            "jerkoff",
            "orgasm",
        )

    private val SUFFIXES = listOf("", "s", "es", "ed", "er", "ers", "ing", "in")
    private val LEET = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's')

    private lateinit var file: Path
    private val words = sortedSetOf<String>()

    fun init(path: Path) {
        file = path
        synchronized(words) {
            words.clear()
            if (Files.exists(path)) {
                words += Files.readAllLines(path).map(::normalizeWord).filter(String::isNotEmpty)
            } else {
                words += DEFAULT_WORDS
                save()
            }
        }
    }

    fun list(): List<String> = synchronized(words) { words.toList() }

    /** Returns false when the word was already listed. */
    fun add(word: String): Boolean = edit { words.add(normalizeWord(word)) }

    /** Returns false when the word wasn't listed. */
    fun remove(word: String): Boolean = edit { words.remove(normalizeWord(word)) }

    /** The first listed word [message] contains, or null if it is clean. */
    fun match(message: String): String? {
        val snapshot = list()
        return message.split(Regex("\\s+")).map(::normalizeWord).firstNotNullOfOrNull { token ->
            snapshot.firstOrNull { matches(token, it) }
        }
    }

    /** Warns [player] and returns true when [message] must not be sent. */
    fun blocks(
        player: Player,
        message: String,
    ): Boolean {
        if (match(message) == null) return false
        player.sendMessage(Component.text("Your message was not sent: it contains blocked language.", NamedTextColor.RED))
        return true
    }

    internal fun normalizeWord(raw: String): String =
        raw
            .lowercase()
            .map { LEET[it] ?: it }
            .filter(Char::isLetter)
            .joinToString("")

    internal fun matches(
        token: String,
        word: String,
    ): Boolean {
        if (suffixes(word).any { token == word + it }) return true
        // Stretched spellings ("fuuuck"); skipped for short words so "as" never matches "ass".
        val squeezedWord = squeeze(word)
        return squeezedWord.length >= 4 && suffixes(word).any { squeeze(token) == squeezedWord + it }
    }

    // "-es" only follows sibilants (bitches), so "spic" never matches "spices".
    private fun suffixes(word: String) =
        if (word.endsWith("ch") ||
            word.endsWith("sh") ||
            word.last() in "sxz"
        ) {
            SUFFIXES
        } else {
            SUFFIXES - "es"
        }

    private fun squeeze(s: String): String = s.replace(Regex("(.)\\1+"), "$1")

    private fun edit(change: () -> Boolean): Boolean =
        synchronized(words) {
            change().also { if (it) save() }
        }

    private fun save() {
        Files.createDirectories(file.parent)
        AtomicFiles.writeString(file, words.joinToString("\n"))
    }
}
