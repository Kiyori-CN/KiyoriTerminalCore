package com.ai.assistance.operit.terminal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderedTerminalInputTest {
    @Test fun laterKeysWaitForEarlierWriteToFinish() = runBlocking {
        val input = OrderedTerminalInput(this)
        val releaseFirst = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val written = mutableListOf<String>()
        input.submit { firstStarted.complete(Unit); releaseFirst.await(); written += "a" }
        val last = input.submit { written += "b" }
        firstStarted.await()
        assertEquals(emptyList<String>(), written)
        releaseFirst.complete(Unit)
        last.join()
        assertEquals(listOf("a", "b"), written)
    }

    @Test fun rapidSubmissionsPreserveOrderOnIoPool() = runBlocking {
        val owner = kotlinx.coroutines.CoroutineScope(coroutineContext + Dispatchers.IO)
        val input = OrderedTerminalInput(owner)
        val written = mutableListOf<Int>()
        var last: kotlinx.coroutines.Job? = null
        repeat(100) { value -> last = input.submit { written += value } }
        last!!.join()
        assertEquals((0 until 100).toList(), written)
    }
}
