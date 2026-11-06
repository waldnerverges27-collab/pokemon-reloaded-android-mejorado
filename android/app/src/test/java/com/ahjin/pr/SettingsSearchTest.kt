package com.ahjin.pr

import com.ahjin.pr.ui.settingSearchEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val entries = settingSearchEntries("0.9.0-dev10")

    private fun search(query: String) = entries.filter {
        matchesSearch(query, it.title, it.subtitle, it.section, it.keywords)
    }

    @Test
    fun `every entry has a unique id and can be found by its own title`() {
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
        entries.forEach { entry ->
            assertTrue("No se encontró ${entry.title}", search(entry.title).any { it.id == entry.id })
        }
    }

    @Test
    fun `all settings groups are represented`() {
        assertEquals(
            setOf(
                "Juego", "Audio", "Partidas", "Mando", "Botones", "Control de movimiento",
                "Controles táctiles", "Vibración al tocar", "Resolución", "Extras", "Apariencia",
                "Color y negro", "Pantalla", "Actualizaciones", "Ayuda",
            ),
            entries.map { it.section }.toSet(),
        )
        assertEquals(
            setOf(
                "Juego", "Botones", "Control de movimiento", "Controles tactiles", "Vibracion al tocar",
                "Resolucion", "Apariencia", "Color y negro", "Pantalla",
            ),
            entries.mapNotNull { it.scrollTo }.toSet(),
        )
    }

    @Test
    fun `synonyms and accented names reach the right options`() {
        assertTrue(search("pixeles perfectos").any { it.id == "video-integer" })
        assertTrue(search("f5/f6").any { it.id == "saves-mine" })
        assertTrue(search("asistente instalacion").any { it.id == "help-doctor" })
        assertTrue(search("avance rapido").any { it.id == "extras" })
    }

    @Test
    fun `an unknown query returns no results`() {
        assertTrue(search("termino-que-no-existe").isEmpty())
    }
}
