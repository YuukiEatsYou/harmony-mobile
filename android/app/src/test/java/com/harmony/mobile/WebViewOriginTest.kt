package com.harmony.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The allowed-origin rule for the injected webview bridges (H1 of the YUC-63
 * hardening), tested on the JVM so the rule is covered without a device:
 * `./gradlew testDebugUnitTest`. The rule is what keeps a non-instance origin
 * from reaching the `HarmonyVoiceHost` bridge and the microphone.
 */
class WebViewOriginTest {

    @Test
    fun httpsOriginDropsTheDefaultPort() {
        assertEquals("https://chat.example.com", WebViewOrigin.ruleFor("https://chat.example.com/"))
        assertEquals("https://chat.example.com", WebViewOrigin.ruleFor("https://chat.example.com:443/"))
    }

    @Test
    fun httpOriginDropsTheDefaultPort() {
        assertEquals("http://localhost", WebViewOrigin.ruleFor("http://localhost/"))
        assertEquals("http://localhost", WebViewOrigin.ruleFor("http://localhost:80/"))
    }

    @Test
    fun nonDefaultPortIsKept() {
        assertEquals("http://192.168.1.10:8080", WebViewOrigin.ruleFor("http://192.168.1.10:8080/"))
        assertEquals("https://chat.example.com:8443", WebViewOrigin.ruleFor("https://chat.example.com:8443/"))
    }

    @Test
    fun thePathAndQueryAreIgnored() {
        assertEquals(
            "https://chat.example.com",
            WebViewOrigin.ruleFor("https://chat.example.com/?channel=c1&message=m2"),
        )
    }

    @Test
    fun schemeAndHostAreNormalizedToLowerCase() {
        assertEquals("https://chat.example.com", WebViewOrigin.ruleFor("HTTPS://Chat.Example.com/"))
    }

    @Test
    fun ipv6LiteralKeepsItsBrackets() {
        assertEquals("http://[::1]:8080", WebViewOrigin.ruleFor("http://[::1]:8080/"))
        assertEquals("https://[::1]", WebViewOrigin.ruleFor("https://[::1]/"))
    }

    @Test
    fun anythingThatIsNotAnHttpOriginFailsClosed() {
        assertNull(WebViewOrigin.ruleFor(null))
        assertNull(WebViewOrigin.ruleFor(""))
        assertNull(WebViewOrigin.ruleFor("   "))
        assertNull(WebViewOrigin.ruleFor("not a url"))
        assertNull(WebViewOrigin.ruleFor("harmony://open?origin=https%3A%2F%2Fchat.example.com"))
        assertNull(WebViewOrigin.ruleFor("ftp://chat.example.com/"))
        assertNull(WebViewOrigin.ruleFor("about:blank"))
        assertNull(WebViewOrigin.ruleFor("mailto:someone@example.com"))
    }
}
