package com.splitfree.data.nostr.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Nostr subscription filter (NIP-01).
 */
data class NostrFilter(
    val kinds: List<Int>? = null,
    val authors: List<String>? = null,
    val ids: List<String>? = null,
    val tags: Map<String, List<String>>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null
) {
    fun toJson(): String {
        val obj = JSONObject()
        kinds?.let { obj.put("kinds", JSONArray(it)) }
        authors?.let { obj.put("authors", JSONArray(it)) }
        ids?.let { obj.put("ids", JSONArray(it)) }
        tags?.forEach { (k, v) -> obj.put(k, JSONArray(v)) }
        since?.let { obj.put("since", it) }
        until?.let { obj.put("until", it) }
        limit?.let { obj.put("limit", it) }
        return obj.toString()
    }
}
