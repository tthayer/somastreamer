package com.thelightphone.somafm

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

// SomaFM's public, unauthenticated JSON feeds. channels.json lists every station
// with its .pls playlists; songs/{id}.json is the station's recent play history,
// newest first.
internal const val SOMA_CHANNELS_URL = "https://api.somafm.com/channels.json"
internal const val SOMA_SONGS_URL = "https://somafm.com/songs/"
private const val USER_AGENT = "LightPhone-SomaFM/0.1"

internal class SomaApiException(message: String) : Exception(message)

/** Read-only client for the SomaFM feeds. Cheap to build; [close] when done. */
internal class SomaApi(
    private val client: HttpClient = somaHttpClient(),
) {
    suspend fun listChannels(): Result<List<Channel>> = runCatching {
        parseChannels(fetch(SOMA_CHANNELS_URL))
    }

    suspend fun recentSongs(channelId: String): Result<List<Song>> = runCatching {
        parseSongs(fetch("$SOMA_SONGS_URL$channelId.json"))
    }

    /**
     * Resolves a `.pls` playlist to its mirrored stream URLs. The platform player
     * can't read `.pls` itself, so the tool hands it a direct Icecast URL.
     */
    suspend fun resolveStreams(playlist: Playlist): Result<List<String>> = runCatching {
        parsePls(fetch(playlist.url)).ifEmpty { throw SomaApiException("That station has no streams right now.") }
    }

    private suspend fun fetch(url: String): String {
        val response = client.get(url)
        if (!response.status.isSuccess()) throw friendlyError(response)
        return response.bodyAsText()
    }

    private fun friendlyError(response: HttpResponse): Exception = when (response.status) {
        HttpStatusCode.NotFound -> SomaApiException("Not found.")
        else -> SomaApiException("SomaFM HTTP ${response.status.value}.")
    }

    fun close() = client.close()
}

internal fun somaHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
    }
    defaultRequest { headers.append(HttpHeaders.UserAgent, USER_AGENT) }
}

/** Opens a [SomaApi] for one unit of work. */
internal suspend fun <T> withApi(block: suspend (SomaApi) -> Result<T>): Result<T> {
    val api = SomaApi()
    return try {
        block(api)
    } finally {
        api.close()
    }
}
