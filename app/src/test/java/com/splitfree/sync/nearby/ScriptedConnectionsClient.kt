package com.splitfree.sync.nearby

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.Task
import io.mockk.every
import io.mockk.mockk

/**
 * Scripted SDK boundary for adapter/stack tests.
 *
 * - Stubbed calls share [Call.sequence]; task-returning calls receive fresh test-settled [FakeTask] instances.
 * - Recorded callback objects permit delivery through ended submissions as well as current ones.
 * - Tasks and lifecycle callbacks are independent; this fake imposes no real SDK ordering or physical radio behavior.
 */
class ScriptedConnectionsClient {
    /** One recorded platform call; [sequence] is shared across every operation. */
    class Call(
        val sequence: Int,
        val operation: String,
        val endpointId: String? = null,
        val task: FakeTask? = null,
        val lifecycle: ConnectionLifecycleCallback? = null,
        val discovery: EndpointDiscoveryCallback? = null,
        val payloadCallback: PayloadCallback? = null,
        val payload: Payload? = null
    )

    /**
     * Hand-settled task invoking registered listeners synchronously on the test's calling thread.
     *
     * - It permits repeated settlement and does not replay completion to listeners registered afterwards.
     */
    class FakeTask {
        val task = mockk<Task<Void>>()
        private val onComplete = mutableListOf<OnCompleteListener<Void>>()
        private val onFailure = mutableListOf<OnFailureListener>()

        /** True once [succeed], [fail] or [cancel] ran. */
        var settled = false
            private set

        init {
            every { task.addOnCompleteListener(capture(onComplete)) } returns task
            every { task.addOnFailureListener(capture(onFailure)) } returns task
            stub(successful = false, canceled = false, exception = null)
        }

        val completionObserved: Boolean get() = onComplete.isNotEmpty()
        val failureObserved: Boolean get() = onFailure.isNotEmpty()

        fun succeed() {
            stub(successful = true, canceled = false, exception = null)
            settled = true
            onComplete.toList().forEach { it.onComplete(task) }
        }

        fun cancel() {
            stub(successful = false, canceled = true, exception = null)
            settled = true
            onComplete.toList().forEach { it.onComplete(task) }
        }

        /** Fails with [ApiException] carrying [statusCode]; completion listeners run before failure listeners. */
        fun fail(statusCode: Int) {
            val exception = ApiException(Status(statusCode))
            stub(successful = false, canceled = false, exception = exception)
            settled = true
            onComplete.toList().forEach { it.onComplete(task) }
            onFailure.toList().forEach { it.onFailure(exception) }
        }

        private fun stub(successful: Boolean, canceled: Boolean, exception: Exception?) {
            every { task.isSuccessful } returns successful
            every { task.isCanceled } returns canceled
            every { task.isComplete } returns (successful || canceled || exception != null)
            every { task.exception } returns exception
        }
    }

    /** The mocked client to hand to `Nearby.getConnectionsClient`. Unstubbed calls fail loudly. */
    val mock: ConnectionsClient = mockk()

    /** Every call in invocation order. */
    val calls = mutableListOf<Call>()

    private var sequence = 0

    val advertisings: List<Call> get() = calls.filter { it.operation == "startAdvertising" }
    val discoveries: List<Call> get() = calls.filter { it.operation == "startDiscovery" }
    val requests: List<Call> get() = calls.filter { it.operation == "requestConnection" }
    val accepts: List<Call> get() = calls.filter { it.operation == "acceptConnection" }
    val rejects: List<Call> get() = calls.filter { it.operation == "rejectConnection" }
    val sends: List<Call> get() = calls.filter { it.operation == "sendPayload" }
    val disconnects: List<Call> get() = calls.filter { it.operation == "disconnectFromEndpoint" }
    val stopAdvertisings: List<Call> get() = calls.filter { it.operation == "stopAdvertising" }
    val stopDiscoveries: List<Call> get() = calls.filter { it.operation == "stopDiscovery" }
    val stopAllEndpoints: List<Call> get() = calls.filter { it.operation == "stopAllEndpoints" }

    /** Inclusive sequence boundary: calls recorded from now on have this sequence or a greater one. */
    val nextSequence: Int get() = sequence + 1

    fun requests(endpointId: String): List<Call> = requests.filter { it.endpointId == endpointId }

    fun disconnects(endpointId: String): List<Call> = disconnects.filter { it.endpointId == endpointId }

    fun rejects(endpointId: String): List<Call> = rejects.filter { it.endpointId == endpointId }

    /** Decoded protocol messages the adapter handed to the platform for [endpointId], in send order. */
    fun messagesTo(endpointId: String): List<NearbyMessage> =
        sends.filter { it.endpointId == endpointId }.mapNotNull { call ->
            call.payload?.asBytes()?.let { (NearbyWire.decode(it) as? NearbyWire.Decoded.Message)?.message }
        }

    init {
        every {
            mock.startAdvertising(
                any<String>(),
                any<String>(),
                any<ConnectionLifecycleCallback>(),
                any<AdvertisingOptions>()
            )
        } answers {
            record("startAdvertising", task = FakeTask(), lifecycle = arg(2)).task!!.task
        }
        every { mock.startDiscovery(any<String>(), any<EndpointDiscoveryCallback>(), any<DiscoveryOptions>()) } answers
            {
                record("startDiscovery", task = FakeTask(), discovery = arg(1)).task!!.task
            }
        every { mock.requestConnection(any<String>(), any<String>(), any<ConnectionLifecycleCallback>()) } answers {
            record("requestConnection", endpointId = arg(1), task = FakeTask(), lifecycle = arg(2)).task!!.task
        }
        every { mock.acceptConnection(any<String>(), any<PayloadCallback>()) } answers {
            record("acceptConnection", endpointId = arg(0), task = FakeTask(), payloadCallback = arg(1)).task!!.task
        }
        every { mock.rejectConnection(any<String>()) } answers {
            record("rejectConnection", endpointId = arg(0), task = FakeTask()).task!!.task
        }
        every { mock.sendPayload(any<String>(), any<Payload>()) } answers {
            record("sendPayload", endpointId = arg(0), task = FakeTask(), payload = arg(1)).task!!.task
        }
        every { mock.disconnectFromEndpoint(any<String>()) } answers {
            record("disconnectFromEndpoint", endpointId = arg(0))
            Unit
        }
        every { mock.stopAdvertising() } answers {
            record("stopAdvertising")
            Unit
        }
        every { mock.stopDiscovery() } answers {
            record("stopDiscovery")
            Unit
        }
        every { mock.stopAllEndpoints() } answers {
            record("stopAllEndpoints")
            Unit
        }
    }

    private fun record(
        operation: String,
        endpointId: String? = null,
        task: FakeTask? = null,
        lifecycle: ConnectionLifecycleCallback? = null,
        discovery: EndpointDiscoveryCallback? = null,
        payloadCallback: PayloadCallback? = null,
        payload: Payload? = null
    ): Call {
        val call = Call(++sequence, operation, endpointId, task, lifecycle, discovery, payloadCallback, payload)
        calls += call
        return call
    }
}
