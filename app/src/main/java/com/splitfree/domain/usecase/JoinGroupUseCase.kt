package com.splitfree.domain.usecase

import android.util.Base64
import android.util.Log
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.EventValidator
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.Group
import com.splitfree.domain.model.GroupMeta
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject

class JoinGroupUseCase @Inject constructor(
    private val groupRepo: GroupRepository,
    private val identity: IdentityManager,
    private val nostrClient: NostrClient,
    private val eventDao: EventDao,
    private val encryption: GroupEncryption,
    private val signer: EventSigner
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse an invite link and join the group.
     * Format: splitfree://join?g=<base64url(group_id)>&k=<base64url(group_key)>&r=<relays>&n=<name>
     */
    suspend operator fun invoke(uri: String): Group {
        val params = parseUri(uri)
        val groupId = String(Base64.decode(params["g"]!!, Base64.URL_SAFE or Base64.NO_WRAP))
        val groupKey = String(Base64.decode(params["k"]!!, Base64.URL_SAFE or Base64.NO_WRAP))
        val relays = (params["r"] ?: "").split(",").filter { it.isNotBlank() }
        val name = URLDecoder.decode(params["n"] ?: "Group", "UTF-8")

        check(relays.isNotEmpty()) { "Invite link must contain at least one relay" }

        val existing = groupRepo.getById(groupId)
        if (existing != null) return existing

        val pubkey = identity.getPublicKeyHex()
        val group = Group(
            id = groupId,
            name = name,
            createdBy = "",
            createdAt = System.currentTimeMillis() / 1000,
            members = listOf(pubkey),
            relays = relays
        )
        groupRepo.save(group, groupKey)

        initialSync(group, groupKey)

        return groupRepo.getById(groupId) ?: group
    }

    /**
     * Pull all existing events for a newly joined group from relays.
     */
    private suspend fun initialSync(group: Group, groupKey: String) {
        try {
            val wasConnected = nostrClient.isConnected
            if (!wasConnected) {
                nostrClient.authSigner = { challenge, relayUrl ->
                    val privKey = identity.getPrivateKeyBytes()
                    try {
                        com.splitfree.domain.crypto.NostrEvent(
                            pubkey = identity.getPublicKeyHex(),
                            createdAt = System.currentTimeMillis() / 1000,
                            kind = 22242,
                            tags = listOf(listOf("challenge", challenge), listOf("relay", relayUrl)),
                            content = ""
                        ).sign(privKey)
                    } finally { privKey.fill(0) }
                }
                nostrClient.connect(group.relays)
            }
            nostrClient.acquireConnection()
            try {
                val events = nostrClient.fetchEvents(group.id, 0)
                val existingIds = eventDao.getEventIds(group.id).toSet()
                var count = 0
                for (event in events) {
                    val eventId = event.id
                    if (eventId in existingIds) continue
                    try {
                        if (!signer.verify(event)) continue

                        if (!EventValidator.isTimestampValidLenient(event.createdAt)) {
                            Log.w(TAG, "Rejecting event with invalid timestamp: $eventId")
                            continue
                        }

                        val encrypted = event.content
                        val decrypted = try { encryption.decrypt(encrypted, groupKey) } catch (_: Exception) { null }
                        var eventType = "unknown"
                        var expenseUuid: String? = null
                        for (tag in event.tags) {
                            if (tag.size >= 2) when (tag[0]) {
                                "t" -> eventType = tag[1]
                                "e" -> expenseUuid = tag[1]
                            }
                        }
                        eventDao.insert(EventEntity(
                            eventId = eventId, groupId = group.id,
                            pubkey = event.pubkey,
                            createdAt = event.createdAt,
                            kind = 30078, contentEncrypted = encrypted,
                            contentDecrypted = decrypted, eventType = eventType,
                            expenseUuid = expenseUuid, sig = event.sig,
                            receivedAt = System.currentTimeMillis() / 1000,
                            originalEventJson = event.toJson()
                        ))

                        // Update local group from group_meta events (ISSUE-12)
                        if (eventType == "group_meta" && decrypted != null) {
                            try {
                                val meta = json.decodeFromString<GroupMeta>(decrypted)
                                if (meta.members.isNotEmpty()) {
                                    groupRepo.updateFromMeta(group.id, meta.name, meta.members, meta.relays)
                                }
                            } catch (_: Exception) {}
                        }

                        count++
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to process event during initial sync: ${e.message}")
                    }
                }
                if (count > 0) {
                    groupRepo.updateLastSync(group.id, System.currentTimeMillis() / 1000)
                    Log.i(TAG, "Initial sync pulled $count events for group ${group.name}")
                }
            } finally {
                nostrClient.releaseConnection()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Initial sync failed (will retry on next periodic sync): ${e.message}")
        }
    }

    private fun parseUri(uri: String): Map<String, String> {
        val query = uri.substringAfter("?", "")
        return query.split("&")
            .filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k to v
            }
    }

    companion object {
        private const val TAG = "JoinGroupUseCase"

        fun createInviteLink(group: Group, groupKey: String): String {
            val g = Base64.encodeToString(group.id.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            val k = Base64.encodeToString(groupKey.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            val r = group.relays.joinToString(",")
            val n = URLEncoder.encode(group.name, "UTF-8")
            return "splitfree://join?g=$g&k=$k&r=$r&n=$n"
        }
    }
}
