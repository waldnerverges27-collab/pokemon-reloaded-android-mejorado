package com.ahjin.pr

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTest {
    @Test
    fun `perfect pixels are the initial mode without replacing an earlier choice`() {
        assertEquals("integer", resolveVideoMode(null, hasLegacyAspect = false, keepAspect = false))
        assertEquals("full", resolveVideoMode(null, hasLegacyAspect = true, keepAspect = false))
        assertEquals("aspect", resolveVideoMode(null, hasLegacyAspect = true, keepAspect = true))
        for (mode in VIDEO_MODES) {
            assertEquals(mode, resolveVideoMode(mode, hasLegacyAspect = true, keepAspect = mode == "full"))
        }
    }
}
