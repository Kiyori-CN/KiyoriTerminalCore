package com.ai.assistance.operit.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** UI 按键必须保持触发顺序，不能依赖多个 IO launch 的调度顺序。 */
internal class OrderedTerminalInput(private val scope: CoroutineScope) {
    private var tail: Job? = null

    @Synchronized
    fun submit(write: suspend () -> Unit): Job {
        val previous = tail
        val next = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            write()
        }
        tail = next
        next.start()
        return next
    }
}
