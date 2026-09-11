package com.splitfree.ui.util

/**
 * Display label for a group member that stays unambiguous when two members share a display name.
 *
 * The label is the member's display name (or the first 8 characters of the pubkey when there is no
 * name). If another key in [everyone] resolves to the same label, a pubkey suffix is appended,
 * lengthened from 8 characters until it is unique among the colliding keys, so the real repository
 * identity is never replaced, only annotated.
 *
 * @param pubkey the member to label
 * @param memberNames pubkey → display name
 * @param everyone every pubkey that can appear alongside [pubkey] (members, participants, payer…)
 * @param youLabel label to use for [myPubkey] instead of its display name, or null to not special-case it
 * @param myPubkey the current user's pubkey; only used when [youLabel] is non-null
 */
fun disambiguatedMemberName(
    pubkey: String,
    memberNames: Map<String, String>,
    everyone: Collection<String>,
    youLabel: String? = null,
    myPubkey: String = ""
): String {
    fun label(key: String): String = if (youLabel != null && key == myPubkey) {
        youLabel
    } else {
        memberNames[key]?.takeIf { it.isNotBlank() } ?: key.take(SHORT_KEY_LENGTH)
    }
    val name = label(pubkey)
    val collisions = (everyone + pubkey).distinct().filter { label(it) == name }
    if (collisions.size < 2) return name
    val length = (SHORT_KEY_LENGTH..pubkey.length.coerceAtLeast(SHORT_KEY_LENGTH)).firstOrNull { size ->
        collisions.count { it.takeLast(size) == pubkey.takeLast(size) } == 1
    } ?: pubkey.length
    return "$name · ${pubkey.takeLast(length)}"
}

private const val SHORT_KEY_LENGTH = 8
