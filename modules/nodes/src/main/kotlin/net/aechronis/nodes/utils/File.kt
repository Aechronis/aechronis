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
    if (Files.exists(path)) {
        try {
            val numString = String(Files.readAllBytes(path))
            try {
                val num = numString.toLong()
                return num
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    return null
}
