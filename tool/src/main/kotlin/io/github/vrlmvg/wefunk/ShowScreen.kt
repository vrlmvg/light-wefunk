package io.github.vrlmvg.wefunk

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ShowViewModel(private val number: Int) : LightViewModel<Unit>() {
    val show = MutableStateFlow<Show?>(null)
    val failed = MutableStateFlow(false)

    init { load() }

    fun load() {
        failed.value = false
        viewModelScope.launch {
            runCatching { WefunkApi.show(number) }
                .onSuccess { show.value = it }
                .onFailure { failed.value = true }
        }
    }
}

/** A show's description and playlist; tap a track to start the show there. */
class ShowScreen(sealedActivity: SealedLightActivity, private val number: Int) : LightScreen<Unit, ShowViewModel>(sealedActivity) {
    override val viewModelClass: Class<ShowViewModel>
        get() = ShowViewModel::class.java

    override fun createViewModel() = ShowViewModel(number)

    private fun play(show: Show, fromMs: Long) {
        WefunkPlayer.playShow(show, fromMs)
        navigateTo(screenFactory = { PlayerScreen(it) })
    }

    @Composable
    override fun Content() {
        val show by viewModel.show.collectAsState()
        val failed by viewModel.failed.collectAsState()
        val playing by WefunkPlayer.playing.collectAsState()
        val position by WefunkPlayer.positionMs.collectAsState()

        WefunkTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar("Show $number", onBack = { goBack() })
                val s = show
                when {
                    s == null && failed -> CenteredMessage("Couldn't load this show.", "TRY AGAIN", viewModel::load)
                    s == null -> CenteredMessage("Loading…")
                    else -> {
                        val nowPlaying = (playing as? Playing.ShowPlayback)?.show?.number == s.number
                        val currentIndex = if (nowPlaying) s.trackIndexAt(position) else -1
                        LightScrollView(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
                        ) {
                            LightText(formatDate(s.date) + " · " + formatTime(s.durationMs), variant = LightTextVariant.Detail, lighten = true)
                            if (s.description.isNotBlank()) {
                                LightText(
                                    s.description,
                                    variant = LightTextVariant.Detail,
                                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp(), end = 1f.gridUnitsAsDp()),
                                )
                            }
                            SectionLabel("Playlist")
                            s.tracks.forEachIndexed { i, track ->
                                ListRow(
                                    title = track.text,
                                    subtitle = formatTime(track.startMs) + if (i == currentIndex) " · now playing" else "",
                                    onClick = { play(s, track.startMs) },
                                    emphasised = i == currentIndex,
                                )
                            }
                        }
                        LightBottomBar(
                            listOf(LightBarButton.Text(if (nowPlaying) "NOW PLAYING" else "PLAY SHOW", onClick = {
                                if (nowPlaying) navigateTo(screenFactory = { PlayerScreen(it) }) else play(s, 0)
                            })),
                        )
                    }
                }
            }
        }
    }
}
