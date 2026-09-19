package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class RotationIntent(val group: Group, val removedMember: String, val epoch: Int)

@Serializable
internal data class RevocationIntent(val oldPubkey: String, val groups: List<Group>)

@Serializable
internal data class PreparedControlEvent(
    val groupId: String,
    val eventType: String,
    val eventJson: String,
    val projection: PreparedRevocationProjection? = null
) {
    fun event(): NostrEvent = checkNotNull(NostrEvent.fromJson(eventJson)) { "Invalid journal envelope" }

    suspend fun publish(publisher: EventPublisherContract) {
        val event = event()
        publisher.publishDirect(event, groupId, event.content, eventType)
    }
}

@Serializable
internal data class PreparedRevocation(val newPubkey: String, val events: List<PreparedControlEvent>)

@Serializable
internal data class PreparedRevocationProjection(val payload: String, val epoch: Int, val signature: String) {
    fun verify(eventId: String, successor: String): Boolean = try {
        Secp256k1.verifySchnorr(signature.hexToBytes(), digest(eventId, payload, epoch), successor.hexToBytes())
    } catch (_: IllegalArgumentException) {
        false
    }

    companion object {
        fun authenticate(
            eventId: String,
            payload: String,
            epoch: Int,
            privateKey: ByteArray
        ): PreparedRevocationProjection = PreparedRevocationProjection(
            payload,
            epoch,
            Secp256k1.signSchnorr(
                digest(eventId, payload, epoch),
                privateKey,
                ByteArray(32).also(SecureRandom()::nextBytes)
            )
                .toHex()
        )

        private fun digest(eventId: String, payload: String, epoch: Int): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(
                Json.encodeToString(
                    listOf("splitfree:revocation-projection:v1", eventId, epoch.toString(), payload)
                ).toByteArray(Charsets.UTF_8)
            )
    }
}
