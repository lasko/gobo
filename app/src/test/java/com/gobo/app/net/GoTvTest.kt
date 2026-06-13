package com.gobo.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoTvTest {

    @Test
    fun parsesStreamsWithDefaults() {
        val streams = parseGoStreams(
            """
            [
              {"stream_id":319014128092,"channel":"midnighttheblue","username":"  MidnightTheBlue  ",
               "title":"  3 dan fox games  ","viewer_count":21,"language":"en","source":"Twitch"},
              {"channel":"bob"}
            ]
            """.trimIndent(),
        )
        assertEquals(2, streams.size)
        val s = streams[0]
        assertEquals(319014128092L, s.streamId)
        assertEquals("midnighttheblue", s.channel)
        assertEquals("MidnightTheBlue", s.username) // trimmed
        assertEquals("3 dan fox games", s.title)    // trimmed
        assertEquals(21, s.viewerCount)
        assertEquals("en", s.language)
        assertEquals("twitch", s.source)            // lower-cased
        // Sparse entry: username falls back to channel, title/viewers/source default.
        assertEquals("bob", streams[1].username)
        assertEquals("Live Go stream", streams[1].title)
        assertEquals(0, streams[1].viewerCount)
        assertEquals("", streams[1].source)
    }

    @Test
    fun skipsEntriesWithoutChannel() {
        // No channel -> we can't build a watch URL, so it's dropped.
        val streams = parseGoStreams("""[{"title":"no channel","source":"twitch"},{"channel":"ok"}]""")
        assertEquals(1, streams.size)
        assertEquals("ok", streams[0].channel)
    }

    @Test
    fun emptyOnUnexpectedShape() {
        assertTrue(parseGoStreams("[]").isEmpty())
        assertTrue(parseGoStreams("""{"results":[]}""").isEmpty()) // not a bare array
    }

    @Test
    fun buildsWatchUrlPerSource() {
        fun stream(source: String, channel: String = "abc") =
            GoStream(1, channel, "u", "t", 0, "en", source)
        assertEquals("https://www.twitch.tv/midnighttheblue", streamUrl(stream("twitch", "midnighttheblue")))
        assertEquals("https://www.youtube.com/watch?v=xyz123", streamUrl(stream("youtube", "xyz123")))
        // Unknown source -> no link (the UI omits it rather than launch something broken).
        assertNull(streamUrl(stream("vimeo")))
        assertNull(streamUrl(stream("")))
    }
}
