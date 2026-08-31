package com.ai.assistance.operit.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandExitMarkerCommandIdTest {
    @Test
    fun onlyAcceptsTheMarkerForTheCurrentlyExecutingCommand() {
        val commandId = "123e4567-e89b-12d3-a456-426614174000"
        val marker = "\u001B]1337;" + CommandExitMarker.PREFIX + commandId + ":0\u0007"

        assertNull(
            CommandExitMarker.parse(
                marker,
                expectedCommandId = "123e4567-e89b-12d3-a456-426614174001",
            )
        )
        assertEquals(0, CommandExitMarker.parse(marker, expectedCommandId = commandId))
    }
}
