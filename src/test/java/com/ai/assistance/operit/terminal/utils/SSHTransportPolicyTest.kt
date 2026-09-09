package com.ai.assistance.operit.terminal.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SSHTransportPolicyTest {
    @Test fun explicitDirectRouteDoesNotUseUserProxyCommand() {
        assertEquals("-o ProxyCommand=none", SSHTransportPolicy.proxyOption(null))
    }

    @Test fun proxyRouteUsesSocksRemoteDnsAndBoundedHandshake() {
        val option = SSHTransportPolicy.proxyOption(SSHTransportPolicy.Endpoint("127.0.0.1", 12345))
        assertTrue(option.contains("/usr/bin/python3"))
        assertTrue(option.contains("timeout=20"))
        assertTrue(option.contains("12345 %h %p"))
        assertTrue(option.contains("encode("))
        assertTrue(option.contains("select.select"))
    }

    @Test fun routingFailurePropagatesWithoutDirectFallback() = runBlocking {
        val original = SSHTransportPolicy.resolveProxy
        try {
            SSHTransportPolicy.resolveProxy = { _, _ -> throw IllegalStateException("proxy unavailable") }
            try {
                SSHTransportPolicy.openSshProxyOption("example.test", 22)
                fail("Expected route failure")
            } catch (expected: IllegalStateException) {
                assertEquals("proxy unavailable", expected.message)
            }
        } finally { SSHTransportPolicy.resolveProxy = original }
    }
}
