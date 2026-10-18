package io.github.vrlmvg.wefunk

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay

/** Now playing: current track, progress and transport controls. */
class PlayerScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val playing by WefunkPlayer.playing.collectAsState()
        val position by WefunkPlayer.positionMs.collectAsState()
        val isPlaying by WefunkPlayer.isPlaying.collectAsState()

        WefunkTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                when (val p = playing) {
                    null -> {
                        TopBar("Now playing", onBack = { goBack() })
                        CenteredMessage("Nothing is playing.")
                    }
                    Playing.Radio -> RadioContent(isPlaying)
                    is Playing.ShowPlayback -> ShowContent(p.show, position, isPlaying)
                }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.ShowContent(show: Show, position: Long, isPlaying: Boolean) {
        val index = show.trackIndexAt(position)
        val track = show.tracks.getOrNull(index)
        val next = show.tracks.getOrNull(index + 1)
        TopBar("Show ${show.number}", onBack = { goBack() })
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(formatDate(show.date), variant = LightTextVariant.Detail, lighten = true)
            LightText(
                track?.text ?: "",
                variant = LightTextVariant.Heading,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
            next?.let {
                LightText("Next: ${it.text}", variant = LightTextVariant.Detail, lighten = true, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()))
            }
            Spacer(Modifier.weight(1f))
            LightTouchableProgressBar(
                colors = LightThemeTokens.colors,
                progress = if (show.durationMs > 0) (position.toFloat() / show.durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction -> WefunkPlayer.playShow(show, (fraction * show.durationMs).toLong()) },
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 0.5f.gridUnitsAsDp(), bottom = 1f.gridUnitsAsDp())) {
                LightText(formatTime(position), variant = LightTextVariant.Detail, lighten = true)
                Spacer(Modifier.weight(1f))
                LightText(
                    "Playlist",
                    variant = LightTextVariant.Detail,
                    underline = true,
                    modifier = Modifier.lightClickable { navigateTo(screenFactory = { ShowScreen(it, show.number) }) },
                )
                Spacer(Modifier.weight(1f))
                LightText(formatTime(show.durationMs), variant = LightTextVariant.Detail, lighten = true)
            }
        }
        LightBottomBar(
            listOf(
                LightBarButton.LightIcon(LightIcons.REWIND, onClick = { WefunkPlayer.skipTrack(-1) }, contentDescription = "Previous track"),
                LightBarButton.LightIcon(LightIcons.SKIP_BACKWARD_FIFTEEN, onClick = { WefunkPlayer.seekBy(-15_000) }, contentDescription = "Back 15 seconds"),
                playPause(isPlaying),
                LightBarButton.LightIcon(LightIcons.SKIP_FORWARD_FIFTEEN, onClick = { WefunkPlayer.seekBy(15_000) }, contentDescription = "Forward 15 seconds"),
                LightBarButton.LightIcon(LightIcons.FAST_FORWARD, onClick = { WefunkPlayer.skipTrack(1) }, contentDescription = "Next track"),
            ),
        )
    }

    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.RadioContent(isPlaying: Boolean) {
        var radio by remember { mutableStateOf<RadioNow?>(null) }
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            var lastFetch = 0L
            while (true) {
                now = System.currentTimeMillis()
                if (now - lastFetch > 30_000) {
                    runCatching { WefunkApi.radioNow() }.onSuccess { radio = it }
                    lastFetch = now
                }
                delay(1_000)
            }
        }
        TopBar("Wefunk Radio", onBack = { goBack() })
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
            val r = radio
            if (r == null) {
                LightText("Live stream", variant = LightTextVariant.Detail, lighten = true)
            } else {
                val index = r.show.trackIndexAt(r.positionAt(now))
                LightText("Live · Show ${r.show.number} from ${formatDate(r.show.date)}", variant = LightTextVariant.Detail, lighten = true)
                LightText(
                    r.show.tracks.getOrNull(index)?.text ?: "",
                    variant = LightTextVariant.Heading,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                r.show.tracks.getOrNull(index + 1)?.let {
                    LightText("Next: ${it.text}", variant = LightTextVariant.Detail, lighten = true, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()))
                }
            }
        }
        LightBottomBar(
            listOf(
                LightBarButton.LightIcon(LightIcons.STOP, onClick = { WefunkPlayer.stop(); goBack() }, contentDescription = "Stop"),
                playPause(isPlaying),
                null,
            ),
        )
    }

    private fun playPause(isPlaying: Boolean) = LightBarButton.LightIcon(
        icon = if (isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
        onClick = WefunkPlayer::togglePause,
        contentDescription = if (isPlaying) "Pause" else "Play",
    )
}
