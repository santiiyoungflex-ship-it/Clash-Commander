package com.ambix.assistant.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudLabelParserTest {
    @Test
    fun parsesBothCommonElixirSpellings() {
        assertEquals(0, parseElixirLabel("0 Elixir"))
        assertEquals(6, parseElixirLabel("6 Elixer"))
        assertEquals(10, parseElixirLabel("10 Elixir"))
        assertEquals(4, parseElixirLabel("Elixir 4"))
        assertEquals(8, parseElixirLabel("elixer 8"))
    }

    @Test
    fun rejectsOutOfRangeAndUnrelatedLabels() {
        assertNull(parseElixirLabel("11 Elixir"))
        assertNull(parseElixirLabel("Hog Rider"))
        assertNull(parseElixirLabel("Elixir"))
    }
}
