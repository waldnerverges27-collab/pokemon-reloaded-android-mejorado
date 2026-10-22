package com.ahjin.pr

import com.ahjin.pr.ZipIntake.Kind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipIntakeTest {
    private fun zip(vararg names: String, charset: java.nio.charset.Charset = StandardCharsets.UTF_8): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes, charset).use { out ->
            for (name in names) {
                out.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) out.write(byteArrayOf(1, 2, 3))
                out.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test
    fun `the game zip is recognised by its executable`() {
        assertEquals(Kind.Game, ZipIntake.classify(zip("Pokemon Reloaded 1.9.2.2 Full.exe", "data/a.png")))
    }

    @Test
    fun `a game zip made from the folder keeps being the game`() {
        assertEquals(Kind.Game, ZipIntake.classify(zip("Pokemon Reloaded/", "Pokemon Reloaded/data/a.png")))
    }

    @Test
    fun `names that are not utf8 do not stop it`() {
        // 0xA1 and 0xA2 are the accented letters of the official zip; the default reader refuses them.
        val odd = "data/Espec\u00a1ficas.dat"
        assertEquals(Kind.Game, ZipIntake.classify(zip("data/", odd, "Reloaded.exe", charset = StandardCharsets.ISO_8859_1)))
    }

    @Test
    fun `an exported slot is one slot`() {
        assertEquals(
            Kind.Slot(2),
            ZipIntake.classify(zip("partidas/reloaded2.savc", "partidas/reloaded2.nfo", "partidas/reloaded2_bk.savc")),
        )
    }

    @Test
    fun `two slots are a restore`() {
        assertEquals(Kind.Saves, ZipIntake.classify(zip("partidas/reloaded1.savc", "partidas/reloaded3.savc")))
    }

    @Test
    fun `saves with teams are a restore even with one slot`() {
        assertEquals(Kind.Saves, ZipIntake.classify(zip("partidas/reloaded1.savc", "equipos/uno.team")))
    }

    @Test
    fun `a safety copy with its info file is a restore`() {
        assertEquals(Kind.Saves, ZipIntake.classify(zip("partidas/reloaded1.savc", "char_custom/x.png", "backup.info")))
    }

    @Test
    fun `anything else is unknown`() {
        assertEquals(Kind.Unknown, ZipIntake.classify(zip("notas.txt", "fotos/a.jpg")))
        assertEquals(Kind.Unknown, ZipIntake.classify(zip()))
    }

    @Test
    fun `a zip with a stray file beside one slot is not just a slot`() {
        assertEquals(Kind.Saves, ZipIntake.classify(zip("partidas/reloaded1.savc", "leeme.txt")))
    }
}
