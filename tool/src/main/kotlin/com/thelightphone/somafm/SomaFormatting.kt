package com.thelightphone.somafm

/** "Ambient, electronic · 152 listening", dropping whichever half is missing. */
internal fun Channel.summaryLine(): String =
    listOfNotNull(
        genre.takeIf { it.isNotBlank() },
        listeners.takeIf { it > 0 }?.let { "$it listening" },
    ).joinToString(" · ")

/** "Artist - Title", or whichever half is present. */
internal fun Song.displayLine(): String =
    listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")

/** Compact age of a play: "now", "4m", "2h", "3d". [nowSeconds] and [playedAt] are epoch seconds. */
internal fun formatAgo(playedAt: Long?, nowSeconds: Long): String {
    if (playedAt == null) return ""
    val seconds = (nowSeconds - playedAt).coerceAtLeast(0)
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        else -> "${seconds / 86_400}d"
    }
}

internal fun RadioStatus.label(): String = when (this) {
    RadioStatus.Idle -> ""
    RadioStatus.Connecting -> "Tuning in…"
    RadioStatus.Playing -> "Playing"
    RadioStatus.Paused -> "Paused"
    is RadioStatus.Failed -> message
}

/** Favorites first (in list order), then everything else; both alphabetical by title. */
internal fun sortChannels(channels: List<Channel>, favorites: Set<String>): Pair<List<Channel>, List<Channel>> {
    val byTitle = channels.sortedBy { it.title.lowercase() }
    return byTitle.partition { it.id in favorites }
}
