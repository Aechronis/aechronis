/**
 * Utils for file io.
 */
package net.aechronis.nodes.utils

import java.nio.file.Files
import java.nio.file.Path

/**
 * Load long number from file
 */
fun loadLongFromFile(path: Path): Long? {
    if (!Files.exists(path)) {
        return null
    }

    return try {
        Files.readString(path).toLong()
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
