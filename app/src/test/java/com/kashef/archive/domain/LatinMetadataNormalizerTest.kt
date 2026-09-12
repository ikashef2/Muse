package com.kashef.archive.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatinMetadataNormalizerTest {
    @Test
    fun latinMetadataPassesThroughWithoutAlias() {
        val result = LatinMetadataNormalizer.canonicalize("Time")
        assertEquals("Time", result.latin)
        assertEquals("", result.original)
        assertFalse(result.requiresReview)
    }

    @Test
    fun remoteLatinAliasWinsAndOriginalScriptIsPreserved() {
        val result = LatinMetadataNormalizer.canonicalize("مرغ سحر", "Morgh-e Sahar")
        assertEquals("Morgh-e Sahar", result.latin)
        assertEquals("مرغ سحر", result.original)
        assertFalse(result.requiresReview)
    }

    @Test
    fun offlineFallbackNeverLeavesPersianScriptVisible() {
        val result = LatinMetadataNormalizer.canonicalize("شهر خاموش")
        assertTrue(result.latin.isNotBlank())
        assertTrue(LatinMetadataNormalizer.isLatin(result.latin))
        assertTrue(result.requiresReview)
    }
}
