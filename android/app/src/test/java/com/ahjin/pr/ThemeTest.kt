package com.ahjin.pr

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.ahjin.pr.ui.Accent
import com.ahjin.pr.ui.IvoryScheme
import com.ahjin.pr.ui.ObsidianScheme
import com.ahjin.pr.ui.scheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = if (a.luminance() >= b.luminance()) a to b else b to a
        return (hi.luminance() + 0.05f) / (lo.luminance() + 0.05f)
    }

    @Test
    fun `gold without AMOLED is exactly the original look`() {
        assertEquals(ObsidianScheme, scheme(dark = true))
        assertEquals(IvoryScheme, scheme(dark = false))
    }

    @Test
    fun `every accent is readable in every mode`() {
        for (dark in listOf(true, false)) for (amoled in listOf(false, true)) for (accent in Accent.ALL) {
            val s = scheme(dark, accent, amoled)
            val where = "accent=$accent dark=$dark amoled=$amoled"
            assertTrue("$where text on primary", contrast(s.onPrimary, s.primary) >= 4.5f)
            assertTrue("$where primary on page", contrast(s.primary, s.background) >= 4.5f)
            assertTrue("$where text on container", contrast(s.onPrimaryContainer, s.primaryContainer) >= 4.5f)
            assertTrue("$where text on page", contrast(s.onBackground, s.background) >= 7f)
            assertTrue("$where text on cards", contrast(s.onSurface, s.surfaceContainerHigh) >= 7f)
        }
    }

    @Test
    fun `AMOLED is pure black in dark mode and changes nothing in light mode`() {
        val black = scheme(dark = true, amoled = true)
        assertEquals(Color.Black, black.background)
        assertEquals(Color.Black, black.surface)
        assertTrue("cards still stand out", black.surfaceContainerHigh != Color.Black)
        assertEquals(IvoryScheme, scheme(dark = false, amoled = true))
    }

    @Test
    fun `an unknown accent falls back to gold`() {
        assertEquals(scheme(dark = true), scheme(dark = true, accent = 99))
    }
}
