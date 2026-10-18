package io.github.vrlmvg.wefunk

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fixtures are real wefunkradio.com pages saved on 2026-10-17. */
class WefunkParserTest {
    private fun fixture(name: String) = File("src/test/resources/$name").readText()

    @Test
    fun latestShows() {
        val page = WefunkParser.parseShowList(fixture("shows-latest.html"))
        assertEquals(1310, page.shows.first().number)
        assertEquals("2026-10-09", page.shows.first().date)
        assertTrue(page.shows.first().description.startsWith("DJ Dacel gets us in the zone"))
        assertEquals(page.shows.map { it.number }.sortedDescending(), page.shows.map { it.number })
        assertTrue(1250 in page.olderPages && 150 in page.olderPages)
    }

    @Test
    fun archivePageDecodesEntities() {
        val page = WefunkParser.parseShowList(fixture("shows-1250.html"))
        assertEquals(50, page.shows.size)
        assertEquals(1299, page.shows.first().number)
        assertEquals(1250, page.shows.last().number)
        val knicks = page.shows.first().description
        assertTrue("Like & Phonte" in knicks && "Knicks'" in knicks, knicks)
    }

    @Test
    fun showPlaylistAlignsWithTimes() {
        val show = WefunkParser.parseShow(fixture("show-1310.html"))
        assertEquals(1310, show.number)
        assertEquals("2026-10-09", show.date)
        assertEquals(7231817, show.durationMs)
        assertEquals("intro", show.tracks[0].text)
        assertEquals(0, show.tracks[0].startMs)
        assertEquals("curió curió - bem querer", show.tracks[1].text)
        assertEquals(92473, show.tracks[1].startMs)
        assertEquals("talk (over fela kuti - water get no enemy (j.m. edit))", show.tracks[2].text)
        assertEquals(1, show.trackIndexAt(100_000))
        assertEquals(show.tracks.size - 1, show.trackIndexAt(show.durationMs))
    }

    @Test
    fun oldShowsUseTheSameFormat() {
        val show = WefunkParser.parseShow(fixture("show-500.html"))
        assertEquals(500, show.number)
        assertEquals("2008-01-04", show.date)
        assertEquals(31, show.tracks.size)
        assertTrue(show.tracks.none { it.text.startsWith("track ") })
    }

    @Test
    fun radioNowPlaying() {
        val radio = WefunkParser.parseRadio(fixture("radio-json2.json"), fetchedAtMs = 1_000)
        assertTrue(radio.show.number > 0)
        assertTrue(radio.show.tracks.isNotEmpty())
        assertEquals(radio.positionMs + 5_000, radio.positionAt(6_000))
        val current = radio.show.tracks[radio.show.trackIndexAt(radio.positionMs)]
        assertTrue(current.text.isNotBlank())
    }
}
