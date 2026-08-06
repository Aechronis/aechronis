package net.aechronis.guard.flags

object FlagValueParser {
    fun parse(input: String): FlagValue {
        val value = input.trim()
        require(value.isNotEmpty()) { "Flag value cannot be empty" }

        if (value.equals("true", ignoreCase = true)) return BooleanFlagValue(true)
        if (value.equals("false", ignoreCase = true)) return BooleanFlagValue(false)

        if (',' in value) {
            val parts = value.split(',').map { it.trim() }
            require(parts.none(String::isEmpty)) { "Array flag values cannot contain empty entries" }
            val numbers = parts.mapNotNull { it.toDoubleOrNull() }
            return if (numbers.size == parts.size) NumberListFlagValue(numbers) else StringListFlagValue(parts)
        }

        value.toLongOrNull()?.let { return IntegerFlagValue(it) }
        value.toDoubleOrNull()?.let { return DecimalFlagValue(it) }
        return StringFlagValue(value)
    }
}
