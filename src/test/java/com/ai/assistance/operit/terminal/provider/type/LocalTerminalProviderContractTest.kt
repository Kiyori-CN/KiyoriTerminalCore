package com.ai.assistance.operit.terminal.provider.type

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTerminalProviderContractTest {
    @Test
    fun hiddenExecutorBootstrapsUbuntuAndKeepsReadingFromPipe() {
        val command = LocalTerminalProvider.HIDDEN_EXEC_LOGIN_COMMAND

        assertTrue(command.contains("install_ubuntu"))
        assertTrue(command.contains("configure_sources"))
        assertTrue(command.contains("fix_permissions"))
        assertTrue(command.contains("/bin/bash --noprofile --norc -s"))
        assertFalse(command.contains("/bin/bash --noprofile --norc'"))
    }
}
