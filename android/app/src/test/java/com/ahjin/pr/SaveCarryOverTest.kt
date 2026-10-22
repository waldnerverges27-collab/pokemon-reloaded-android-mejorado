package com.ahjin.pr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveCarryOverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val folders = listOf("partidas", "equipos", "char_custom")

    private fun write(root: File, path: String, text: String) {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeText(text)
    }

    @Test
    fun `the saves of the old game are in the new one`() {
        val old = tmp.newFolder("old")
        val new = tmp.newFolder("new")
        write(old, "partidas/reloaded1.savc", "uno")
        write(old, "partidas/reloaded1.nfo", "info")
        write(old, "partidas/reloaded1_bk.savc", "copia")
        write(old, "partidas/reloaded2.savc", "dos")
        write(old, "equipos/a.team", "equipo")
        write(old, "char_custom/x/pic.png", "png")
        write(old, "music/song.mid", "no es una partida")
        write(new, "images/a.png", "nuevo")

        val slots = SaveCarryOver.merge(old, new, folders)

        assertEquals(2, slots)
        assertEquals("uno", File(new, "partidas/reloaded1.savc").readText())
        assertEquals("copia", File(new, "partidas/reloaded1_bk.savc").readText())
        assertEquals("equipo", File(new, "equipos/a.team").readText())
        assertEquals("png", File(new, "char_custom/x/pic.png").readText())
        assertEquals("nuevo", File(new, "images/a.png").readText())
        assertFalse(File(new, "music/song.mid").exists())
        // Nothing is taken out of the old game by copying.
        assertTrue(File(old, "partidas/reloaded1.savc").exists())
    }

    @Test
    fun `the player's save wins over the zip's file of the same name`() {
        val old = tmp.newFolder("old")
        val new = tmp.newFolder("new")
        write(old, "partidas/reloaded1.savc", "mi progreso")
        write(new, "partidas/reloaded1.savc", "de fabrica")
        write(new, "char_custom/nuevo.png", "solo del zip")

        SaveCarryOver.merge(old, new, folders)

        assertEquals("mi progreso", File(new, "partidas/reloaded1.savc").readText())
        assertEquals("solo del zip", File(new, "char_custom/nuevo.png").readText())
    }

    @Test
    fun `a first install has nothing to carry`() {
        val old = File(tmp.root, "no-existe")
        val new = tmp.newFolder("new")
        assertEquals(0, SaveCarryOver.merge(old, new, folders))
        assertFalse(File(new, "partidas").exists())
    }
}
