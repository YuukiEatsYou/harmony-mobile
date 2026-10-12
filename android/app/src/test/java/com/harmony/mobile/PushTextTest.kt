package com.harmony.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The notification wording (YUC-21 §1), tested on the JVM so the mapping is
 * covered without a device. `./gradlew testDebugUnitTest`.
 */
class PushTextTest {
    @Test
    fun mentionLeadsWithTheKindAndNamesTheChannel() {
        val content = PushText.summarize(
            kind = "mention",
            authorName = "Rudy",
            channelName = "general",
            instanceName = "Example",
            snippet = "hey @bob",
        )
        assertEquals("Rudy", content.title)
        assertEquals("Mentioned you in #general: hey @bob", content.body)
        assertEquals("Example", content.subText)
    }

    @Test
    fun replyUsesTheReplyVerb() {
        val content = PushText.summarize("reply", "Roxy", "dev", "Example", "on it")
        assertEquals("Replied to you in #dev: on it", content.body)
    }

    @Test
    fun blankSnippetDropsTheColonAndPreview() {
        val content = PushText.summarize("mention", "Rudy", "general", "Example", null)
        assertEquals("Mentioned you in #general", content.body)
    }

    @Test
    fun blankChannelFallsBackToTheBareVerb() {
        val content = PushText.summarize("reply", "Roxy", "", "Example", "hi")
        assertEquals("Replied to you: hi", content.body)
    }

    @Test
    fun titleFallsBackFromAuthorToChannelToInstanceToApp() {
        assertEquals(
            "general",
            PushText.summarize("mention", "", "general", "Example", null).title,
        )
        assertEquals(
            "Example",
            PushText.summarize("mention", "", "", "Example", null).title,
        )
        assertEquals(
            "Harmony",
            PushText.summarize("mention", "", "", "", null).title,
        )
    }

    @Test
    fun blankInstanceHasNoSubText() {
        assertNull(PushText.summarize("mention", "Rudy", "general", "", "hi").subText)
    }

    @Test
    fun unknownKindIsTreatedAsAMention() {
        assertEquals(
            "Mentioned you in #general",
            PushText.summarize("", "Rudy", "general", "Example", "").body,
        )
    }
}
