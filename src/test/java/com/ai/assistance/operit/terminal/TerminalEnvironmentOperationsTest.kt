package com.ai.assistance.operit.terminal

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TerminalEnvironmentOperationsTest {
    @Test fun cancellationStaysInsideTheTerminalOperation() = runBlocking {
        val owner = TerminalEnvironmentOperations()
        val entered = CompletableDeferred<Unit>()
        val callerContinued = CompletableDeferred<Boolean>()
        val caller = launch {
            try { owner.run { entered.complete(Unit); awaitCancellation() } }
            catch (_: CancellationException) { callerContinued.complete(currentCoroutineContext().isActive) }
        }
        entered.await()
        owner.maintain { }
        assertTrue(callerContinued.await())
        caller.join()
    }

    @Test fun lifecycleShutdownCanWaitForExistingMaintenance() = runBlocking {
        val owner = TerminalEnvironmentOperations()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { owner.maintain { entered.complete(Unit); release.await() } }
        entered.await()
        val shutdown = async(start = CoroutineStart.UNDISPATCHED) { owner.maintain(waitForCurrent = true) { 9 } }
        assertFalse(shutdown.isCompleted)
        release.complete(Unit)
        first.join()
        assertEquals(9, shutdown.await())
    }

    @Test fun maintenanceWaitsForCancelledOperationCleanup() = runBlocking {
        val owner = TerminalEnvironmentOperations()
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        val operation = launch {
            owner.run {
                try { entered.complete(Unit); awaitCancellation() }
                finally { cleaned = true }
            }
        }
        entered.await()
        owner.maintain { assertTrue(cleaned) }
        operation.join()
        assertEquals(7, owner.run { 7 })
    }

    @Test fun rejectsNewOperationsAndDuplicateMaintenanceWhileExclusive() = runBlocking {
        val owner = TerminalEnvironmentOperations()
        owner.maintain {
            try { owner.run { fail("must not run") }; fail("must reject") }
            catch (_: IllegalStateException) { }
            try { owner.maintain { fail("must not run") }; fail("must reject") }
            catch (_: IllegalStateException) { }
        }
        assertEquals("ready", owner.run { "ready" })
    }

    @Test fun maintenanceFailureReleasesBoundary() = runBlocking {
        val owner = TerminalEnvironmentOperations()
        try { owner.maintain { throw IllegalStateException("write failed") }; fail("must throw") }
        catch (expected: IllegalStateException) { assertEquals("write failed", expected.message) }
        assertEquals(1, owner.run { 1 })
    }

    @Test fun timeoutDoesNotBeginDestructiveWork() = runBlocking {
        val owner = TerminalEnvironmentOperations(settleTimeoutMs = 20)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = launch {
            owner.run {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
            }
        }
        entered.await()
        try {
            owner.maintain { fail("must not delete while an operation remains") }
            fail("must report an unsettled operation")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("did not stop"))
        } finally { release.complete(Unit); operation.join() }
    }
}
