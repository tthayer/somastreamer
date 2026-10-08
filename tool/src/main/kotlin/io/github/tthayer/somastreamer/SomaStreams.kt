package io.github.tthayer.somastreamer

private val PLS_FILE_LINE = Regex("""^File(\d+)\s*=\s*(\S+)\s*$""", RegexOption.IGNORE_CASE)

/**
 * Extracts the stream URLs from a `.pls` playlist, in `FileN` order. SomaFM's
 * playlists list the same stream on several mirrors (`ice1`, `ice2`, ...).
 */
internal fun parsePls(text: String): List<String> =
    text.lineSequence()
        .mapNotNull { PLS_FILE_LINE.matchEntire(it.trim()) }
        .map { it.groupValues[1].toInt() to it.groupValues[2] }
        .sortedBy { it.first }
        .map { it.second }
        .filter { it.startsWith("https://") || it.startsWith("http://") }
        .distinct()
        .toList()

/**
 * The best playlist for [quality]: the matching tier in the quality's preferred
 * format order, else any playlist of that tier, else the channel's first one.
 */
internal fun Channel.playlistFor(quality: StreamQuality): Playlist? {
    val tier = playlists.filter { it.quality.equals(quality.quality, ignoreCase = true) }
    return quality.preferredFormats.firstNotNullOfOrNull { format ->
        tier.firstOrNull { it.format.equals(format, ignoreCase = true) }
    } ?: tier.firstOrNull() ?: playlists.firstOrNull()
}

private val STREAM_TITLE = Regex("""StreamTitle='(.*?)';""", RegexOption.DOT_MATCHES_ALL)

/**
 * Reads the track out of an Icecast (ICY) metadata block, e.g.
 * `StreamTitle='Artist - Title';StreamUrl='...';`. SomaFM joins artist and title
 * with " - "; a title with no separator is returned as the title alone. Null
 * when the block carries no track (station IDs and breaks send an empty title).
 */
internal fun parseStreamTitle(metadata: String): Song? {
    val raw = STREAM_TITLE.find(metadata)?.groupValues?.get(1)?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val separator = raw.indexOf(" - ")
    return if (separator < 0) {
        Song(title = raw, artist = "", playedAt = null)
    } else {
        Song(title = raw.substring(separator + 3).trim(), artist = raw.substring(0, separator).trim(), playedAt = null)
    }
}
