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
import com.splitfree.domain.crypto.hexToBytes
import com.splitfree.domain.crypto.toHex
import com.splitfree.domain.model.Group
import com.splitfree.sync.EventProcessor
import kotlinx.coroutines.delay
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
        private val selfHeal: SelfHealUseCase,
    ) {
        /**
         * Parse an invite link and join the group.
         * v3 format: splitfree://join?d=<compact_base64_payload> (ephemeral key exchange, no group key in URL)
         * v2 format: splitfree://join?d=<compact_base64_payload> (legacy, group key in URL)
         * v1 legacy: splitfree://join?g=...&k=...&r=...&n=...&exp=...
         */
        suspend operator fun invoke(uri: String): Group {
            Log.i(TAG, "Joining via link: ${uri.take(80)}...")
            val params = parseUri(uri)
            require(params.containsKey("g")) { "Invalid invite link: missing group ID" }
            val groupId = String(Base64.decode(params["g"]!!, Base64.URL_SAFE or Base64.NO_WRAP))
            val relays = (params["r"] ?: "").split(",").filter { it.isNotBlank() }
            val name = URLDecoder.decode(params["n"] ?: "Group", "UTF-8")

            // v3 links carry an ephemeral private key; v2/v1 carry the group key directly
            val ephemeralPrivHex = params["eph"]
            val groupKey: String

            if (ephemeralPrivHex != null) {
                // v3: fetch key_delivery event from relay using ephemeral key
                require(relays.isNotEmpty()) { "Invite link must contain at least one relay" }
                groupKey = fetchGroupKeyViaEphemeral(ephemeralPrivHex, groupId, relays)
            } else {
                require(params.containsKey("k")) { "Invalid invite link: missing key" }
                groupKey = String(Base64.decode(params["k"]!!, Base64.URL_SAFE or Base64.NO_WRAP))
            }

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
                selfHeal(groupId)
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
         * v3 key exchange: use the ephemeral private key from the invite link to
         * decrypt a pre-published key_delivery gift wrap from the relay.
         */
        private suspend fun fetchGroupKeyViaEphemeral(
            ephemeralPrivHex: String,
            groupId: String,
            relays: List<String>,
        ): String {
            ensureConnected(relays)
            // Also add fallback relays — the sender may have published to a fallback
            com.splitfree.data.nostr.RelayConfig.FALLBACK_RELAYS.forEach { nostrClient.addRelay(it) }
            delay(1500) // allow fallback relays to connect
            val ephPriv = ephemeralPrivHex.hexToBytes()
            val ephPub = com.splitfree.domain.crypto.NostrEvent.pubkeyFromPrivkey(ephPriv)
            try {
                // Fetch kind 1059 events addressed to the ephemeral pubkey
                val events = nostrClient.fetchGiftWraps(ephPub)
                for (event in events) {
                    val convKey = com.splitfree.domain.crypto.Nip44.getConversationKey(ephPriv, event.pubkey.hexToBytes())
                    val sealJson = try { com.splitfree.domain.crypto.Nip44.decrypt(event.content, convKey) } catch (_: Exception) { continue }
                    val seal = com.splitfree.domain.crypto.NostrEvent.fromJson(sealJson) ?: continue
                    if (seal.kind != 13) continue
                    val sealConvKey = com.splitfree.domain.crypto.Nip44.getConversationKey(ephPriv, seal.pubkey.hexToBytes())
                    val rumorJson = try { com.splitfree.domain.crypto.Nip44.decrypt(seal.content, sealConvKey) } catch (_: Exception) { continue }
                    val rumor = com.splitfree.domain.crypto.NostrEvent.fromJson(rumorJson) ?: continue
                    // Verify this is a key_delivery for our group
                    val gTag = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.get(1)
                    val tTag = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "t" }?.get(1)
                    if (gTag == groupId && tTag == "key_delivery") {
                        Log.i(TAG, "Received group key via ephemeral key exchange for $groupId")
                        return rumor.content
                    }
                }
                throw IllegalStateException("Could not retrieve group key. The invite link may have expired or the key delivery event was not found on relays.")
            } finally {
                ephPriv.fill(0)
            }
        }

        /**
         * Pull all existing events for a newly joined group from relays.
         */
        private suspend fun initialSync(
            group: Group,
            groupKey: String,
        ) {
            val events = nostrClient.fetchEvents(group.id, 0, identity.getPublicKeyHex())
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
            private val KNOWN_RELAYS = com.splitfree.data.nostr.RelayConfig.KNOWN_RELAYS

            /**
             * Creates an invite link.
             *
             * When [senderPrivKey] is provided: v3 format with ephemeral key exchange (CWE-319 fix).
             * The group key is NOT in the URL. Instead, an ephemeral secp256k1 keypair
             * is generated: the private key goes in the link, and a NIP-59 gift-wrapped
             * key_delivery event (encrypted to the ephemeral pubkey) is returned for publishing.
             *
             * When [senderPrivKey] is null: v2 legacy format with group key in URL.
             *
             * @return Pair of (link URL, key delivery event to publish or null)
             */
            fun createInviteLink(
                group: Group,
                groupKey: String,
                senderPrivKey: ByteArray? = null,
            ): Pair<String, com.splitfree.domain.crypto.NostrEvent?> {
                val uuid = java.util.UUID.fromString(group.id)
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

                val buf = java.io.ByteArrayOutputStream()
                var keyDeliveryEvent: com.splitfree.domain.crypto.NostrEvent? = null

                if (senderPrivKey != null) {
                    // v3: ephemeral key exchange
                    val ephPriv = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(ephPriv)) {
                        java.security.SecureRandom().nextBytes(ephPriv)
                    }
                    val ephPubHex = com.splitfree.domain.crypto.NostrEvent.pubkeyFromPrivkey(ephPriv)

                    val rumor = com.splitfree.domain.crypto.NostrEvent(
                        pubkey = com.splitfree.domain.crypto.NostrEvent.pubkeyFromPrivkey(senderPrivKey),
                        createdAt = System.currentTimeMillis() / 1000,
                        kind = 30078,
                        tags = listOf(listOf("g", group.id), listOf("t", "key_delivery")),
                        content = groupKey,
                    )
                    keyDeliveryEvent = com.splitfree.domain.crypto.Nip59.giftWrap(
                        rumor = rumor.copy(sig = ""),
                        senderPrivKey = senderPrivKey,
                        recipientPubKey = ephPubHex.hexToBytes(),
                    )

                    buf.write(3) // version 3
                    writeUuid(buf, uuid)
                    buf.write(ephPriv)
                    ephPriv.fill(0)
                } else {
                    // v2: group key in URL (legacy / testing)
                    val keyBytes = groupKey.toByteArray(Charsets.UTF_8)
                    buf.write(2) // version 2
                    writeUuid(buf, uuid)
                    buf.write(keyBytes.size.coerceAtMost(255))
                    buf.write(keyBytes, 0, keyBytes.size.coerceAtMost(255))
                }

                buf.write(relayBitmap and 0xFF)
                buf.write(customRelayBytes.size.coerceAtMost(255))
                if (customRelayBytes.isNotEmpty()) buf.write(customRelayBytes, 0, customRelayBytes.size.coerceAtMost(255))
                val expInt = (exp and 0xFFFFFFFFL).toInt()
                buf.write((expInt ushr 24) and 0xFF)
                buf.write((expInt ushr 16) and 0xFF)
                buf.write((expInt ushr 8) and 0xFF)
                buf.write(expInt and 0xFF)
                buf.write(nameBytes, 0, nameBytes.size.coerceAtMost(100))

                val payload = Base64.encodeToString(buf.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                return "splitfree://join?d=$payload" to keyDeliveryEvent
            }

            private fun writeUuid(buf: java.io.ByteArrayOutputStream, uuid: java.util.UUID) {
                for (shift in listOf(56, 48, 40, 32, 24, 16, 8, 0)) {
                    buf.write((uuid.mostSignificantBits ushr shift).toInt() and 0xFF)
                }
                for (shift in listOf(56, 48, 40, 32, 24, 16, 8, 0)) {
                    buf.write((uuid.leastSignificantBits ushr shift).toInt() and 0xFF)
                }
            }

            /**
             * Decode a v2 or v3 compact invite link.
             * Returns the same parameter map as v1 for compatibility.
             * v3 adds "eph" key (ephemeral private key hex) instead of "k".
             */
            private fun decodeCompactLink(fragment: String): Map<String, String> {
                val data = Base64.decode(fragment, Base64.URL_SAFE or Base64.NO_WRAP)
                if (data.isEmpty()) return emptyMap()
                val version = data[0].toInt() and 0xFF
                if (version != 2 && version != 3) return emptyMap()
                var pos = 1

                // UUID (16 bytes)
                if (data.size < pos + 16) return emptyMap()
                var msb = 0L
                for (i in 0 until 8) msb = (msb shl 8) or (data[pos + i].toLong() and 0xFF)
                var lsb = 0L
                for (i in 0 until 8) lsb = (lsb shl 8) or (data[pos + 8 + i].toLong() and 0xFF)
                val groupId = java.util.UUID(msb, lsb).toString()
                pos += 16

                val result = mutableMapOf(
                    "g" to Base64.encodeToString(groupId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP),
                )

                if (version == 2) {
                    // v2: keyLen(1) + key(N)
                    if (data.size < pos + 1) return emptyMap()
                    val keyLen = data[pos].toInt() and 0xFF; pos++
                    if (data.size < pos + keyLen) return emptyMap()
                    val groupKey = String(data, pos, keyLen, Charsets.UTF_8); pos += keyLen
                    result["k"] = Base64.encodeToString(groupKey.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                } else {
                    // v3: ephPriv(32 bytes)
                    if (data.size < pos + 32) return emptyMap()
                    val ephPriv = data.copyOfRange(pos, pos + 32); pos += 32
                    result["eph"] = ephPriv.toHex()
                    ephPriv.fill(0)
                }

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

                result["r"] = relays.joinToString(",")
                result["n"] = URLEncoder.encode(name, "UTF-8")
                result["exp"] = exp.toString()
                return result
            }

            private const val INVITE_EXPIRY_SECS = 24 * 3600L // 24 hours (reduced from 7 days)
            private const val MAX_RELAYS = 10
            private const val MAX_RELAY_URL_LENGTH = 256
        }
    }
