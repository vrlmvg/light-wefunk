package io.github.vrlmvg.wefunk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

@Composable
fun WefunkTheme(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) { content() }
    }
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?) {
    LightTopBar(
        leftButton = onBack?.let { LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = it, contentDescription = "Back") },
        center = LightTopBarCenter.Text(title),
        modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
    )
}

@Composable
fun ColumnScope.CenteredMessage(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText(text, variant = LightTextVariant.Copy, align = TextAlign.Center)
        if (action != null && onAction != null) {
            LightText(
                text = action,
                variant = LightTextVariant.Button,
                underline = true,
                modifier = Modifier
                    .padding(top = 1.5f.gridUnitsAsDp())
                    .lightClickable(onClick = onAction)
                    .padding(0.5f.gridUnitsAsDp()),
            )
        }
    }
}

/** Small lightened heading above a group of rows. */
@Composable
fun SectionLabel(text: String) {
    LightText(text, variant = LightTextVariant.Detail, lighten = true, modifier = Modifier.padding(top = 1f.gridUnitsAsDp()))
}

/** List entry: primary line, then a smaller lightened line (LightOS hierarchy). */
@Composable
fun ListRow(title: String, subtitle: String?, onClick: (() -> Unit)?, emphasised: Boolean = false, maxSubtitleLines: Int = 1) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.lightClickable(onClick = onClick) else Modifier)
            .padding(vertical = 0.6f.gridUnitsAsDp()),
    ) {
        LightText(
            title,
            variant = if (emphasised) LightTextVariant.Heading else LightTextVariant.Copy,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            LightText(it, variant = LightTextVariant.Detail, lighten = true, maxLines = maxSubtitleLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/** 2026-10-09 → October 9, 2026 */
fun formatDate(iso: String): String {
    val parts = iso.split('-').mapNotNull { it.toIntOrNull() }
    if (parts.size != 3 || parts[1] !in 1..12) return iso
    return "${MONTHS[parts[1] - 1]} ${parts[2]}, ${parts[0]}"
}

/** 75 000 ms → 1:15, 3 723 000 ms → 1:02:03 */
fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
