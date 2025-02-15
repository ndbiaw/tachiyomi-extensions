package eu.kanade.tachiyomi.extension.es.leercapitulo

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.es.leercapitulo.dto.MangaDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.nio.charset.Charset
import kotlin.random.Random

class LeerCapitulo : ParsedHttpSource(), ConfigurableSource {
    override val name = "LeerCapitulo"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val isCi = System.getenv("CI") == "true"

    override val baseUrl
        get() = when {
            isCi -> MIRRORS.joinToString("#, ")
            else -> _baseUrl
        }

    private val _baseUrl = run {
        val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
        val mirrors = MIRRORS
        var index = preferences.getString(MIRROR_PREF, "-1")!!.toInt()
        if (index !in mirrors.indices) {
            index = Random.nextInt(0, mirrors.size)
            preferences.edit().putString(MIRROR_PREF, index.toString()).apply()
        }
        mirrors[index]
    }

    // Popular
    override fun popularMangaRequest(page: Int): Request =
        GET(baseUrl, headers)

    override fun popularMangaSelector(): String =
        ".hot-manga > .thumbnails > a"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.attr("title")

        thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
    }

    override fun popularMangaNextPageSelector(): String? =
        null

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search-autocomplete".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)

        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val mangas = json.decodeFromString<List<MangaDto>>(response.body.string()).map {
            SManga.create().apply {
                setUrlWithoutDomain(it.link)
                title = it.label
                thumbnail_url = baseUrl + it.thumbnail
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    override fun searchMangaSelector(): String =
        throw UnsupportedOperationException("Not used.")

    override fun searchMangaFromElement(element: Element): SManga =
        throw UnsupportedOperationException("Not used.")

    override fun searchMangaNextPageSelector(): String? =
        null

    // Latest
    override fun latestUpdatesRequest(page: Int): Request =
        popularMangaRequest(page)

    override fun latestUpdatesSelector(): String =
        ".mainpage-manga"

    override fun latestUpdatesFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst(".media-body > a")!!.attr("abs:href"))
        title = element.selectFirst("h4")!!.text()
        thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
    }

    override fun latestUpdatesNextPageSelector(): String? =
        null

    // Details
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")!!.text()

        val altNames = document.selectFirst(".description-update > span:contains(Títulos Alternativos:) + :matchText")?.text()
        val desc = document.selectFirst("#example2")!!.text()
        description = when (altNames) {
            null -> desc
            else -> "$desc\n\nAlt name(s): $altNames"
        }

        genre = document.select(".description-update a[href^='/genre/']").joinToString { it.text() }
        status = document.selectFirst(".description-update > span:contains(Estado:) + :matchText")!!.text().toStatus()
        thumbnail_url = document.selectFirst(".cover-detail > img")!!.attr("abs:src")
    }

    // Chapters
    override fun chapterListSelector(): String =
        ".chapter-list > ul > li"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val a = element.selectFirst("a.xanh")!!
        setUrlWithoutDomain(a.attr("abs:href"))
        name = a.text()
        chapter_number = name
            .substringAfter("Capitulo ")
            .substringBefore(":")
            .toFloatOrNull()
            ?: -1f
    }

    // Pages
    override fun pageListParse(document: Document): List<Page> {
        val orderList = document.selectFirst("meta[property=ad:check]")?.attr("content")
            ?.replace("[^\\d]+".toRegex(), "-")
            ?.split("-")

        val arrayData = document.selectFirst("#arraydata")!!.text()

        val encodedUrls = arrayData.replace(Regex("[A-Z0-9]", RegexOption.IGNORE_CASE)) {
            val index = "BoevQJylkhcAxZVb4sXTgfaHu3zr7tP61L9qGNUmCpYKREdijD28OSwWInF5M0".indexOf(it.value)
            "GQiMUkPrKIgpzVcu87AY4LnvO2a0oBy9JFbRhSx6qdfme5wZsEWlT1CN3XjDHt"[index].toString()
        }

        val urlList = String(Base64.decode(encodedUrls, Base64.DEFAULT), Charset.forName("UTF-8")).split(",")

        val sortedUrls = orderList?.map { urlList[it.reversed().toInt()] }?.reversed() ?: urlList

        return sortedUrls.mapIndexed { i, image_url ->
            Page(i, imageUrl = image_url)
        }
    }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not used.")

    // Other
    private fun String.toStatus() = when (this) {
        "Ongoing" -> SManga.ONGOING
        "Paused" -> SManga.ON_HIATUS
        "Completed" -> SManga.COMPLETED
        "Cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            val mirrors = MIRRORS

            key = MIRROR_PREF
            title = "Mirror"
            summary = "%s\nRequires restart to take effect"
            entries = mirrors
            entryValues = Array(mirrors.size, Int::toString)
        }.let(screen::addPreference)
    }

    companion object {
        private const val MIRROR_PREF = "MIRROR"
        private val MIRRORS get() = arrayOf("https://www.leercapitulo.com", "https://olympusscan.top")
    }
}
