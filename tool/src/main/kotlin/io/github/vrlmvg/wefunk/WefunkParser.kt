package io.github.vrlmvg.wefunk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class ShowSummary(
    val number: Int,
    /** ISO date, e.g. 2026-10-09. */
    val date: String,
    val description: String,
)

data class Track(
    val startMs: Long,
    /** WEFUNK's own wording: "artist - title", "intro", "talk (over artist - title)". */
    val text: String,
)

data class Show(
    val number: Int,
    val date: String,
    val description: String,
    val durationMs: Long,
    val tracks: List<Track>,
) {
    fun trackIndexAt(positionMs: Long): Int = tracks.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
}

/** What the 24/7 radio stream is playing, as of [fetchedAtMs]. */
data class RadioNow(
    val show: Show,
    val positionMs: Long,
    val fetchedAtMs: Long,
    /** The show the stream plays after [show]; the same response carries its playlist. */
    val next: Show? = null,
) {
    fun positionAt(nowMs: Long): Long = positionMs + (nowMs - fetchedAtMs)

    /** The show and position playing at [nowMs], or null once both known shows have ended. */
    fun at(nowMs: Long): LiveMoment? {
        val position = positionAt(nowMs)
        if (show.durationMs <= 0 || position < show.durationMs) return LiveMoment(show, position)
        val next = next ?: return null
        val nextPosition = position - show.durationMs
        return if (next.durationMs <= 0 || nextPosition < next.durationMs) LiveMoment(next, nextPosition) else null
    }

    /**
     * When this response stops describing the stream: after the next show ends. Without
     * durations to go on, check again in a few minutes.
     */
    val refreshAtMs: Long
        get() {
            val nextDuration = next?.durationMs ?: 0
            if (show.durationMs <= 0 || nextDuration <= 0) return fetchedAtMs + FALLBACK_REFRESH_MS
            return fetchedAtMs + (show.durationMs - positionMs) + nextDuration
        }

    private companion object {
        const val FALLBACK_REFRESH_MS = 5 * 60_000L
    }
}

data class LiveMoment(val show: Show, val positionMs: Long) {
    val trackIndex: Int get() = show.trackIndexAt(positionMs)
    val track: Track? get() = show.tracks.getOrNull(trackIndex)
    val nextTrack: Track? get() = show.tracks.getOrNull(trackIndex + 1)
}

/** An archive page and the numbers of the other archive pages it links to. */
data class ShowPage(val shows: List<ShowSummary>, val olderPages: List<Int>)

/** Reads WEFUNK's public web pages; there is no documented API. */
object WefunkParser {
    private val json = Json { ignoreUnknownKeys = true }

    private val SHOW_ITEM = Regex(
        """<a href="/show/(\d+)"[^>]*class="[^"]*show-item[^"]*"[^>]*id="sp_(\d{4}-\d{2}-\d{2})".*?show-description-content">(.*?)</span>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val ARCHIVE_LINK = Regex("""href="/shows/(\d+)"""")
    private val TRACKS_VAR = Regex("""var tracks\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
    private val PLAYLIST_ITEM = Regex("""<li data-plid="\d+_\w+">\s*<div class="content">(.*?)</div>\s*<div class="context">""", RegexOption.DOT_MATCHES_ALL)
    private val OG_DESCRIPTION = Regex("""<meta property="og:description" content="([^"]*)"""")
    private val TITLE_NUMBER = Regex("""<title>WEFUNK Show (\d+)</title>""")

    fun parseShowList(html: String): ShowPage {
        val shows = SHOW_ITEM.findAll(html).map { m ->
            ShowSummary(
                number = m.groupValues[1].toInt(),
                date = m.groupValues[2],
                description = text(m.groupValues[3]),
            )
        }.distinctBy { it.number }.toList()
        val pages = ARCHIVE_LINK.findAll(html).map { it.groupValues[1].toInt() }.distinct().sortedDescending().toList()
        return ShowPage(shows, pages)
    }

    fun parseShow(html: String): Show {
        val tracksJson = TRACKS_VAR.find(html)?.groupValues?.get(1) ?: throw WefunkException("No playlist on this show page.")
        val data = json.parseToJsonElement(tracksJson) as JsonObject
        val starts = (data["tracks"] as? JsonArray).orEmpty().map {
            (it as JsonObject)["mspos"]?.jsonPrimitive?.longOrNull ?: 0L
        }
        val labels = PLAYLIST_ITEM.findAll(html).map { text(it.groupValues[1]) }.toList()
        val tracks = starts.mapIndexed { i, start -> Track(start, labels.getOrNull(i) ?: "track ${i + 1}") }
        val number = data["shownum"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: TITLE_NUMBER.find(html)?.groupValues?.get(1)?.toInt()
            ?: throw WefunkException("Unknown show number.")
        return Show(
            number = number,
            date = data["showdate"]?.jsonPrimitive?.contentOrNull ?: "",
            description = OG_DESCRIPTION.find(html)?.groupValues?.get(1)?.let(::text) ?: "",
            durationMs = data["mstotal"]?.jsonPrimitive?.longOrNull ?: (starts.lastOrNull() ?: 0L),
            tracks = tracks,
        )
    }

    fun parseRadio(body: String, fetchedAtMs: Long): RadioNow {
        val root = json.parseToJsonElement(body) as JsonObject
        val stream = root["stream"] as? JsonObject ?: throw WefunkException("Radio info unavailable.")
        val date = stream["current_showdate"]?.jsonPrimitive?.contentOrNull ?: throw WefunkException("Radio info unavailable.")
        val showInfo = root["showinfo"] as? JsonObject
        val info = showInfo?.get(date) as? JsonObject ?: throw WefunkException("Radio info unavailable.")
        val nextDate = stream["next_showdate"]?.jsonPrimitive?.contentOrNull
        val nextInfo = nextDate?.let { showInfo[it] as? JsonObject }
        return RadioNow(
            show = radioShow(date, info, stream["current_duration_ms"]?.jsonPrimitive?.longOrNull),
            positionMs = stream["current_position_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            fetchedAtMs = fetchedAtMs,
            next = if (nextDate != null && nextInfo != null) radioShow(nextDate, nextInfo, null) else null,
        )
    }

    private fun radioShow(date: String, info: JsonObject, durationMs: Long?): Show {
        val tracks = (info["playlist"] as? JsonArray).orEmpty().map { element ->
            val o = element as JsonObject
            Track(
                startMs = o["mspos"]?.jsonPrimitive?.longOrNull ?: 0L,
                text = o["item_text"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }
        val number = info["show_link"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('/')?.toIntOrNull()
            ?: ((info["prev_shownum"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) + 1)
        return Show(
            number = number,
            date = date,
            description = "",
            durationMs = durationMs ?: info["durationms"]?.jsonPrimitive?.longOrNull ?: 0L,
            tracks = tracks,
        )
    }

    /** Strips tags and decodes the HTML entities WEFUNK pages use. */
    internal fun text(html: String): String {
        val noTags = html.replace(Regex("<[^>]+>"), "")
        val decoded = Regex("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);").replace(noTags) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> {
                    val code = if (e.startsWith("#x") || e.startsWith("#X")) e.drop(2).toInt(16) else e.drop(1).toInt()
                    String(Character.toChars(code))
                }
            }
        }
        return decoded.replace(Regex("\\s+"), " ").trim()
    }

    internal fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
}

class WefunkException(message: String) : Exception(message)
