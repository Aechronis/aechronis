package net.aechronis.watchdog

import net.aechronis.watchdog.objects.TranslationProbeCodec
import net.kyori.adventure.nbt.BinaryTagTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationProbeTest {
    @Test
    fun `sign payload contains the probe component and fallback lines`() {
        val data = TranslationProbeCodec.signData("item.minecraft.diamond")
        val messages = data.getCompound("front_text").getList("messages", BinaryTagTypes.STRING)

        assertEquals(
            "{\"translate\":\"item.minecraft.diamond\",\"fallback\":\"faild\"}",
            messages.getString(0),
        )
        assertEquals("{\"text\":\"faild\"}", messages.getString(1))
        assertFalse(data.getCompound("front_text").getBoolean("has_glowing_text"))
    }

    @Test
    fun `fallback is not resolved`() {
        assertFalse(TranslationProbeCodec.isResolved("faild"))
        assertFalse(TranslationProbeCodec.isResolved(" faild "))
        assertFalse(TranslationProbeCodec.isResolved("{\"text\":\"faild\"}"))
        assertTrue(TranslationProbeCodec.isResolved("Diamond"))
    }

    @Test
    fun `result only exposes forbidden resolved keys`() {
        val result =
            TranslationProbeCodec.result(
                resolvedKeys = listOf("allowed.key", "bad.key"),
                forbiddenKeys = setOf("bad.key"),
            )

        assertEquals(listOf("allowed.key", "bad.key"), result.resolvedKeys)
        assertEquals(listOf("bad.key"), result.forbiddenKeys)
    }
}
