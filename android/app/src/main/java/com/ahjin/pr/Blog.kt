package com.ahjin.pr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the game's official blog to know the latest version, where to download it, and what the
 * post actually says — so the launcher can surface the author's own news instead of an empty screen.
 *
 * Uses Blogger's JSON feed instead of scraping the rendered page, which is much more stable.
 */
object Blog {
    private const val FEED = "https://pokemon-reloaded.blogspot.com/feeds/posts/default?alt=json"
    private const val PAGE = 10

    /** How many pages of posts are searched for a download before giving up (50 posts). */
    private const val MAX_PAGES = 5

    data class Link(val label: String, val url: String)

    data class Post(
        val title: String,
        val version: String?,
        val links: List<Link>,
        val postUrl: String?,
        val published: String?,
        val images: List<String>,
        val summary: String,
        /** The newest download the blog offers, which is not always in the newest post. */
        val download: Download? = null,
    )

    /**
     * Where to get the game. The blog's newest posts are often news with no link at all (the newest at
     * the time of writing is about a fakemon project), so the posts are searched, newest first, for the
     * first one that carries the game, and the link is taken from there.
     */
    data class Download(
        val version: String?,
        /** The main link: MEGA when the post has one, else the first the post offers. */
        val url: String,
        /** Another link for the same game, in case the main one is full or down. */
        val alternative: String?,
        /** The post that carries the link. */
        val postUrl: String?,
        val published: String?,
    )

    /** One post of the feed, before anything is taken from it. */
    data class Entry(val title: String, val html: String, val postUrl: String?, val published: String?)

    suspend fun latest(): Result<Post> = withContext(Dispatchers.IO) {
        // DNS on mobile networks is occasionally flaky; a couple of quick retries smooth that over.
        var last: Throwable? = null
        repeat(3) { attempt ->
            if (attempt > 0) delay(700L * attempt)
            runCatching { fetch() }.onSuccess { return@withContext Result.success(it) }.onFailure { last = it }
        }
        Result.failure(last ?: IllegalStateException("No pude consultar el blog."))
    }

    private fun fetch(): Post {
        val first = parseEntries(get(1))
        if (first.isEmpty()) error("El blog no devolvio ninguna entrada.")
        var download = first.firstNotNullOfOrNull { pickDownload(it) }
        // Later pages only matter when the first ten posts have nothing; if one of them fails the news
        // are still shown, and the button falls back to the post.
        var page = 2
        while (download == null && page <= MAX_PAGES) {
            val more = runCatching { parseEntries(get((page - 1) * PAGE + 1)) }.getOrNull()
            if (more.isNullOrEmpty()) break
            download = more.firstNotNullOfOrNull { pickDownload(it) }
            page++
        }
        return post(first[0]).copy(download = download)
    }

    private fun get(startIndex: Int): String {
        val connection = (URL("$FEED&max-results=$PAGE&start-index=$startIndex").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "ReloadedLauncher/1.0")
        }
        connection.connect()
        if (connection.responseCode !in 200..299) error("El blog respondio ${connection.responseCode}.")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private val ANCHOR = Regex("""<a\s[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]+>")
    private val VERSION = Regex("""\d+\.\d+(?:\.\d+){1,2}""")
    // Preferred: the version right after "Beta" (e.g. "The Last Beta 1.9.2.2"). Anchoring matters
    // because plain digit runs inside URLs would otherwise match things like "201.9.2.2".
    private val BETA_VERSION = Regex("""Beta\s+(\d+\.\d+(?:\.\d+){1,2})""", RegexOption.IGNORE_CASE)
    // A name or a title is trusted more than free text, so two numbers are enough there ("Beta 1.9").
    private val NAMED_VERSION = Regex("""Beta\s+(\d+\.\d+(?:\.\d+){0,2})""", RegexOption.IGNORE_CASE)
    private val IMAGE = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val DROP_BLOCKS = Regex("""<(script|style)[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val PARAGRAPH = Regex("""<br\s*/?>|</p>|</div>|</h\d>""", RegexOption.IGNORE_CASE)
    private val NUMERIC_ENTITY = Regex("""&#(\d+);""")
    private val NAMED_ENTITIES = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
        "&lt;" to "<", "&gt;" to ">", "&aacute;" to "á", "&eacute;" to "é", "&iacute;" to "í",
        "&oacute;" to "ó", "&uacute;" to "ú", "&ntilde;" to "ñ", "&Aacute;" to "Á", "&Eacute;" to "É",
        "&Iacute;" to "Í", "&Oacute;" to "Ó", "&Uacute;" to "Ú", "&Ntilde;" to "Ñ",
        "&iexcl;" to "¡", "&iquest;" to "?", "&laquo;" to "«", "&raquo;" to "»", "&hellip;" to "…",
        "&ndash;" to "–", "&mdash;" to "—", "&middot;" to "·", "&deg;" to "°", "&trade;" to "™",
    )

    /** The posts of a Blogger JSON feed, newest first. A page past the last one has no `entry`. */
    internal fun parseEntries(body: String): List<Entry> {
        val entries = JSONObject(body).getJSONObject("feed").optJSONArray("entry") ?: return emptyList()
        return (0 until entries.length()).map { i ->
            val entry = entries.getJSONObject(i)
            val postUrl = entry.optJSONArray("link")
                ?.let { links ->
                    (0 until links.length())
                        .map { links.getJSONObject(it) }
                        .firstOrNull { it.optString("rel") == "alternate" }
                        ?.optString("href")
                }
                ?.ifEmpty { null }
            Entry(
                title = entry.getJSONObject("title").getString("\$t"),
                html = entry.getJSONObject("content").getString("\$t"),
                postUrl = postUrl,
                published = entry.optJSONObject("published")?.optString("\$t")?.take(10),
            )
        }
    }

    private fun post(entry: Entry): Post = Post(
        title = entry.title,
        version = newestVersion(entry.html),
        links = downloadLinks(entry.html),
        postUrl = entry.postUrl,
        published = entry.published,
        images = images(entry.html),
        summary = summary(entry.html),
    )

    private val HOSTS = listOf("mega.nz", "gofile.io", "mediafire.com", "drive.google.com", "dropbox.com")

    private fun decode(text: String): String =
        runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)

    /**
     * The game's download in a post, or null if the post has none.
     *
     * Not every link to a file host is the game: the posts also link the "Librerias" archive (a `.rar`
     * of runtime libraries) and, for the old versions, "Delta" patches that only make sense on top of
     * another install. Those are left out. MEGA goes first because it is the link the author gives as
     * the main one and it opens in the MEGA app or any browser; Gofile's direct links come after.
     */
    internal fun pickDownload(entry: Entry): Download? {
        val candidates = ANCHOR.findAll(entry.html)
            .map { Link(label = TAG.replace(it.groupValues[2], "").replace("&nbsp;", " ").trim(), url = it.groupValues[1].replace("&amp;", "&")) }
            .filter { link -> isFileHost(link.url) }
            .distinctBy { it.url }
            .filter { link ->
                val text = (link.label + " " + decode(link.url)).lowercase()
                !text.contains("librer") && !text.contains("delta") &&
                    !text.endsWith(".rar") && !text.endsWith(".7z")
            }
            .sortedBy { if (it.url.contains("mega.nz", ignoreCase = true)) 0 else 1 }
            .toList()
        val main = candidates.firstOrNull() ?: return null
        val version = candidates.firstNotNullOfOrNull { fileVersion(it.url) }
            ?: newestVersion(entry.html)
            ?: NAMED_VERSION.find(entry.title)?.groupValues?.get(1)
        return Download(
            version = version,
            url = main.url,
            alternative = candidates.drop(1).firstOrNull { it.url != main.url }?.url,
            postUrl = entry.postUrl,
            published = entry.published,
        )
    }

    /** The version in a file's own name ("...Beta 1.9.2.2 Full.zip"), which is the surest place to read it. */
    private fun fileVersion(url: String): String? {
        val name = decode(url.substringBefore('?').substringAfterLast('/'))
        return NAMED_VERSION.find(name)?.groupValues?.get(1) ?: VERSION.find(name)?.value
    }

    /** Whether a link points *at* one of the file hosts (not merely mentions one in its path). */
    private fun isFileHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase() ?: return false
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** The newest `x.y.z` mentioned in the post (the title alone is often one patch behind). */
    private fun newestVersion(html: String): String? =
        BETA_VERSION.findAll(html).map { it.groupValues[1] }.maxWithOrNull(::compareVersions)
            ?: VERSION.findAll(html).map { it.value }.maxWithOrNull(::compareVersions)

    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }

    private fun downloadLinks(html: String): List<Link> =
        ANCHOR.findAll(html)
            .map { Link(label = TAG.replace(it.groupValues[2], "").trim(), url = it.groupValues[1]) }
            .filter { it.url.contains("mega.nz") || it.url.contains("gofile.io") }
            .distinctBy { it.url }
            .toList()

    /** The post's own pictures, which are what makes the news worth showing in the app. */
    private fun images(html: String): List<String> =
        IMAGE.findAll(html)
            .map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()

    /** The post's text as plain paragraphs, cut to a readable preview for the launcher. */
    private fun summary(html: String): String {
        var text = DROP_BLOCKS.replace(html, " ")
        text = PARAGRAPH.replace(text, "\n")
        text = TAG.replace(text, "")
        NAMED_ENTITIES.forEach { (entity, value) -> text = text.replace(entity, value) }
        text = NUMERIC_ENTITY.replace(text) { match -> match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value }
        val paragraphs = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val preview = StringBuilder()
        for (paragraph in paragraphs) {
            if (preview.length + paragraph.length > 700) break
            if (preview.isNotEmpty()) preview.append('\n')
            preview.append(paragraph)
        }
        return preview.toString().ifEmpty { paragraphs.firstOrNull().orEmpty().take(700) }
    }
}
