package io.github.vrlmvg.wefunk

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The radio schedule, shared by every screen. One /radio/json2 response describes the current
 * show and the next one, so it is only fetched again once both have played (WEFUNK asked us not
 * to poll it).
 */
object WefunkRadio {
    private val mutex = Mutex()
    private var cached: RadioNow? = null
    private var retryAtMs = 0L

    /** What's on the stream at [nowMs], fetching only when the cached schedule has run out. */
    suspend fun at(nowMs: Long = System.currentTimeMillis()): LiveMoment? = mutex.withLock {
        val current = cached
        if (current != null && nowMs < current.refreshAtMs) return current.at(nowMs)
        if (nowMs < retryAtMs) return current?.at(nowMs)
        runCatching { WefunkApi.radioNow() }
            .onSuccess { cached = it; retryAtMs = 0 }
            .onFailure { retryAtMs = nowMs + RETRY_AFTER_FAILURE_MS }
        cached?.at(nowMs)
    }

    private const val RETRY_AFTER_FAILURE_MS = 60_000L
}
