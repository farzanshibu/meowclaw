package com.farzanshibu.meowclaw.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.CoroutineContext

/**
 * Runs blocking [block] on [context] and calls [onCancel] the moment the
 * caller is cancelled, so the block can be interrupted (cancel an HTTP call,
 * stop a native generation). `invokeOnCompletion` fires too late for this: a
 * job only completes once the blocking call inside it has already returned.
 */
internal suspend fun <T> interruptible(
    context: CoroutineContext,
    onCancel: () -> Unit,
    block: suspend CoroutineScope.() -> T,
): T = coroutineScope {
    val work = async(context, block = block)
    try {
        work.await()
    } catch (e: CancellationException) {
        onCancel()
        throw e
    }
}
