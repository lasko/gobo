package com.gobo.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * GoTV is OGS' directory of **external** live Go streams on Twitch/YouTube — not OGS games (there is
 * no game id to spectate in-app). We surface the list and hand off to the device browser to watch,
 * the same privacy principle as the OAuth Custom Tab: never embed a third-party player (that would
 * pull in a tracker-laden WebView). We also deliberately **ignore** the payload's `thumbnail_url` /
 * `profile_image_url` — they live on Twitch's CDN, so loading them would leak each user's IP/requests
 * to a third party. The list is rendered from OGS' own JSON text only.
 */
private val goTvJson = Json { ignoreUnknownKeys = true }

/** One external live Go stream from GoTV. [channel] + [source] build the watch URL ([streamUrl]). */
data class GoStream(
    val streamId: Long,
    val channel: String,
    val username: String,
    val title: String,
    val viewerCount: Int,
    val language: String,
    /** Platform: "twitch" or "youtube" (lower-cased on parse). */
    val source: String,
)

/**
 * Parse `/api/v1/gotv/streams` (a bare JSON array) into streams. Entries without a `channel` are
 * skipped — without it we can't build a watch URL. Pure, for unit testing.
 */
fun parseGoStreams(body: String): List<GoStream> {
    val arr = goTvJson.parseToJsonElement(body) as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val channel = o["channel"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (channel.isBlank()) return@mapNotNull null
        GoStream(
            streamId = o["stream_id"]?.jsonPrimitive?.longOrNull ?: 0L,
            channel = channel,
            username = o["username"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { channel },
            title = o["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "Live Go stream" },
            viewerCount = o["viewer_count"]?.jsonPrimitive?.intOrNull ?: 0,
            language = o["language"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            source = o["source"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty(),
        )
    }
}

/**
 * The public watch URL for a stream, opened in the device browser. Returns null for a source we don't
 * know how to link (so the UI can omit the action rather than launch a broken link).
 */
fun streamUrl(stream: GoStream): String? = when (stream.source) {
    "twitch" -> "https://www.twitch.tv/${stream.channel}"
    "youtube" -> "https://www.youtube.com/watch?v=${stream.channel}"
    else -> null
}
