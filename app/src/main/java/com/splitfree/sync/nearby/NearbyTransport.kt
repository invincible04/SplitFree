package com.splitfree.sync.nearby

import com.splitfree.data.ble.BleEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * Endpoint lifecycle and byte transport for nearby sessions; discovery and advertising are caller-controlled.
 * Transport connection does not establish application identity or group authorization.
 * Sessions use application-level receipts to track processing independently of transport delivery.
 */
interface NearbyTransport {
    val events: SharedFlow<BleEvent>

    /** Submits bytes asynchronously; return does not confirm delivery or peer application. */
    fun sendPayload(endpointId: String, data: ByteArray)

    /** Requests endpoint disconnection; the session owner remains responsible for protocol cleanup. */
    fun disconnect(endpointId: String)
}
