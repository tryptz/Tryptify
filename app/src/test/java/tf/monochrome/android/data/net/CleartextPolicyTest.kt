// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** http:// for servers at home only, and a plain password never over it. */
class CleartextPolicyTest {

    @Test
    fun httpsIsAlwaysFine() {
        assertNull(CleartextPolicy.refusal("https://music.example.com/rest/ping?p=enc:00"))
        assertNull(CleartextPolicy.refusal("wss://connect.tryptify.com/v1/connect"))
    }

    @Test
    fun httpToTheInternetIsRefused() {
        assertNotNull(CleartextPolicy.refusal("http://music.example.com/rest/ping"))
        assertNotNull(CleartextPolicy.refusal("http://8.8.8.8:4533/"))
        assertNotNull(CleartextPolicy.refusal("HTTP://Music.Example.com/"))
        // A home-looking name in the user info or the path is not the host.
        assertNotNull(CleartextPolicy.refusal("http://192.168.1.2@evil.example.com/"))
        assertNotNull(CleartextPolicy.refusal("http://evil.example.com/192.168.1.2"))
        assertNotNull(CleartextPolicy.refusal("http://192.168.1.2.evil.example.com/"))
    }

    @Test
    fun httpAtHomeIsFine() {
        for (url in listOf(
            "http://localhost:4533/", "http://127.0.0.1/", "http://10.0.0.5/", "http://172.16.0.1/", "http://172.31.255.1/",
            "http://192.168.1.20:4533/rest/ping?t=abc&s=def", "http://169.254.3.4/", "http://100.101.102.103/",
            "http://nas.local/", "http://nas.lan:8080/", "http://nas.home.arpa/", "http://box.internal/",
            "http://[::1]:4533/", "http://[fd12:3456::1]/", "http://[fe80::1%25wlan0]/",
        )) assertNull(url, CleartextPolicy.refusal(url))
    }

    @Test
    fun theEdgesOfTheHomeRangesAreNotHome() {
        assertFalse(CleartextPolicy.isAtHome("172.32.0.1"))
        assertFalse(CleartextPolicy.isAtHome("100.128.0.1"))
        assertFalse(CleartextPolicy.isAtHome("192.169.0.1"))
        assertFalse(CleartextPolicy.isAtHome("1.2.3"))
        assertFalse(CleartextPolicy.isAtHome("2001:db8::1"))
        assertFalse(CleartextPolicy.isAtHome(""))
        assertTrue(CleartextPolicy.isAtHome("nas.local."))
    }

    @Test
    fun aPlainPasswordNeverGoesOverHttpEvenAtHome() {
        assertNotNull(CleartextPolicy.refusal("http://192.168.1.20/rest/ping?u=me&p=enc:6869"))
        assertNull(CleartextPolicy.refusal("http://192.168.1.20/rest/ping?u=me&t=abc&s=def"))
    }

    @Test
    fun mediaWithoutASignInMayUseHttp() {
        // An internet radio station, a cover on a plain-http site.
        assertNull(CleartextPolicy.mediaRefusal("http://stream.radio.example.net:8000/live.mp3"))
        assertNull(CleartextPolicy.mediaRefusal("http://img.example.net/cover.jpg?w=300"))
        // A music server's stream carries its token: home only.
        assertNotNull(CleartextPolicy.mediaRefusal("http://music.example.com/rest/stream?id=1&u=me&t=abc&s=def"))
        assertNull(CleartextPolicy.mediaRefusal("http://192.168.1.20/rest/stream?id=1&u=me&t=abc&s=def"))
        assertNotNull(CleartextPolicy.mediaRefusal("http://192.168.1.20/rest/stream?id=1&u=me&p=enc:6869"))
    }

    @Test
    fun theHostIsReadLikeABrowserWould() {
        assertEquals("nas.local", CleartextPolicy.hostOf("http://me:pw@NAS.local:4533/x?y#z"))
        assertEquals("fd00::1", CleartextPolicy.hostOf("http://[fd00::1]:80/"))
    }
}
