// RGB color object

package luna.nodes.utils

data class Color(val r: Int, val g: Int, val b: Int)

object ChatColor {
    /**
     * Represents black
     */
    var BLACK = "§0"

    /**
     * Represents dark blue
     */
    var DARK_BLUE = "§1"

    /**
     * Represents dark green
     */
    var DARK_GREEN = "§2"

    /**
     * Represents dark blue (aqua)
     */
    var DARK_AQUA = "§3"

    /**
     * Represents dark red
     */
    var DARK_RED = "§4"

    /**
     * Represents dark purple
     */
    var DARK_PURPLE = "§5"

    /**
     * Represents gold
     */
    var GOLD = "§6"

    /**
     * Represents gray
     */
    var GRAY = "§7"

    /**
     * Represents dark gray
     */
    var DARK_GRAY = "§8"

    /**
     * Represents blue
     */
    var BLUE = "§9"

    /**
     * Represents green
     */
    var GREEN = "§a"

    /**
     * Represents aqua
     */
    var AQUA = "§b"

    /**
     * Represents red
     */
    var RED = "§c"

    /**
     * Represents light purple
     */
    var LIGHT_PURPLE = "§d"

    /**
     * Represents yellow
     */
    var YELLOW = "§e"

    /**
     * Represents white
     */
    var WHITE = "§f"

    /**
     * Represents magical characters that change around randomly
     */
    var MAGIC = "§k"

    /**
     * Makes the text bold.
     */
    var BOLD = "§l"

    /**
     * Makes a line appear through the text.
     */
    var STRIKETHROUGH = "§m"

    /**
     * Makes the text appear underlined.
     */
    var UNDERLINE = "§n"

    /**
     * Makes the text italic.
     */
    var ITALIC = "§o"

    /**
     * Resets all previous chat colors or formats.
     */
    var RESET = "§r"
}