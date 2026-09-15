package com.splitfree.sync.nearby

import com.splitfree.data.ble.BleEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * Local handle for one transport link, distinct from its reusable endpoint id and authenticated peer key.
 *
 * - The transport associates a fresh instance with each link and validates object identity before side effects.
 * - An ended or unrecognized handle cannot target a replacement; constructing a handle does not register a link.
 */
class NearbyConnection(val endpointId: String)

/**
 * Endpoint lifecycle and byte transport for nearby sessions; discovery and advertising are caller-controlled.
 *
 * - Transport connection does not establish application identity or group authorization.
 * - Sessions use application-level receipts to track processing independently of transport delivery.
 */
interface NearbyTransport {
    /** Subscribe before starting the radio: the production adapter has no event replay. */
    val events: SharedFlow<BleEvent>

    /** Submits only to the current connected link; return confirms neither delivery nor peer application. */
    fun sendPayload(connection: NearbyConnection, data: ByteArray)

    /** Disconnects only this current connection; an ended connection is ignored, never its replacement. */
    fun disconnect(connection: NearbyConnection)
}
