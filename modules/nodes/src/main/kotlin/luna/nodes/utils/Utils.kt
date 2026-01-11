/**
 * Misc utils.
 */

package luna.nodes.utils

// check restrictions on string inputs
fun stringInputIsValid(s: String, maxLength: Int = 32): Boolean {
    if (s.length > maxLength) {
        return false
    }

    if (s.contains("\"") || s.contains("{") || s.contains("}")) {
        return false
    }

    return true
}

// escape format string characters
fun sanitizeString(s: String): String {
    var sEscaped = s.replace("$", "$$")
    sEscaped = sEscaped.replace("%", "%%")
    sEscaped = sEscaped.replace("\n", "")
    sEscaped = sEscaped.replace("\"", "")
    sEscaped = sEscaped.replace("{", "")
    sEscaped = sEscaped.replace("}", "")

    return sEscaped
}
