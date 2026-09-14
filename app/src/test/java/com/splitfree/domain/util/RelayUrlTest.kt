package com.splitfree.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayUrlTest {
    @Test
    fun `scheme and host are lowercased while path and query keep their case and bytes`() {
        assertEquals("wss://relay.example/TeamA?token=AbC/", RelayUrl.normalize("WSS://Relay.Example/TeamA?token=AbC/"))
    }

    @Test
    fun `a bare authority with a root slash drops the slash`() {
        assertEquals("wss://host", RelayUrl.normalize("WSS://HOST/"))
        assertEquals("wss://host", RelayUrl.normalize("wss://host"))
    }

    @Test
    fun `a non-root path keeps its trailing slash`() {
        assertEquals("wss://host/room/", RelayUrl.normalize("wss://host/room/"))
        assertEquals("wss://host/room", RelayUrl.normalize("wss://host/room"))
    }

    @Test
    fun `a root slash is kept when a query or fragment follows it`() {
        assertEquals("wss://host/?a=b", RelayUrl.normalize("wss://host/?a=b"))
        assertEquals("wss://host/#frag", RelayUrl.normalize("wss://host/#frag"))
    }

    @Test
    fun `port is kept`() {
        assertEquals("wss://host:8443/x", RelayUrl.normalize("wss://host:8443/x"))
    }

    @Test
    fun `userinfo is kept`() {
        assertEquals("wss://user@host/x", RelayUrl.normalize("wss://user@host/x"))
        assertEquals("wss://User:P%40ss@host", RelayUrl.normalize("wss://User:P%40ss@HOST"))
    }

    @Test
    fun `percent-encoding in the path is untouched`() {
        assertEquals("wss://host/a%2Fb", RelayUrl.normalize("wss://host/a%2Fb"))
    }

    @Test
    fun `a query with an empty path is kept`() {
        assertEquals("wss://host?a=b", RelayUrl.normalize("wss://host?a=b"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("wss://host/x", RelayUrl.normalize("  wss://host/x\n"))
    }

    @Test
    fun `IPv6 literal hosts are kept in brackets`() {
        assertEquals("wss://[::1]:7777", RelayUrl.normalize("wss://[::1]:7777"))
    }

    @Test
    fun `other schemes are refused`() {
        assertNull(RelayUrl.normalize("ws://host"))
        assertNull(RelayUrl.normalize("https://host"))
        assertNull(RelayUrl.normalize("host/path"))
    }

    @Test
    fun `a URL without a host is refused`() {
        assertNull(RelayUrl.normalize("wss://"))
        assertNull(RelayUrl.normalize("wss:///path"))
        assertNull(RelayUrl.normalize("wss:host"))
    }

    @Test
    fun `text that is not a URL is refused`() {
        assertNull(RelayUrl.normalize("not a url"))
        assertNull(RelayUrl.normalize(""))
        assertNull(RelayUrl.normalize("   "))
    }
}
