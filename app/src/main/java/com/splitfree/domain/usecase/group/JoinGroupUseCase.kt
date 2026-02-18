package com.splitfree.domain.usecase.group

import android.util.Base64
import com.splitfree.data.local.dao.OutboxDao
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.sync.worker.SyncEngine
import com.splitfree.util.DebugLog as Log
import com.splitfree.util.hexToBytes
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * Parses an invite link, retrieves the group key, syncs existing events, and joins the group.
 */
class JoinGroupUseCase
@Inject
constructor(
    private val groupRepo: GroupRepositoryContract,
    private val identity: IdentityManager,
    private val nostrClient: NostrClient,
    private val signer: EventSigner,
    private val encryption: GroupEncryption,
    private val outboxDao: OutboxDao,
    private val selfHeal: SelfHealUseCase,
    private val syncEngine: SyncEngine
) {
    /**
     * Parse an invite link and join the group.
     * v3 format: splitfree://join?d=<compact_base64_payload> (ephemeral key exchange, no group key in URL)
     * v2 format: splitfree://join?d=<compact_base64_payload> (legacy, group key in URL)
     * v1 legacy: splitfree://join?g=...&k=...&r=...&n=...&exp=...
     */
    suspend operator fun invoke(uri: String): Group {
        Log.i(TAG, "Joining via link: ${uri.take(80)}...")
        val invite = InviteLinkCodec.decode(uri)

        // Validate group ID is a valid UUID
        try {
            java.util.UUID.fromString(invite.groupId)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid group ID format")
        }

        check(invite.relays.isNotEmpty()) { "Invite link must contain at least one relay" }

        // Validate invite link expiration
        if (invite.expiry != null && System.currentTimeMillis() / 1000 > invite.expiry) {
            throw IllegalStateException("This invite link has expired. Ask the group creator for a new one.")
        }

        // v3 links carry an ephemeral private key; v2/v1 carry the group key directly
        val groupKey: String =
            if (invite.ephemeralPrivHex != null) {
                fetchGroupKeyViaEphemeral(invite.ephemeralPrivHex, invite.groupId, invite.relays)
            } else {
                require(invite.groupKeyBase64 != null) { "Invalid invite link: missing key" }
                String(Base64.decode(invite.groupKeyBase64, Base64.URL_SAFE or Base64.NO_WRAP))
            }

        Log.i(TAG, "Parsed invite: group=${invite.groupId} name=${invite.name} relays=${invite.relays.size}")

        val existing = groupRepo.getById(invite.groupId)
        if (existing != null) {
            Log.i(TAG, "Already in group ${invite.groupId}")
            return existing
        }

        val pubkey = identity.getPublicKeyHex()
        val group =
            Group(
                id = invite.groupId,
                name = invite.name,
                createdBy = "",
                createdAt = System.currentTimeMillis() / 1000,
                members = listOf(pubkey),
                relays = invite.relays
            )
        groupRepo.save(group, groupKey)

        // Connect, sync existing events, publish our join, then release
        try {
            ensureConnected(group.relays)
            initialSync(group, groupKey)
            selfHeal(group.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Initial sync failed: ${e.message}")
        }

        // After initial sync, publish a group_meta that includes ourselves.
        // This announces our join to other members via relays.
        val currentGroup = groupRepo.getById(group.id) ?: group
        val updatedMembers =
            if (pubkey in currentGroup.members) {
                currentGroup.members
            } else {
                currentGroup.members + pubkey
            }
        groupRepo.updateFromMeta(group.id, currentGroup.name, updatedMembers, currentGroup.relays)
        Log.i(TAG, "Local members after join: ${updatedMembers.map { it.take(8) }}")

        // Publish join announcement while still connected
        publishGroupMeta(
            group.id,
            currentGroup.name,
            currentGroup.createdBy.ifEmpty {
                pubkey
            },
            currentGroup.createdAt,
            updatedMembers,
            currentGroup.relays,
            groupKey
        )

        return groupRepo.getById(group.id) ?: group
    }

    private suspend fun ensureConnected(relays: List<String>) {
        if (!nostrClient.isConnected) {
            nostrClient.authSigner = { challenge, relayUrl -> signer.createAuthEvent(challenge, relayUrl) }
            nostrClient.connect(relays)
            Log.i(TAG, "Connected to ${relays.size} relays for join")
        }
    }

    /**
     * v3 key exchange: use the ephemeral private key from the invite link to
     * decrypt a pre-published key_delivery gift wrap from the relay.
     */
    private suspend fun fetchGroupKeyViaEphemeral(
        ephemeralPrivHex: String,
        groupId: String,
        relays: List<String>
    ): String {
        ensureConnected(relays)
        // Also add fallback relays — the sender may have published to a fallback
        com.splitfree.data.nostr.RelayConfig.FALLBACK_RELAYS
            .forEach { nostrClient.addRelay(it) }
        delay(1500) // allow fallback relays to connect
        val ephPriv = ephemeralPrivHex.hexToBytes()
        val ephPub =
            com.splitfree.domain.crypto.NostrEvent
                .pubkeyFromPrivkey(ephPriv)
        try {
            // Fetch kind 1059 events addressed to the ephemeral pubkey
            val events = nostrClient.fetchGiftWraps(ephPub)
            for (event in events) {
                val convKey =
                    com.splitfree.domain.crypto.nip.Nip44
                        .getConversationKey(ephPriv, event.pubkey.hexToBytes())
                val sealJson =
                    try {
                        com.splitfree.domain.crypto.nip.Nip44
                            .decrypt(event.content, convKey)
                    } catch (
                        _: Exception
                    ) {
                        continue
                    }
                val seal =
                    com.splitfree.domain.crypto.NostrEvent
                        .fromJson(sealJson) ?: continue
                if (seal.kind != 13) continue
                val sealConvKey =
                    com.splitfree.domain.crypto.nip.Nip44
                        .getConversationKey(ephPriv, seal.pubkey.hexToBytes())
                val rumorJson =
                    try {
                        com.splitfree.domain.crypto.nip.Nip44
                            .decrypt(seal.content, sealConvKey)
                    } catch (
                        _: Exception
                    ) {
                        continue
                    }
                val rumor =
                    com.splitfree.domain.crypto.NostrEvent
                        .fromJson(rumorJson) ?: continue
                // Verify this is a key_delivery for our group
                val gTag = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.get(1)
                val tTag = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "t" }?.get(1)
                if (gTag == groupId && tTag == "key_delivery") {
                    Log.i(TAG, "Received group key via ephemeral key exchange for $groupId")
                    return rumor.content
                }
            }
            throw IllegalStateException(
                "Could not retrieve group key. The invite link may have expired or the key delivery event was not found on relays."
            )
        } finally {
            ephPriv.fill(0)
        }
    }

    /**
     * Pull all existing events for a newly joined group from relays.
     */
    private suspend fun initialSync(group: Group, groupKey: String) {
        val count = syncEngine.pullEvents(group.id, 0, groupKey, lenientTimestamp = true)
        if (count > 0) {
            Log.i(TAG, "Initial sync stored $count events for group ${group.name}")
        } else {
            Log.i(TAG, "Initial sync: no new events found")
        }
    }

    private suspend fun publishGroupMeta(
        groupId: String,
        name: String,
        createdBy: String,
        createdAt: Long,
        members: List<String>,
        relays: List<String>,
        groupKey: String
    ) {
        try {
            val metaJson =
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("description", JsonPrimitive(""))
                    put("created_by", JsonPrimitive(createdBy))
                    put("created_at", JsonPrimitive(createdAt))
                    putJsonArray("members") { members.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("relays") { relays.forEach { add(JsonPrimitive(it)) } }
                }.toString()
            val encrypted = encryption.encrypt(metaJson, groupKey)
            val event =
                signer.createSignedEvent(
                    groupId = groupId,
                    eventType = "group_meta",
                    encryptedContent = encrypted
                )
            outboxDao.insert(
                OutboxEntity(eventId = event.id, eventJson = event.toJson(), createdAt = event.createdAt)
            )
            // Publish directly while we still have a connection (throttler is async and may fire after disconnect)
            val published = nostrClient.publish(event)
            if (published) {
                outboxDao.delete(event.id)
                Log.i(TAG, "Published group_meta with ${members.size} members for group $groupId")
            } else {
                Log.w(TAG, "Direct publish failed — outbox will retry via SyncWorker")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish group_meta on join: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "JoinGroupUseCase"
    }
}
