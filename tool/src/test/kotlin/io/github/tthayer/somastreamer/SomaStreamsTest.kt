package io.github.tthayer.somastreamer

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// groovesalad130.pls under src/test/resources is a real SomaFM playlist captured 2026-09-25.
class SomaStreamsTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    @AfterTest
    fun clearLog() = SomaStationIds.forEach(SongLog::clear)

    @Test
    fun `bundled stations are unique and stream over https`() {
        assertTrue(SOMA_STATIONS.size > 30)
        assertEquals(SOMA_STATIONS.size, SomaStationIds.toSet().size)
        SOMA_STATIONS.forEach { channel ->
            assertTrue(channel.title.isNotBlank(), channel.id)
            assertTrue(channel.playlists.isNotEmpty(), channel.id)
            assertTrue(channel.playlists.all { it.url.startsWith("https://somafm.com/${channel.id}") }, channel.id)
        }
    }

    @Test
    fun `station rows map bitrates onto quality tiers`() {
        val channel = station("groovesalad", "Groove Salad", "ambient/electronic", "", 130, 64, 32)
        assertEquals("ambient, electronic", channel.genre)
        assertEquals("https://somafm.com/groovesalad32.pls", channel.playlistFor(StreamQuality.Low)?.url)
        assertEquals("https://somafm.com/groovesalad64.pls", channel.playlistFor(StreamQuality.Standard)?.url)
        assertEquals("https://somafm.com/groovesalad130.pls", channel.playlistFor(StreamQuality.High)?.url)
    }

    @Test
    fun `falls back to any playlist when the tier is missing`() {
        val noStandard = station("x", "X", "", "", 130, 32)
        assertEquals("https://somafm.com/x130.pls", noStandard.playlistFor(StreamQuality.Standard)?.url)
        assertNull(station("y", "Y", "", "").playlistFor(StreamQuality.High))
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
    fun `reads artist and title from ICY metadata`() {
        // Captured from ice2.somafm.com/groovesalad-32-aac on 2026-10-08.
        val song = parseStreamTitle(
            "StreamTitle='Coco Steel & Lovebomb - Ice Cream We All Scream For (Carlos Cervilla Remix)';" +
                "StreamUrl='https://somafm.com/logos/512/groovesalad512.jpg';",
        )
        assertEquals("Coco Steel & Lovebomb", song?.artist)
        assertEquals("Ice Cream We All Scream For (Carlos Cervilla Remix)", song?.title)
    }

    @Test
    fun `ICY titles without a separator, with apostrophes, or empty`() {
        assertEquals(Song("SomaFM Station ID", "", null), parseStreamTitle("StreamTitle='SomaFM Station ID';"))
        // Splits on the first separator only; the title keeps the rest.
        assertEquals("Don't Stop - Me Now", parseStreamTitle("StreamTitle='Queen - Don't Stop - Me Now';")?.title)
        assertEquals("Queen", parseStreamTitle("StreamTitle='Queen - Don't Stop Me Now';")?.artist)
        assertEquals("Don't Stop Me Now", parseStreamTitle("StreamTitle='Queen - Don't Stop Me Now';")?.title)
        assertNull(parseStreamTitle("StreamTitle='';"))
        assertNull(parseStreamTitle(""))
    }

    @Test
    fun `song log keeps newest first and skips repeats`() {
        val id = SOMA_STATIONS.first().id
        SongLog.record(id, Song("One", "A", null), nowSeconds = 100)
        SongLog.record(id, Song("One", "A", null), nowSeconds = 130)
        SongLog.record(id, Song("Two", "B", null), nowSeconds = 300)
        assertEquals(listOf("Two" to 300L, "One" to 100L), SongLog.songs.value[id]?.map { it.title to it.playedAt })
        SongLog.clear(id)
        assertNull(SongLog.songs.value[id])
    }
}

private val SomaStationIds get() = SOMA_STATIONS.map { it.id }
