package com.ai.assistance.operit.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandExitMarkerTest {
    @Test
    fun parsesSuccessfulAndFailedStatuses() {
        assertEquals(0, CommandExitMarker.parse("\u001B[2K${CommandExitMarker.PREFIX}123e4567-e89b-12d3-a456-426614174000:0\u001B[2K"))
        assertEquals(127, CommandExitMarker.parse("${CommandExitMarker.PREFIX}123e4567-e89b-12d3-a456-426614174000:127"))
        assertEquals(-9, CommandExitMarker.parse("${CommandExitMarker.PREFIX}123e4567-e89b-12d3-a456-426614174000:-9"))
    }

    @Test
    fun rejectsMalformedOrUnrelatedOutput() {
        assertNull(CommandExitMarker.parse("${CommandExitMarker.PREFIX}not-a-command:0"))
        assertNull(CommandExitMarker.parse("ordinary output"))
        assertNull(CommandExitMarker.parse("${CommandExitMarker.PREFIX}123e4567-e89b-12d3-a456-426614174000:not-a-number"))
    }

    @Test
    fun visibleCommandProtocolEndsMarkerOnItsOwnCrLfLine() {
        val input = buildCommandWithExitMarkerProtocol(
            command = "dpkg --configure -a",
            commandId = "123e4567-e89b-12d3-a456-426614174000",
        )

        assertTrue(input.contains("${CommandExitMarker.PREFIX}123e4567-e89b-12d3-a456-426614174000:"))
        assertTrue(input.contains("\\033[2K\\r\\n"))
        assertFalse(input.contains("\\033[2K\\r'"))
    }
}
