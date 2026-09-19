package com.splitfree.domain.model.group

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.util.toHex
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class IdentityHistoryPage(
    val groupId: String,
    val oldPubkey: String,
    val newPubkey: String,
    val revocationEventJson: String,
    val root: String,
    val eventCount: Int,
    val pageIndex: Int,
    val eventIds: List<String>,
    val version: Int = 1
) {
    val pageCount: Int get() = maxOf(1, (eventCount + PAGE_SIZE - 1) / PAGE_SIZE)
    val revocation: NostrEvent? get() = NostrEvent.fromJson(revocationEventJson)

    fun hasValidShape(): Boolean = version == 1 &&
        HEX.matches(oldPubkey) &&
        HEX.matches(newPubkey) &&
        oldPubkey != newPubkey &&
        HEX.matches(root) &&
        eventCount in 0..MAX_EVENTS &&
        pageIndex in 0 until pageCount &&
        eventIds.size == minOf(PAGE_SIZE, eventCount - pageIndex * PAGE_SIZE) &&
        eventIds.all(HEX::matches) &&
        eventIds == eventIds.distinct().sorted() &&
        revocationEventJson.toByteArray(Charsets.UTF_8).size <= MAX_REVOCATION_BYTES

    companion object {
        const val TYPE = "identity_history"
        const val PAGE_SIZE = 128
        const val MAX_EVENTS = PAGE_SIZE * 4096
        const val MAX_REVOCATION_BYTES = 8192
        val MONEY_TYPES = setOf("expense", "expense_correction", "expense_delete", "settlement")
        private val HEX = Regex("[0-9a-f]{64}")

        fun root(eventIds: List<String>): String = MessageDigest.getInstance("SHA-256").digest(
            Json.encodeToString(listOf("splitfree:identity-history:v1") + eventIds).toByteArray(Charsets.UTF_8)
        ).toHex()

        fun create(
            groupId: String,
            old: String,
            successor: String,
            revocation: NostrEvent,
            ids: List<String>
        ): List<IdentityHistoryPage> {
            require(ids.size <= MAX_EVENTS) { "Identity history exceeds the authenticated page limit" }
            val ordered = ids.distinct().sorted()
            require(ordered.size == ids.size && ordered.all(HEX::matches)) { "Invalid historical event IDs" }
            val root = root(ordered)
            val pages = ordered.chunked(PAGE_SIZE).ifEmpty { listOf(emptyList()) }
            return pages.mapIndexed { index, page ->
                IdentityHistoryPage(groupId, old, successor, revocation.toJson(), root, ordered.size, index, page)
            }
        }
    }
}
