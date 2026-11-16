package com.ouor.localchat.ui.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/** FB-6 custom limit input and labels. */
class LimitInputTest {

    @Test fun `commas are dropped and range is inclusive`() {
        assertEquals(LimitInput.Ok(100_000), parseLimit("100,000", CONTEXT_RANGE))
        assertEquals(LimitInput.Ok(1_024), parseLimit(" 1024 ", CONTEXT_RANGE))
        assertEquals(LimitInput.Ok(10_000_000), parseLimit("10,000,000", CONTEXT_RANGE))
        assertEquals(LimitInput.Ok(256), parseLimit("256", OUTPUT_RANGE))
    }

    @Test fun `empty, non numbers and out of range`() {
        assertEquals(LimitInput.Empty, parseLimit("", CONTEXT_RANGE))
        assertEquals(LimitInput.Empty, parseLimit(" , ", CONTEXT_RANGE))
        assertEquals(LimitInput.NotNumber, parseLimit("12k", CONTEXT_RANGE))
        assertEquals(LimitInput.NotNumber, parseLimit("-5", CONTEXT_RANGE))
        assertEquals(LimitInput.NotNumber, parseLimit("1.5", CONTEXT_RANGE))
        assertEquals(LimitInput.OutOfRange, parseLimit("1023", CONTEXT_RANGE))
        assertEquals(LimitInput.OutOfRange, parseLimit("10000001", CONTEXT_RANGE))
        assertEquals(LimitInput.OutOfRange, parseLimit("255", OUTPUT_RANGE))
        assertEquals(LimitInput.OutOfRange, parseLimit("99999999999999999999", OUTPUT_RANGE))
    }

    @Test fun `m-3 m-4 any locale's thousands grouping is accepted, ASCII digits only`() {
        assertEquals(LimitInput.Ok(1_024), parseLimit("1.024", CONTEXT_RANGE)) // de
        assertEquals(LimitInput.Ok(1_048_576), parseLimit("1 048 576", CONTEXT_RANGE)) // fr, plain space
        assertEquals(LimitInput.Ok(1_048_576), parseLimit("1 048 576", CONTEXT_RANGE)) // fr, narrow NBSP as printed
        assertEquals(LimitInput.Ok(1_024), parseLimit("1'024", CONTEXT_RANGE)) // ch
        assertEquals(LimitInput.NotNumber, parseLimit("１２８０", CONTEXT_RANGE)) // full-width digits
        assertEquals(LimitInput.NotNumber, parseLimit("1.0245", CONTEXT_RANGE))
        assertEquals(LimitInput.NotNumber, parseLimit("1,024.000", CONTEXT_RANGE))
    }

    @Test fun `presets use K = 1024 and other numbers are exact`() {
        assertEquals("128K", limitLabel(131_072, CONTEXT_PRESETS))
        assertEquals("200K", limitLabel(204_800, CONTEXT_PRESETS))
        assertEquals("1M", limitLabel(1_048_576, CONTEXT_PRESETS))
        assertEquals("128,000", limitLabel(128_000, CONTEXT_PRESETS))
        assertEquals("3,072", limitLabel(3_072, OUTPUT_PRESETS))
        assertEquals(9, CONTEXT_PRESETS.size)
        assertEquals(8, OUTPUT_PRESETS.size)
    }
}
