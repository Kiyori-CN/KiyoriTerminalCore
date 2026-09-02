package com.ai.assistance.operit.terminal.view.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputAssemblyTest {
    @Test
    fun preservesWhitespaceAndLineBoundariesAcrossHistoryPages() {
        assertEquals(
            "  first\nsecond  \n  tail\n",
            assembleCommandHistoryOutput(
                pages = listOf("  first\nsecond  "),
                tail = "  tail\n",
            )
        )
    }
}
