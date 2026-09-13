package com.splitfree.sync.nearby

import com.splitfree.data.ble.BleEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * The transport the nearby session engine runs on. Google Nearby Connections implements it in
 * production ([com.splitfree.data.ble.NearbySync]); tests use an in-memory pair.
 *
 * The engine only needs three things from a transport: connection lifecycle and payload events,
 * a way to send bytes to an endpoint, and a way to drop an endpoint. Discovery and advertising stay
 * on the concrete transport because they are UI-driven.
 */
interface NearbyTransport {
    val events: SharedFlow<BleEvent>

    /**
     * Hand [data] to the endpoint. Delivery is asynchronous; the engine relies on application-level
     * receipts, never on the return of this call, to learn what was applied.
     */
    fun sendPayload(endpointId: String, data: ByteArray)

    fun disconnect(endpointId: String)
}
