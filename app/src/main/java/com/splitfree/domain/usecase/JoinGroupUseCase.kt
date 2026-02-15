package com.splitfree.domain.usecase

import android.util.Base64
import com.splitfree.util.DebugLog as Log
import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.local.entities.OutboxEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.nostr.NostrClient
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.IdentityManager
import com.splitfree.domain.model.Group
import com.splitfree.sync.EventProcessor
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject

class JoinGroupUseCase
    @Inject
    constructor(
        private val groupRepo: GroupRepository,
        private val identity: IdentityManager,
        private val nostrClient: NostrClient,
        private val eventDao: EventDao,
        private val eventProcessor: EventProcessor,
        private val signer: EventSigner,
        private val encryption: GroupEncryption,
        private val outboxDao: OutboxDao,
        private val throttler: EventThrottler,
    ) {
        /**
         * Parse an invite link and join the group.
         * v2 format: https://splitfree.app/join#<compact_base64_payload>
         * v1 legacy: splitfree://join?g=...&k=...&r=...&n=...&exp=...
         */
        suspend operator fun invoke(uri: String): Group {
            Log.i(TAG, "Joining via link: ${uri.take(80)}...")
            val params = parseUri(uri)
            require(params.containsKey("g") && params.containsKey("k")) { "Invalid invite link: missing required parameters" }
            val groupId = String(Base64.decode(params["g"]!!, Base64.URL_SAFE or Base64.NO_WRAP))
            val groupKey = String(Base64.decode(params["k"]!!, Base64.URL_SAFE or Base64.NO_WRAP))
            val relays = (params["r"] ?: "").split(",").filter { it.isNotBlank() }
            val name = URLDecoder.decode(params["n"] ?: "Group", "UTF-8")

            // Validate group ID is a valid UUID
            try {
                java.util.UUID.fromString(groupId)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid group ID format")
            }

            // Validate relay URLs: must be wss:// scheme, reasonable length, max 10 relays
            require(relays.size <= MAX_RELAYS) { "Too many relays in invite link (max $MAX_RELAYS)" }
            for (relay in relays) {
                require(relay.startsWith("wss://") && relay.length <= MAX_RELAY_URL_LENGTH) {
                    "Invalid relay URL: must use wss:// scheme and be under $MAX_RELAY_URL_LENGTH chars"
                }
            }

            check(relays.isNotEmpty()) { "Invite link must contain at least one relay" }
            Log.i(TAG, "Parsed invite: group=$groupId name=$name relays=${relays.size}")

            // Validate invite link expiration
            val expiry = params["exp"]?.toLongOrNull()
            if (expiry != null && System.currentTimeMillis() / 1000 > expiry) {
                throw IllegalStateException("This invite link has expired. Ask the group creator for a new one.")
            }

            val existing = groupRepo.getById(groupId)
            if (existing != null) {
                Log.i(TAG, "Already in group $groupId")
                return existing
            }

            val pubkey = identity.getPublicKeyHex()
            val group =
                Group(
                    id = groupId,
                    name = name,
                    createdBy = "",
                    createdAt = System.currentTimeMillis() / 1000,
                    members = listOf(pubkey),
                    relays = relays,
                )
            groupRepo.save(group, groupKey)

            // Connect, sync existing events, publish our join, then release
            try {
                ensureConnected(group.relays)
                initialSync(group, groupKey)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Initial sync failed: ${e.message}")
            }

            // After initial sync, publish a group_meta that includes ourselves.
            // This announces our join to other members via relays.
            val currentGroup = groupRepo.getById(groupId) ?: group
            val updatedMembers = if (pubkey in currentGroup.members) {
                currentGroup.members
            } else {
                currentGroup.members + pubkey
            }
            groupRepo.updateFromMeta(groupId, currentGroup.name, updatedMembers, currentGroup.relays)
            Log.i(TAG, "Local members after join: ${updatedMembers.map { it.take(8) }}")

            // Publish join announcement while still connected
            publishGroupMeta(groupId, currentGroup.name, currentGroup.createdBy.ifEmpty { pubkey }, currentGroup.createdAt, updatedMembers, currentGroup.relays, groupKey)

            return groupRepo.getById(groupId) ?: group
        }

        private suspend fun ensureConnected(relays: List<String>) {
            if (!nostrClient.isConnected) {
                nostrClient.authSigner = { challenge, relayUrl -> signer.createAuthEvent(challenge, relayUrl) }
                nostrClient.connect(relays)
                Log.i(TAG, "Connected to ${relays.size} relays for join")
            }
        }

        /**
         * Pull all existing events for a newly joined group from relays.
         */
        private suspend fun initialSync(
            group: Group,
            groupKey: String,
        ) {
            val events = nostrClient.fetchEvents(group.id, 0)
            Log.i(TAG, "Initial sync fetched ${events.size} events from relays")
            val existingIds = eventDao.getEventIds(group.id).toSet()
            var count = 0
            for (event in events) {
                if (event.id in existingIds) continue
                val result =
                    eventProcessor.process(
                        rawEvent = event,
                        knownGroupId = group.id,
                        knownGroupKey = groupKey,
                        lenientTimestamp = true,
                    )
                if (result.stored) {
                    Log.d(TAG, "Stored event: type=${result.eventType} from=${result.authorHex?.take(8)}")
                    count++
                }
            }
            if (count > 0) {
                groupRepo.updateLastSync(group.id, System.currentTimeMillis() / 1000)
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
            groupKey: String,
        ) {
            try {
                val metaJson = buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("description", JsonPrimitive(""))
                    put("created_by", JsonPrimitive(createdBy))
                    put("created_at", JsonPrimitive(createdAt))
                    putJsonArray("members") { members.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("relays") { relays.forEach { add(JsonPrimitive(it)) } }
                }.toString()
                val encrypted = encryption.encrypt(metaJson, groupKey)
                val event = signer.createSignedEvent(
                    groupId = groupId,
                    eventType = "group_meta",
                    encryptedContent = encrypted,
                )
                outboxDao.insert(OutboxEntity(eventId = event.id, eventJson = event.toJson(), createdAt = event.createdAt))
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

        private fun parseUri(uri: String): Map<String, String> {
            val query = uri.substringAfter("?", "")
            val params = query.split("&").filter { it.contains("=") }.associate {
                val (k, v) = it.split("=", limit = 2)
                k to v
            }
            // v2 compact format: splitfree://join?d=<base64payload>
            params["d"]?.let { d ->
                val decoded = decodeCompactLink(d)
                if (decoded.isNotEmpty()) return decoded
            }
            // v2 legacy fragment format: splitfree://join#<base64payload>
            val fragment = uri.substringAfter("#", "")
            if (fragment.isNotEmpty() && !fragment.contains("=")) {
                val decoded = decodeCompactLink(fragment)
                if (decoded.isNotEmpty()) return decoded
            }
            // v1 legacy format: splitfree://join?g=...&k=...
            return params
        }

        companion object {
            private const val TAG = "JoinGroupUseCase"

            // Well-known relays — index-based encoding to keep invite links short.
            // Order must never change (append-only). Index 0-based.
            private val KNOWN_RELAYS = listOf(
                "wss://relay.damus.io",
                "wss://nos.lol",
                "wss://relay.primal.net",
                "wss://relay.snort.social",
                "wss://relay.nostr.net",
            )

            /**
             * Creates a compact invite link. Format v2:
             * https://splitfree.app/join#<base64url(version|uuid_bytes|key_bytes|relay_bitmap|exp_bytes|name_bytes)>
             *
             * Using fragment (#) instead of query params — the entire payload is one base64 token,
             * so messaging apps treat it as a single clickable URL with no special chars to break on.
             */
            fun createInviteLink(
                group: Group,
                groupKey: String,
            ): String {
                val uuid = java.util.UUID.fromString(group.id)
                val keyBytes = group.let { groupKey.toByteArray(Charsets.UTF_8) }
                val nameBytes = group.name.toByteArray(Charsets.UTF_8)
                val exp = (System.currentTimeMillis() / 1000 + INVITE_EXPIRY_SECS)

                // Encode relays as a bitmap of known relays + any custom relay strings
                var relayBitmap = 0
                val customRelays = mutableListOf<String>()
                for (relay in group.relays) {
                    val idx = KNOWN_RELAYS.indexOf(relay)
                    if (idx >= 0) relayBitmap = relayBitmap or (1 shl idx)
                    else customRelays.add(relay)
                }
                val customRelayStr = customRelays.joinToString(",")
                val customRelayBytes = customRelayStr.toByteArray(Charsets.UTF_8)

                // Pack: version(1) + uuid(16) + keyLen(1) + key(N) + relayBitmap(1) + customRelayLen(1) + customRelays(N) + exp(4) + name(rest)
                val buf = java.io.ByteArrayOutputStream()
                buf.write(2) // version
                buf.write((uuid.mostSignificantBits ushr 56).toInt() and 0xFF)
                buf.write((uuid.mostSignificantBits ushr 48).toInt() and 0xFF)
                buf.write((uuid.mostSignificantBits ushr 40).toInt() and 0xFF)
                buf.write((uuid.mostSignificantBits ushr 32).toInt() and 0xFF)
                buf.write((uuid.mostSignificantBits ushr 24).toInt() and 0xFF)
                buf.write((uuid.mostSignificantBits ushr 16).toInt() and 0xFF)
                buf.write((uuid.mostSignificantBits ushr 8).toInt() and 0xFF)
                buf.write(uuid.mostSignificantBits.toInt() and 0xFF)
                buf.write((uuid.leastSignificantBits ushr 56).toInt() and 0xFF)
                buf.write((uuid.leastSignificantBits ushr 48).toInt() and 0xFF)
                buf.write((uuid.leastSignificantBits ushr 40).toInt() and 0xFF)
                buf.write((uuid.leastSignificantBits ushr 32).toInt() and 0xFF)
                buf.write((uuid.leastSignificantBits ushr 24).toInt() and 0xFF)
                buf.write((uuid.leastSignificantBits ushr 16).toInt() and 0xFF)
                buf.write((uuid.leastSignificantBits ushr 8).toInt() and 0xFF)
                buf.write(uuid.leastSignificantBits.toInt() and 0xFF)
                buf.write(keyBytes.size.coerceAtMost(255))
                buf.write(keyBytes, 0, keyBytes.size.coerceAtMost(255))
                buf.write(relayBitmap and 0xFF)
                buf.write(customRelayBytes.size.coerceAtMost(255))
                if (customRelayBytes.isNotEmpty()) buf.write(customRelayBytes, 0, customRelayBytes.size.coerceAtMost(255))
                // Expiry as 4-byte big-endian seconds
                val expInt = (exp and 0xFFFFFFFFL).toInt()
                buf.write((expInt ushr 24) and 0xFF)
                buf.write((expInt ushr 16) and 0xFF)
                buf.write((expInt ushr 8) and 0xFF)
                buf.write(expInt and 0xFF)
                buf.write(nameBytes, 0, nameBytes.size.coerceAtMost(100))

                val payload = Base64.encodeToString(buf.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                return "splitfree://join?d=$payload"
            }

            /**
             * Decode a v2 compact invite link. Returns the same parameter map as v1 for compatibility.
             */
            private fun decodeCompactLink(fragment: String): Map<String, String> {
                val data = Base64.decode(fragment, Base64.URL_SAFE or Base64.NO_WRAP)
                if (data.isEmpty() || data[0].toInt() != 2) return emptyMap()
                var pos = 1

                // UUID (16 bytes)
                if (data.size < pos + 16) return emptyMap()
                var msb = 0L
                for (i in 0 until 8) msb = (msb shl 8) or (data[pos + i].toLong() and 0xFF)
                var lsb = 0L
                for (i in 0 until 8) lsb = (lsb shl 8) or (data[pos + 8 + i].toLong() and 0xFF)
                val groupId = java.util.UUID(msb, lsb).toString()
                pos += 16

                // Key
                if (data.size < pos + 1) return emptyMap()
                val keyLen = data[pos].toInt() and 0xFF; pos++
                if (data.size < pos + keyLen) return emptyMap()
                val groupKey = String(data, pos, keyLen, Charsets.UTF_8); pos += keyLen

                // Relay bitmap
                if (data.size < pos + 1) return emptyMap()
                val bitmap = data[pos].toInt() and 0xFF; pos++
                val relays = mutableListOf<String>()
                for (i in KNOWN_RELAYS.indices) {
                    if (bitmap and (1 shl i) != 0) relays.add(KNOWN_RELAYS[i])
                }

                // Custom relays
                if (data.size < pos + 1) return emptyMap()
                val customLen = data[pos].toInt() and 0xFF; pos++
                if (customLen > 0 && data.size >= pos + customLen) {
                    val custom = String(data, pos, customLen, Charsets.UTF_8)
                    relays.addAll(custom.split(",").filter { it.isNotBlank() })
                    pos += customLen
                }

                // Expiry (4 bytes)
                if (data.size < pos + 4) return emptyMap()
                val exp = ((data[pos].toLong() and 0xFF) shl 24) or
                    ((data[pos + 1].toLong() and 0xFF) shl 16) or
                    ((data[pos + 2].toLong() and 0xFF) shl 8) or
                    (data[pos + 3].toLong() and 0xFF)
                pos += 4

                // Name (remaining bytes)
                val name = if (pos < data.size) String(data, pos, data.size - pos, Charsets.UTF_8) else "Group"

                return mapOf(
                    "g" to Base64.encodeToString(groupId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP),
                    "k" to Base64.encodeToString(groupKey.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP),
                    "r" to relays.joinToString(","),
                    "n" to URLEncoder.encode(name, "UTF-8"),
                    "exp" to exp.toString(),
                )
            }

            private const val INVITE_EXPIRY_SECS = 7 * 86400L // 7 days
            private const val MAX_RELAYS = 10
            private const val MAX_RELAY_URL_LENGTH = 256
        }
    }
