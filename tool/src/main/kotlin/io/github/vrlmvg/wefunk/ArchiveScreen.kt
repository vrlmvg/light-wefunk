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
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ArchiveViewModel : LightViewModel<Unit>() {
    /** null = list of page ranges; otherwise the shows on that archive page. */
    val page = MutableStateFlow<Int?>(null)
    val ranges = MutableStateFlow<List<Int>>(emptyList())
    val shows = MutableStateFlow<List<ShowSummary>?>(null)
    val failed = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            runCatching { WefunkApi.latestShows() }
                .onSuccess { ranges.value = it.olderPages }
                .onFailure { failed.value = true }
        }
    }

    fun openPage(start: Int) {
        page.value = start
        shows.value = null
        failed.value = false
        viewModelScope.launch {
            runCatching { WefunkApi.archive(start) }
                .onSuccess { shows.value = it.shows }
                .onFailure { failed.value = true }
        }
    }

    override fun onBackPressed(): Boolean {
        if (page.value != null) {
            page.value = null
            failed.value = false
            return true
        }
        return false
    }
}

class ArchiveScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, ArchiveViewModel>(sealedActivity) {
    override val viewModelClass: Class<ArchiveViewModel>
        get() = ArchiveViewModel::class.java

    override fun createViewModel() = ArchiveViewModel()

    @Composable
    override fun Content() {
        val page by viewModel.page.collectAsState()
        val ranges by viewModel.ranges.collectAsState()
        val shows by viewModel.shows.collectAsState()
        val failed by viewModel.failed.collectAsState()

        WefunkTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(page?.let { "Shows $it–${it + 49}" } ?: "Archive", onBack = { goBack() })
                when {
                    failed -> CenteredMessage("Couldn't reach WEFUNK. Check your connection.")
                    page == null && ranges.isEmpty() -> CenteredMessage("Loading…")
                    page != null && shows == null -> CenteredMessage("Loading…")
                    else -> LightScrollView(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 1f.gridUnitsAsDp()),
                    ) {
                        if (page == null) {
                            ranges.forEach { start ->
                                ListRow("Shows $start–${start + 49}", null, onClick = { viewModel.openPage(start) })
                            }
                        } else {
                            shows!!.forEach { show ->
                                ListRow("Show ${show.number}", formatDate(show.date), onClick = {
                                    navigateTo(screenFactory = { ShowScreen(it, show.number) })
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}
