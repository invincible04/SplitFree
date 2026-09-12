package com.splitfree.domain.model.sync

/**
 * Relay pool connection status as the UI sees it.
 *
 * `Connecting` covers both an in-flight handshake and the moment before any relay has been asked to
 * connect, so a fresh launch never reads as offline before it has had a chance to connect.
 */
enum class ConnectionStatus {
    Connecting,
    Connected,
    Offline
}
