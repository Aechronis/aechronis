/**
 * Utils for file io.
 */
package luna.nodes.utils

import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Future

/**
 * Synchronously save string to file from given path.
 */
fun saveStringToFile(str: String, path: Path) {
    val buffer = ByteBuffer.wrap(str.toByteArray())
    val fileChannel: AsynchronousFileChannel = AsynchronousFileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    val operation: Future<Int> = fileChannel.write(buffer, 0)
    operation.get()
}

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
