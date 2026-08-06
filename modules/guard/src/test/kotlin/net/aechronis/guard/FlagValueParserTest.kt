package net.aechronis.guard

import net.aechronis.guard.flags.BooleanFlagValue
import net.aechronis.guard.flags.DecimalFlagValue
import net.aechronis.guard.flags.FlagValueParser
import net.aechronis.guard.flags.IntegerFlagValue
import net.aechronis.guard.flags.NumberListFlagValue
import net.aechronis.guard.flags.StringFlagValue
import net.aechronis.guard.flags.StringListFlagValue
import kotlin.test.Test
import kotlin.test.assertEquals

class FlagValueParserTest {
    @Test
    fun `parses supported flag values`() {
        assertEquals(BooleanFlagValue(true), FlagValueParser.parse("true"))
        assertEquals(StringFlagValue("woah"), FlagValueParser.parse("woah"))
        assertEquals(StringListFlagValue(listOf("woah", "wow")), FlagValueParser.parse("woah,wow"))
        assertEquals(NumberListFlagValue(listOf(1.0, 2.0, 4.0, 5.0)), FlagValueParser.parse("1,2,4,5"))
        assertEquals(IntegerFlagValue(12), FlagValueParser.parse("12"))
        assertEquals(DecimalFlagValue(1.25), FlagValueParser.parse("1.25"))
    }
}
