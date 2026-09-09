package com.ai.assistance.operit.terminal.ui

import com.ai.assistance.operit.terminal.data.SSHAuthType
import com.ai.assistance.operit.terminal.data.SSHConfig
import org.junit.Assert.*
import org.junit.Test

class SSHConfigDraftTest {
    private val draft = SSHConfigDraft(
        " example.test ", "22", " user ", SSHAuthType.PASSWORD, " password ", "", "",
        true, "30", false, "8881", "2223", "android", "abcdefghijklmnop",
    )

    @Test fun rejectsInvalidPortsAndHeartbeatOverflow() {
        for (value in listOf("0", "65536", "-1", "22.5", "2147483648", "")) {
            assertTrue(SSHConfigField.PORT in draft.copy(port = value).errors)
            assertTrue(SSHConfigField.REMOTE_PORT in draft.copy(enableReverseTunnel = true, remoteTunnelPort = value).errors)
            assertTrue(SSHConfigField.LOCAL_PORT in draft.copy(enableReverseTunnel = true, localSshPort = value).errors)
        }
        assertTrue(SSHConfigField.KEEP_ALIVE in draft.copy(keepAliveInterval = "2147484").errors)
        assertTrue(draft.copy(keepAliveInterval = "2147483", port = "65535").errors.isEmpty())
    }

    @Test fun preservesUnexposedForwardingAndDisabledGroupValues() {
        val original = SSHConfig("old.test", username = "old", authType = SSHAuthType.PASSWORD,
            enablePortForwarding = false, localForwardPort = 9010, remoteForwardPort = 9011,
            keepAliveInterval = 75, remoteTunnelPort = 9000, localSshPort = 9001,
            localSshUsername = "original", localSshPassword = "original-password")
        val saved = draft.copy(enableKeepAlive = false, keepAliveInterval = "invalid",
            remoteTunnelPort = "invalid", localSshPort = "invalid").toConfig(original)
        assertFalse(saved.enablePortForwarding)
        assertEquals(9010, saved.localForwardPort)
        assertEquals(9011, saved.remoteForwardPort)
        assertEquals(75, saved.keepAliveInterval)
        assertEquals(9000, saved.remoteTunnelPort)
        assertEquals(9001, saved.localSshPort)
        assertEquals("original", saved.localSshUsername)
        assertEquals("original-password", saved.localSshPassword)
    }

    @Test fun preservesPasswordWhitespaceAndNormalizesAddress() {
        val saved = draft.toConfig(null)
        assertEquals(" password ", saved.password)
        assertEquals("example.test", saved.host)
        assertEquals("user", saved.username)
    }

    @Test fun keyAuthenticationClearsPasswordAndPreservesPassphrase() {
        val saved = draft.copy(authType = SSHAuthType.PUBLIC_KEY, password = "",
            privateKeyPath = "/keys/my key", passphrase = " secret ").toConfig(null)
        assertNull(saved.password)
        assertEquals("/keys/my key", saved.privateKeyPath)
        assertEquals(" secret ", saved.passphrase)
    }

    @Test fun rejectsControlCharactersAndShortLocalCredentials() {
        assertTrue(SSHConfigField.HOST in draft.copy(host = "host\nname").errors)
        assertTrue(SSHConfigField.USERNAME in draft.copy(username = "user name").errors)
        assertTrue(SSHConfigField.PASSWORD in draft.copy(password = "a\u0000b").errors)
        assertTrue(SSHConfigField.LOCAL_PASSWORD in draft.copy(enableReverseTunnel = true, localSshPassword = "short").errors)
    }
}
