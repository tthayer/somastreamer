package com.thelightphone.somafm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Fixtures under src/test/resources are real SomaFM responses captured 2026-09-25
// (channels.json trimmed to three stations).
class SomaJsonTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    @Test
    fun `parses channels, splitting pipe-separated genres`() {
        val channels = parseChannels(fixture("channels.json")).associateBy { it.id }
        assertEquals(setOf("beatblender", "dronezone", "groovesalad"), channels.keys)

        val grooveSalad = channels.getValue("groovesalad")
        assertEquals("Groove Salad", grooveSalad.title)
        assertEquals("ambient, electronic", grooveSalad.genre)
        assertEquals(4, grooveSalad.playlists.size)
    }

    @Test
    fun `tolerates missing and unknown fields`() {
        val channels = parseChannels("""{"channels":[{"id":"x","listeners":"12","extra":true}]}""")
        assertEquals("x", channels.single().title)
        assertTrue(channels.single().playlists.isEmpty())
    }

    @Test
    fun `parses songs newest first with epoch dates`() {
        val songs = parseSongs(fixture("songs-groovesalad.json"))
        assertTrue(songs.isNotEmpty())
        val first = songs.first()
        assertTrue(first.title.isNotBlank())
        assertTrue((first.playedAt ?: 0) > 1_700_000_000)
        assertTrue(songs.zipWithNext().all { (a, b) -> (a.playedAt ?: 0) >= (b.playedAt ?: 0) })
    }

    @Test
    fun `extracts pls mirrors in FileN order`() {
        assertEquals(
            listOf("https://ice2.somafm.com/groovesalad-128-aac", "https://ice6.somafm.com/groovesalad-128-aac"),
            parsePls(
                """
                [playlist]
                numberofentries=2
                File2=https://ice6.somafm.com/groovesalad-128-aac
                Title2=SomaFM: Groove Salad (#2)
                File1=https://ice2.somafm.com/groovesalad-128-aac
                Length1=-1
                Version=2
                """.trimIndent(),
            ),
        )
        assertEquals(3, parsePls(fixture("groovesalad130.pls")).size)
    }

    @Test
    fun `pls parsing ignores non-http entries and blank playlists`() {
        assertEquals(emptyList(), parsePls("[playlist]\nFile1=file:///etc/passwd\n"))
        assertEquals(emptyList(), parsePls(""))
    }

    @Test
    fun `picks the playlist matching quality and preferred format`() {
        val channel = parseChannels(fixture("channels.json")).first { it.id == "groovesalad" }
        assertEquals("https://api.somafm.com/groovesalad32.pls", channel.playlistFor(StreamQuality.Low)?.url)
        assertEquals("https://api.somafm.com/groovesalad64.pls", channel.playlistFor(StreamQuality.Standard)?.url)
        assertEquals("https://api.somafm.com/groovesalad130.pls", channel.playlistFor(StreamQuality.High)?.url)
    }

    @Test
    fun `falls back to any playlist when the tier is missing`() {
        val mp3Only = Channel("x", "X", "", "", "", "", listOf(Playlist("https://a/x.pls", "mp3", "highest")))
        assertEquals("https://a/x.pls", mp3Only.playlistFor(StreamQuality.Low)?.url)
        assertNull(mp3Only.copy(playlists = emptyList()).playlistFor(StreamQuality.High))
    }
}
