package com.ai.assistance.operit.terminal.utils

import com.ai.assistance.operit.terminal.TerminalEnvironmentContract
import com.ai.assistance.operit.terminal.data.MirrorSource
import org.junit.Assert.*
import org.junit.Test

class MirrorSourcePolicyTest {
    @Test fun acceptsHttpAndHttpsIncludingIpv6AndEncodedPaths() {
        for (url in listOf("https://example.test/simple", "http://[::1]:8080/packages/", "https://example.test/a%20b")) {
            assertTrue(url, validMirrorSourceUrl(url))
        }
    }

    @Test fun rejectsInvalidOrConfigurationBreakingUrls() {
        for (url in listOf("", "example.test", "file:///etc", 
             "https://example.test:65536/",
            "https://example.test/a\nb", "https://example.test/a b", "https://example.test/a\"b", "https://example.test/a\\b")) {
            assertFalse(url, validMirrorSourceUrl(url))
        }
    }

    @Test fun preservesAuthenticatedUrlsButRedactsTheirListLabel() {
        val url = "https://user:password@example.test/simple?token=secret#fragment"
        assertTrue(validMirrorSourceUrl(url))
        assertEquals("https://example.test/simple…", mirrorSourceDisplayUrl(url))
    }

    @Test fun quotesShellMetacharactersInRustEnvironmentValues() {
        val url = "https://example.test/a'\u0024HOME;value/"
        val command = rustSourceEnvironmentCommand(MirrorSource("custom", "User mirror", url, true))
        assertEquals("export RUSTUP_DIST_SERVER=" + TerminalEnvironmentContract.shellQuote(url.trimEnd('/')) +
            "\nexport RUSTUP_UPDATE_ROOT=" + TerminalEnvironmentContract.shellQuote(url.trimEnd('/') + "/rustup"), command)
    }

    @Test fun localConfigurationUsesQuotedPayloadAndPathsWithoutMirrorNameInterpolation() {
        val source = MirrorSource("custom", "EOF; arbitrary name", "https://example.test/a'b", true)
        val command = localSourceConfigurationCommand(source, source, source, "resolute")
        assertFalse(command.contains(source.name))
        assertTrue(command.contains(TerminalEnvironmentContract.shellQuote("index-url = \"${source.url}\"")))
        assertTrue(command.contains("\"\u0024UBUNTU_PATH/etc/apt/sources.list\""))
        assertTrue(command.contains("resolute-security"))
    }
}
