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
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HomeViewModel : LightViewModel<Unit>() {
    val latest = MutableStateFlow<ShowPage?>(null)
    val radio = MutableStateFlow<RadioNow?>(null)
    val loadError = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private var radioPoll: Job? = null

    init { load() }

    fun load() {
        loadError.value = false
        viewModelScope.launch {
            runCatching { WefunkApi.latestShows() }
                .onSuccess { latest.value = it }
                .onFailure { loadError.value = true }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        radioPoll?.cancel()
        radioPoll = viewModelScope.launch {
            while (isActive) {
                runCatching { WefunkApi.radioNow() }.onSuccess { radio.value = it }
                delay(30_000)
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        radioPoll?.cancel()
    }

    fun dismissMessage() { message.value = null }

    /**
     * Show numbers have gaps (the archive starts around 168), so pick a random archive page and
     * then a show that is actually listed on it.
     */
    fun pickRandomShow(open: (Int) -> Unit) {
        val latestPage = latest.value ?: return
        viewModelScope.launch {
            runCatching {
                val pages = latestPage.olderPages
                val page = if (pages.isEmpty() || (0..pages.size).random() == 0) latestPage else WefunkApi.archive(pages.random())
                page.shows.random().number
            }.onSuccess(open).onFailure { message.value = "Couldn't reach WEFUNK. Check your connection." }
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {
    init {
        WefunkPlayer.attach(DefaultLightAudio(sealedActivity))
    }

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel()

    private fun openShow(number: Int) = navigateTo(screenFactory = { ShowScreen(it, number) })
    private fun openArchive() = navigateTo(screenFactory = { ArchiveScreen(it) })
    private fun openPlayer() = navigateTo(screenFactory = { PlayerScreen(it) })

    private fun listenToRadio() {
        WefunkPlayer.playRadio()
        openPlayer()
    }

    private fun randomShow() = viewModel.pickRandomShow(::openShow)

    @Composable
    override fun Content() {
        val latest by viewModel.latest.collectAsState()
        val radio by viewModel.radio.collectAsState()
        val loadError by viewModel.loadError.collectAsState()
        val message by viewModel.message.collectAsState()
        val playing by WefunkPlayer.playing.collectAsState()

        WefunkTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar("Wefunk Radio", onBack = null)
                when {
                    latest == null && loadError -> CenteredMessage("Couldn't reach WEFUNK. Check your connection.", "TRY AGAIN", viewModel::load)
                    latest == null -> CenteredMessage("Loading…")
                    else -> LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 1f.gridUnitsAsDp()),
                    ) {
                        SectionLabel("Radio")
                        ListRow(
                            title = "Listen live",
                            subtitle = radio?.let { r ->
                                r.show.tracks.getOrNull(r.show.trackIndexAt(r.positionAt(System.currentTimeMillis())))?.text
                            } ?: "Continuous stream of WEFUNK shows",
                            onClick = ::listenToRadio,
                        )
                        SectionLabel("Latest shows")
                        latest!!.shows.forEach { show ->
                            ListRow("Show ${show.number}", formatDate(show.date), onClick = { openShow(show.number) })
                        }
                        ListRow("Older shows", "Browse the full archive", onClick = ::openArchive)
                    }
                }
                LightBottomBar(
                    listOf(
                        LightBarButton.LightIcon(LightIcons.SHUFFLE, onClick = ::randomShow, contentDescription = "Random show"),
                        if (playing != null) {
                            LightBarButton.LightIcon(LightIcons.MEDIA, onClick = ::openPlayer, contentDescription = "Now playing")
                        } else {
                            null
                        },
                    ),
                )
            }
            message?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissMessage) }
        }
    }
}
