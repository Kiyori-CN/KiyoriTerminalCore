package com.ai.assistance.operit.terminal.utils

import com.ai.assistance.operit.terminal.data.MirrorSource
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceManagerTest {
    private val sources =
        listOf(
            MirrorSource("default", "Default", "https://example.test", true),
            MirrorSource("custom", "Custom", "https://custom.example.test", true),
        )

    @Test
    fun preservesKnownSelectedSource() {
        assertEquals("custom", resolveSelectedSourceId("custom", "default", sources))
    }

    @Test
    fun repairsMissingSelectedSourceToCatalogDefault() {
        assertEquals("default", resolveSelectedSourceId("removed", "default", sources))
        assertEquals("default", resolveSelectedSourceId(null, "default", sources))
    }
}
