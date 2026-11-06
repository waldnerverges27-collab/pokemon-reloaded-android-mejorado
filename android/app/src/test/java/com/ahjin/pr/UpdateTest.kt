package com.ahjin.pr

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class UpdateTest {
    @Rule @JvmField
    val temporary = TemporaryFolder()

    private val splitRelease = listOf(
        "pokemon-reloaded-mejorado-0.9.0-dev3-armeabi-v7a.apk",
        "pokemon-reloaded-mejorado-0.9.0-dev3-arm64-v8a.apk",
        "checksums.txt",
    )

    @Test
    fun `the updater chooses the device's own ABI regardless of asset order`() {
        assertEquals(splitRelease[1], chooseApkName(splitRelease, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(splitRelease[0], chooseApkName(splitRelease, listOf("armeabi-v7a")))
        assertEquals(splitRelease[1], chooseApkName(splitRelease.reversed(), listOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun `legacy universal APKs remain eligible and incompatible splits are not offered`() {
        val legacy = listOf("pokemon-reloaded-mejorado-0.8.1.apk")
        assertEquals(legacy.single(), chooseApkName(legacy, listOf("arm64-v8a")))
        val universal = splitRelease + "pokemon-reloaded-mejorado-0.9.0-dev3-universal.apk"
        assertEquals(splitRelease[1], chooseApkName(universal, listOf("arm64-v8a", "armeabi-v7a")))
        assertNull(chooseApkName(listOf("app-x86_64.apk"), listOf("arm64-v8a")))
        assertNull(chooseApkName(listOf("checksums.txt"), listOf("arm64-v8a")))
    }

    @Test
    fun `only a complete sha256 asset digest is accepted`() {
        val digest = "A".repeat(64)
        assertEquals(digest.lowercase(), sha256FromDigest("sha256:$digest"))
        assertEquals("", sha256FromDigest("md5:$digest"))
        assertEquals("", sha256FromDigest("sha256:abc"))
        assertEquals("", sha256FromDigest(null))
    }

    @Test
    fun `file sha256 uses the actual bytes`() {
        val file = temporary.newFile("abc.apk").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", fileSha256(file))
    }

    @Test
    fun `download resumes the exact requested range`() {
        val bytes = ByteArray(250_000) { (it % 251).toByte() }
        val range = AtomicReference<String?>()
        val server = apkServer(bytes, range = range, honorRange = true)
        try {
            val target = temporary.newFile("resume.apk.part").apply {
                writeBytes(bytes.copyOf(100_000))
            }
            val progress = mutableListOf<Pair<Long, Long>>()
            downloadFile(server.url, target, bytes.size.toLong(), { false }) { done, total ->
                progress += done to total
            }
            assertEquals("bytes=100000-", range.get())
            assertEquals(100_000L, progress.first().first)
            assertEquals(bytes.size.toLong(), progress.last().second)
            assertArrayEquals(bytes, target.readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `download restarts cleanly when a server ignores range`() {
        val bytes = ByteArray(180_000) { (it % 239).toByte() }
        val server = apkServer(bytes, honorRange = false)
        try {
            val target = temporary.newFile("ignored-range.apk.part").apply {
                writeBytes(bytes.copyOf(70_000))
            }
            downloadFile(server.url, target, bytes.size.toLong(), { false }) { _, _ -> }
            assertArrayEquals(bytes, target.readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `cancelling keeps an existing partial file untouched`() {
        val bytes = ByteArray(120_000) { (it % 241).toByte() }
        val server = apkServer(bytes, honorRange = true)
        try {
            val partial = bytes.copyOf(50_000)
            val target = temporary.newFile("cancelled.apk.part").apply { writeBytes(partial) }
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                downloadFile(server.url, target, bytes.size.toLong(), { true }) { _, _ -> }
            }
            assertArrayEquals(partial, target.readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a truncated response fails but leaves bytes available to resume`() {
        val bytes = ByteArray(90_000) { (it % 227).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes.copyOf(20_000)) }
        }
        server.start()
        try {
            val target = File(temporary.root, "truncated.apk.part")
            val url = "http://127.0.0.1:${server.address.port}/apk"
            assertThrows(Exception::class.java) {
                downloadFile(url, target, bytes.size.toLong(), { false }) { _, _ -> }
            }
            assertTrue(target.isFile)
            assertTrue(target.length() in 1 until bytes.size.toLong())
        } finally {
            server.stop(0)
        }
    }

    private class TestServer(private val server: HttpServer) {
        val url = "http://127.0.0.1:${server.address.port}/apk"
        fun stop() = server.stop(0)
    }

    private fun apkServer(
        bytes: ByteArray,
        range: AtomicReference<String?> = AtomicReference(),
        honorRange: Boolean,
    ): TestServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange ->
            val requested = exchange.requestHeaders.getFirst("Range")
            range.set(requested)
            val start = if (honorRange && requested != null) {
                requested.substringAfter("bytes=").substringBefore('-').toInt()
            } else 0
            val response = bytes.copyOfRange(start, bytes.size)
            if (start > 0) {
                exchange.responseHeaders.set("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
            }
            exchange.sendResponseHeaders(if (start > 0) 206 else 200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        return TestServer(server)
    }
}
