package com.ouor.localchat.ui.components

import com.ouor.localchat.core.model.ChatErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentRulesTest {

    @Test fun `gauge levels`() {
        assertEquals(GaugeLevel.Normal, gaugeLevel(null))
        assertEquals(GaugeLevel.Normal, gaugeLevel(59))
        assertEquals(GaugeLevel.Warn, gaugeLevel(60))
        assertEquals(GaugeLevel.Warn, gaugeLevel(80))
        assertEquals(GaugeLevel.High, gaugeLevel(81))
    }

    @Test fun `gauge percent rounds up so a small share of a 1M window is not 0`() {
        assertEquals(1, gaugePercent(405, 1_048_576))
        assertEquals(0, gaugePercent(0, 1_048_576))
        assertEquals(50, gaugePercent(500, 1000))
        assertEquals(100, gaugePercent(5000, 1000))
        assertEquals(null, gaugePercent(405, null))
        assertEquals(null, gaugePercent(null, 1000))
    }

    @Test fun `gauge text`() {
        assertEquals("12%", gaugeText(12, false, null))
        assertEquals("~18%", gaugeText(18, true, null))
        assertEquals("1.2k tokens", gaugeText(null, false, "1.2k tokens"))
    }

    @Test fun `reasoning chip label falls back above six chars`() {
        assertEquals("high", reasoningChipLabel("high", "추론"))
        assertEquals("추론", reasoningChipLabel("extended", "추론"))
        assertEquals("추론", reasoningChipLabel(null, "추론"))
    }

    @Test fun `segmented fits only short small sets`() {
        assertTrue(segmentedFits(listOf("끔", "low", "high")))
        assertFalse(segmentedFits(listOf("only")))
        assertFalse(segmentedFits(listOf("a", "b", "c", "d", "e", "f")))
        assertFalse(segmentedFits(listOf("minimal", "low")))
    }

    @Test fun `every error kind maps to an action`() {
        ChatErrorKind.entries.forEach { errorPresentation(it) }
        assertEquals(ErrorAction.ChangeKey, errorPresentation(ChatErrorKind.AUTH).action)
        assertEquals(ErrorAction.TopUp, errorPresentation(ChatErrorKind.BALANCE).action)
        assertEquals(ErrorAction.SummarizeNow, errorPresentation(ChatErrorKind.CONTEXT_OVERFLOW).action)
        assertEquals(ErrorAction.Retry, errorPresentation(ChatErrorKind.BAD_REQUEST).action)
        assertEquals(ErrorAction.OpenModelSettings, errorPresentation(ChatErrorKind.BAD_REQUEST, usedOptionalFeatures = true).action)
    }
}
