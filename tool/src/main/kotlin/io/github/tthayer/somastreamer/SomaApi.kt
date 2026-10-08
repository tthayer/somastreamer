package io.github.tthayer.somastreamer

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.discard
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readByteArray

private const val USER_AGENT = "SomaStreamer/0.1 (Light Phone)"

// Icecast sends one metadata block every `icy-metaint` audio bytes (45,000 on
// SomaFM). Refuse anything far larger so a misbehaving server can't make a
// title check download minutes of audio.
private const val MAX_METAINT = 256 * 1024

internal class SomaApiException(message: String) : Exception(message)

/**
 * Client for SomaFM's public listening endpoints: the `.pls` playlists and the
 * Icecast streams they point to. Cheap to build; [close] when done.
 */
internal class SomaApi(
    private val client: HttpClient = somaHttpClient(),
) {
    /**
     * Resolves a `.pls` playlist to its mirrored stream URLs. The platform player
     * can't read `.pls` itself, so the tool hands it a direct Icecast URL.
     */
    suspend fun resolveStreams(playlist: Playlist): Result<List<String>> = runCatching {
        parsePls(fetch(playlist.url)).ifEmpty { throw SomaApiException("That station has no streams right now.") }
    }

    /**
     * What [streamUrl] is playing right now. Opens the stream asking for ICY
     * metadata, skips the audio up to the first metadata block, reads the track
     * title from it, and hangs up: about one metadata interval of audio per call.
     * Null when the station is between tracks.
     */
    suspend fun streamTitle(streamUrl: String): Result<Song?> = runCatching {
        client.prepareGet(streamUrl) { header("Icy-MetaData", "1") }.execute { response ->
            if (!response.status.isSuccess()) throw friendlyError(response)
            val metaint = response.headers["icy-metaint"]?.trim()?.toIntOrNull()
                ?.takeIf { it in 1..MAX_METAINT }
                ?: throw SomaApiException("That stream doesn't say what's playing.")
            val body = response.bodyAsChannel()
            body.discard(metaint.toLong())
            val length = (body.readByte().toInt() and 0xFF) * 16
            if (length == 0) return@execute null
            parseStreamTitle(body.readByteArray(length).decodeToString().trimEnd('\u0000'))
        }
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
