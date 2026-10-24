package io.github.vrlmvg.wefunk

import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.LightPlayerSpec
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/** What is loaded in the player. */
sealed interface Playing {
    data object Radio : Playing
    data class ShowPlayback(val show: Show) : Playing
}

/**
 * The tool's single detached player (the SDK allows one per process). Playback lives in the SDK's
 * media service, so it keeps going when you leave the tool; this object only remembers what's loaded.
 */
object WefunkPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: LightAudioPlayer? = null
    /** Automatic reconnects since playback last ran; reset by playing or by the user starting something. */
    private var retries = 0

    private val _playing = MutableStateFlow<Playing?>(null)
    val playing: StateFlow<Playing?> = _playing.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val positionMs: StateFlow<Long> get() = player?.positionMs ?: MutableStateFlow(0L)
    val isPlaying: StateFlow<Boolean> get() = player?.isPlaying ?: MutableStateFlow(false)

    /** Connects to (or creates) the detached session and works out what it is playing. */
    fun attach(audio: LightAudio) {
        if (player != null) return
        val p = audio.newPlayer(LightPlayerSpec(playback = LightAudioPlayback.Detached))
        player = p
        scope.launch {
            if (!p.awaitReady()) return@launch
            _ready.value = true
            if (p.currentMediaItemIndex.value != NO_MEDIA_ITEM && _playing.value == null) restore(p)
            launch { p.isPlaying.collect { if (it) retries = 0 } }
            p.error.collect { error ->
                // Network drops, and signed show URLs expire after 6 hours: reconnect with a fresh URL and
                // carry on where we were. Backs off and gives up after a few tries so a dead mirror or
                // no connection doesn't turn into a tight retry loop.
                if (error?.kind != LightAudioErrorKind.Source || retries >= MAX_RETRIES) return@collect
                retries++
                val at = p.positionMs.value
                delay(RETRY_DELAY_MS * retries)
                when (val current = _playing.value) {
                    Playing.Radio -> {
                        p.setMediaQueue(listOf(radioItem()))
                        p.play()
                    }
                    is Playing.ShowPlayback -> {
                        p.replaceMediaItem(0, showItem(current.show))
                        p.play()
                        seekWhenLoaded(p, at)
                    }
                    null -> Unit
                }
            }
        }
    }

    fun playRadio() {
        val p = player ?: return
        retries = 0
        _playing.value = Playing.Radio
        p.setMediaQueue(listOf(radioItem()))
        p.play()
    }

    private fun radioItem() = LightAudioItem(
        id = RADIO_ID,
        source = LightAudioSource.UrlSource(WefunkApi.RADIO_STREAM_URL),
        metadata = LightMediaMetadata(title = "Wefunk Radio", artist = "Live stream"),
    )

    fun playShow(show: Show, fromMs: Long = 0) {
        val p = player ?: return
        retries = 0
        val current = _playing.value
        if (current is Playing.ShowPlayback && current.show.number == show.number) {
            p.seekTo(fromMs)
            p.play()
            return
        }
        _playing.value = Playing.ShowPlayback(show)
        p.setMediaQueue(listOf(showItem(show)))
        p.play()
        if (fromMs > 0) seekWhenLoaded(p, fromMs)
    }

    /** A seek sent before the stream has loaded is dropped, so wait until it reports a duration. */
    private fun seekWhenLoaded(p: LightAudioPlayer, toMs: Long) {
        scope.launch {
            withTimeoutOrNull(20_000) { p.durationMs.first { it > 0 } } ?: return@launch
            p.seekTo(toMs)
        }
    }

    fun togglePause() {
        val p = player ?: return
        if (p.isPlaying.value) p.pause() else p.play()
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val show = (_playing.value as? Playing.ShowPlayback)?.show ?: return
        p.seekTo((p.positionMs.value + deltaMs).coerceIn(0, show.durationMs))
    }

    /** Jumps to the start of the next (+1) or previous (-1) playlist entry. */
    fun skipTrack(direction: Int) {
        val p = player ?: return
        val show = (_playing.value as? Playing.ShowPlayback)?.show ?: return
        if (show.tracks.isEmpty()) return
        val position = p.positionMs.value
        val index = show.trackIndexAt(position)
        val target = when {
            direction > 0 -> index + 1
            // Like a CD player: "previous" restarts the current track unless you're near its start.
            position - show.tracks[index].startMs > 5_000 -> index
            else -> index - 1
        }
        show.tracks.getOrNull(target)?.let { p.seekTo(it.startMs) }
    }

    fun stop() {
        player?.stop()
        _playing.value = null
    }

    private suspend fun restore(p: LightAudioPlayer) {
        val id = p.queue.value.firstOrNull()?.id ?: return
        _playing.value = when {
            id == RADIO_ID -> Playing.Radio
            id.startsWith(SHOW_ID_PREFIX) -> id.removePrefix(SHOW_ID_PREFIX).toIntOrNull()
                ?.let { runCatching { WefunkApi.show(it) }.getOrNull() }
                ?.let { Playing.ShowPlayback(it) }
            else -> null
        }
    }

    private fun showItem(show: Show) = LightAudioItem(
        id = SHOW_ID_PREFIX + show.number,
        source = LightAudioSource.UrlSource(WefunkApi.showStreamUrl(show.number)),
        metadata = LightMediaMetadata(title = "WEFUNK Show ${show.number}", artist = formatDate(show.date), durationMs = show.durationMs),
    )

    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 3_000L
    private const val RADIO_ID = "wefunk-radio"
    private const val SHOW_ID_PREFIX = "wefunk-show-"
}
