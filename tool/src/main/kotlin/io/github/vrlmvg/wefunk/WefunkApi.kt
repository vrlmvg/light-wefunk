package io.github.vrlmvg.wefunk

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

private const val SITE = "https://www.wefunkradio.com"

/** Fetches WEFUNK's public pages and builds stream URLs the same way its web player does. */
object WefunkApi {
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
            }
        }
    }

    suspend fun latestShows(): ShowPage = WefunkParser.parseShowList(get("/shows/"))

    /** Archive pages hold 50 shows each, e.g. page 1250 = shows 1250–1299. */
    suspend fun archive(page: Int): ShowPage = WefunkParser.parseShowList(get("/shows/$page"))

    suspend fun show(number: Int): Show = WefunkParser.parseShow(get("/show/$number"))

    suspend fun radioNow(): RadioNow = WefunkParser.parseRadio(get("/radio/json2"), System.currentTimeMillis())

    const val RADIO_STREAM_URL = "$SITE/play/radio.mp3"

    /**
     * Redirects to a signed .m4a on one of WEFUNK's mirrors. Like the site, ask for high quality AAC
     * first; the signature is valid for 6 hours.
     */
    fun showStreamUrl(number: Int): String {
        val token = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val expires = System.currentTimeMillis() / 1000 + 6 * 60 * 60
        return "$SITE/mirror/stream/$number?formats=hq-m4a,oq-m4a,oq-mp3&tokenid=$token&exp=$expires"
    }

    private val random = SecureRandom()

    private suspend fun get(path: String): String {
        val response = client.get(SITE + path) { header("User-Agent", USER_AGENT) }
        if (!response.status.isSuccess()) throw WefunkException("WEFUNK returned HTTP ${response.status.value}.")
        return response.bodyAsText()
    }

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Light Phone III) WEFUNK-LightTool/0.1"
}
