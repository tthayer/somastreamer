package com.thelightphone.somafm

import kotlin.test.Test
import kotlin.test.assertEquals

class SomaFormattingTest {

    private fun channel(id: String, title: String, genre: String = "", listeners: Int = 0) =
        Channel(id, title, "", "", genre, listeners, "", emptyList())

    @Test
    fun `formats play age compactly`() {
        val now = 1_000_000L
        assertEquals("", formatAgo(null, now))
        assertEquals("now", formatAgo(now - 30, now))
        assertEquals("now", formatAgo(now + 30, now))
        assertEquals("4m", formatAgo(now - 4 * 60 - 5, now))
        assertEquals("2h", formatAgo(now - 2 * 3_600, now))
        assertEquals("3d", formatAgo(now - 3 * 86_400, now))
    }

    @Test
    fun `summary line drops missing parts`() {
        assertEquals("ambient · 12 listening", channel("a", "A", "ambient", 12).summaryLine())
        assertEquals("ambient", channel("a", "A", "ambient").summaryLine())
        assertEquals("12 listening", channel("a", "A", listeners = 12).summaryLine())
        assertEquals("", channel("a", "A").summaryLine())
    }

    @Test
    fun `song line joins artist and title`() {
        assertEquals("Bistro Boy - Waves Of Sorrow", Song("Waves Of Sorrow", "Bistro Boy", "", null).displayLine())
        assertEquals("Waves Of Sorrow", Song("Waves Of Sorrow", "", "", null).displayLine())
    }

    @Test
    fun `sorts favorites apart, both alphabetical`() {
        val channels = listOf(channel("z", "zeta"), channel("b", "Beta"), channel("a", "alpha"), channel("g", "Gamma"))
        val (favorites, others) = sortChannels(channels, setOf("z", "a"))
        assertEquals(listOf("a", "z"), favorites.map { it.id })
        assertEquals(listOf("b", "g"), others.map { it.id })
    }

    @Test
    fun `quality cycles and falls back to the default`() {
        assertEquals(StreamQuality.Standard, StreamQuality.Low.next())
        assertEquals(StreamQuality.Low, StreamQuality.High.next())
        assertEquals(StreamQuality.Default, StreamQuality.fromName(null))
        assertEquals(StreamQuality.Default, StreamQuality.fromName("bogus"))
        assertEquals(StreamQuality.High, StreamQuality.fromName("High"))
    }
}
