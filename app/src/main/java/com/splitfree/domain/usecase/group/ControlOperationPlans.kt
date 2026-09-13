package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.EventPublisherContract
import kotlinx.serialization.Serializable

@Serializable
internal data class RotationIntent(val group: Group, val removedMember: String, val epoch: Int)

@Serializable
internal data class RevocationIntent(val oldPubkey: String, val groups: List<Group>)

@Serializable
internal data class PreparedControlEvent(val groupId: String, val eventType: String, val eventJson: String) {
    fun event(): NostrEvent = checkNotNull(NostrEvent.fromJson(eventJson)) { "Invalid journal envelope" }

    suspend fun publish(publisher: EventPublisherContract) {
        val event = event()
        publisher.publishDirect(event, groupId, event.content, eventType)
    }
}

@Serializable
internal data class PreparedRevocation(val newPubkey: String, val events: List<PreparedControlEvent>)
