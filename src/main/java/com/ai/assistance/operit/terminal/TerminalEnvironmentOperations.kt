package com.ai.assistance.operit.terminal

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex

internal class TerminalEnvironmentMaintenanceException : IllegalStateException("Terminal environment maintenance is in progress")

/** 初始化/创建进程/隐藏执行与环境维护共用一个边界；维护前取消并等待在途操作退出。 */
internal class TerminalEnvironmentOperations(private val settleTimeoutMs: Long = 10_000L) {
    private val lock = Any()
    private val maintenanceMutex = Mutex()
    private val active = mutableSetOf<Job>()
    private var maintaining = false

    suspend fun <T> run(block: suspend () -> T): T = coroutineScope {
        // 单独的子 Job 确保维护只取消这次终端操作，不取消调用方随后执行的其他工作。
        val operation = currentCoroutineContext().job
        synchronized(lock) {
            if (maintaining) throw TerminalEnvironmentMaintenanceException()
            active.add(operation)
        }
        try { block() } finally { synchronized(lock) { active.remove(operation) } }
    }

    suspend fun <T> maintain(waitForCurrent: Boolean = false, block: suspend () -> T): T {
        if (waitForCurrent) maintenanceMutex.lock()
        else if (!maintenanceMutex.tryLock()) throw TerminalEnvironmentMaintenanceException()
        val pending = synchronized(lock) {
            maintaining = true
            active.toList()
        }
        try {
            pending.forEach { it.cancel() }
            check(withTimeoutOrNull(settleTimeoutMs) { pending.joinAll(); true } == true) {
                "Terminal operations did not stop; environment files have not been removed"
            }
            return block()
        } finally {
            synchronized(lock) { maintaining = false }
            maintenanceMutex.unlock()
        }
    }
}
