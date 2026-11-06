package com.ahjin.pr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateTest {
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
}
