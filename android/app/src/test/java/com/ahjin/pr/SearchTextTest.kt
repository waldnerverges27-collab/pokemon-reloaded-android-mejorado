package com.ahjin.pr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    @Test
    fun `empty and blank queries do not filter`() {
        assertTrue(matchesSearch("", "Cualquier texto"))
        assertTrue(matchesSearch("   ", "Cualquier texto"))
    }

    @Test
    fun `accents and letter case are ignored`() {
        assertTrue(matchesSearch("PIXELES", "Píxeles perfectos"))
        assertTrue(matchesSearch("instalacion", "Asistente de Instalación"))
        assertTrue(matchesSearch("pokemon", "Pokémon"))
    }

    @Test
    fun `several words can appear in another order`() {
        assertTrue(matchesSearch("pantalla rellenar", "Rellenar la pantalla"))
        assertTrue(matchesSearch("  perfectos   pixeles ", "Píxeles perfectos"))
    }

    @Test
    fun `punctuation separates search terms`() {
        assertTrue(matchesSearch("f5/f6", "Los iconos pulsan F5 y F6"))
        assertTrue(matchesSearch("f5 f6", "Los iconos pulsan F5 y F6"))
    }

    @Test
    fun `guide results keep the useful paragraphs without losing context`() {
        val paragraphs = listOf("Los botones son translúcidos", "F5 guarda y F6 carga", "Las copias se exportan")
        assertEquals(listOf("F5 guarda y F6 carga"), searchTextDocument("f5/f6", "Guardar partida", paragraphs))
        assertEquals(paragraphs, searchTextDocument("partida", "Guardar partida", paragraphs))
        assertEquals(paragraphs, searchTextDocument("", "Guardar partida", paragraphs))
        assertNull(searchTextDocument("no-existe", "Guardar partida", paragraphs))
    }

    @Test
    fun `all words are required and can use several fields`() {
        assertTrue(matchesSearch("copias comparte", "Copias de seguridad", "Haz, restaura o comparte"))
        assertFalse(matchesSearch("copias restaurar", "Copias de seguridad"))
        assertFalse(matchesSearch("que-no-existe", "Guía de uso"))
    }
}
