package com.ahjin.pr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlogTest {
    private fun sample(): List<Blog.Entry> =
        Blog.parseEntries(javaClass.getResource("/blog-feed-sample.json")!!.readText())

    private fun entry(html: String, title: String = "Post") = Blog.Entry(title, html, "https://blog/post.html", "2026-01-01")

    @Test
    fun `a news post with no link has no download`() {
        val posts = sample()
        assertEquals("The Last Fakemon", posts[0].title)
        assertNull(Blog.pickDownload(posts[0]))
    }

    @Test
    fun `the download is taken from the newest post that has one`() {
        val posts = sample()
        val found = posts.firstNotNullOf { Blog.pickDownload(it) }
        assertEquals("https://mega.nz/file/RrsDjDQY#5hOsjq7HIYsNX30JkeHzPGCl12ZO8yugdcz_f7gqLCA", found.url)
        assertEquals("1.9.2.2", found.version)
        assertEquals(posts[1].postUrl, found.postUrl)
    }

    @Test
    fun `the libraries archive is never offered as the game`() {
        val found = Blog.pickDownload(sample()[1])!!
        assertNotNull(found.alternative)
        assertTrue(found.alternative!!.contains("Full.zip"))
        assertTrue(!found.alternative!!.contains("rar"))
    }

    @Test
    fun `mega goes first even when it is listed after another host`() {
        val html = """<a href="https://store1.gofile.io/download/web/x/Proyecto%20Reloaded%20The%20Last%20Beta%202.0%20Full.zip">Alternativo</a>
            <a href="https://mega.nz/file/AAA#bbb">Link</a>"""
        val found = Blog.pickDownload(entry(html))!!
        assertEquals("https://mega.nz/file/AAA#bbb", found.url)
        assertTrue(found.alternative!!.contains("gofile"))
        assertEquals("2.0", found.version)
    }

    @Test
    fun `delta patches and libraries alone are not a game`() {
        val html = """<a href="https://store1.gofile.io/download/web/x/Proyecto%20Reloaded%20The%20Last%20Beta%201.4%20Delta.zip">Delta</a>
            <a href="https://cold2.gofile.io/download/web/y/Librer%C3%ADasReloaded.rar">Librerías Reloaded</a>
            <a href="https://example.com/mega.nz">otra cosa</a>"""
        assertNull(Blog.pickDownload(entry(html)))
    }

    @Test
    fun `a link with no file name takes its version from the post`() {
        val found = Blog.pickDownload(entry("""<a href="https://mega.nz/file/Z#k">Link</a> Ya disponible The Last Beta 1.7.1""", "The Last Beta 1.7"))!!
        assertEquals("1.7.1", found.version)
    }

    @Test
    fun `a page past the end has no entries`() {
        assertEquals(0, Blog.parseEntries("""{"feed":{"id":{"${'$'}t":"x"}}}""").size)
    }
}
