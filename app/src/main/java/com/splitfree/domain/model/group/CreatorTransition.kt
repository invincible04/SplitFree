package com.splitfree.domain.model.group

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Creator-succession certificate signed by the retiring key, containing the successor's authorization.
 * The retiring signature binds the group and event ID, timestamp and epoch; [successorProof] binds
 * the group and identity pair. Neither signature proves that the encrypted event is available.
 *
 * Carries no historical group key and admits no ledger event. Its event tuple lets the projection
 * deduplicate the certificate with a matching authenticated revocation.
 */
@Serializable
data class CreatorTransition(
    val eventId: String,
    val timestamp: Long,
    val epoch: Int,
    val oldPubkey: String,
    val newPubkey: String,
    val successorProof: String,
    val signature: String
) {
    /** Verifies signatures and field bounds, not root reachability or wall-clock admissibility. */
    fun verify(groupId: String): Boolean = try {
        HEX_KEY.matches(eventId) &&
            HEX_KEY.matches(oldPubkey) &&
            HEX_KEY.matches(newPubkey) &&
            HEX_SIG.matches(signature) &&
            timestamp > 0 &&
            epoch in 0..65535 &&
            KeyRevocation(oldPubkey, newPubkey, successorProof = successorProof).let {
                it.isAuthorizedBy(oldPubkey) && it.provesSuccessor(groupId)
            } &&
            Secp256k1.verifySchnorr(signature.hexToBytes(), digest(groupId), oldPubkey.hexToBytes())
    } catch (_: IllegalArgumentException) {
        false
    }

    /** New external evidence has the same one-hour future allowance as an encrypted event. */
    fun hasAdmissibleTimestamp(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean = nowSeconds >= 0 &&
        timestamp > 0 &&
        (timestamp <= nowSeconds || timestamp - nowSeconds <= 3600L)

    /** Converts verified evidence to a retirement fact; callers must authenticate it before use. */
    fun fact(): GroupControlFact = GroupControlFact(
        eventId,
        "revocation",
        timestamp,
        epoch,
        author = oldPubkey,
        successor = newPubkey,
        proven = true
    )

    private fun digest(groupId: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(
        Json.encodeToString(
            listOf(
                "splitfree/creator-transition/v1",
                groupId,
                eventId,
                timestamp.toString(),
                epoch.toString(),
                oldPubkey,
                newPubkey,
                successorProof
            )
        ).toByteArray(Charsets.UTF_8)
    )

    companion object {
        /** Resource bound for authenticated metadata/backups, distinct from the compact invite budget. */
        const val MAX_TRANSITIONS = 64
        const val MAX_INVITE_TRANSITIONS = 4
        private val HEX_KEY = Regex("[0-9a-f]{64}")
        private val HEX_SIG = Regex("[0-9a-f]{128}")

        fun sign(
            groupId: String,
            event: NostrEvent,
            epoch: Int,
            revocation: KeyRevocation,
            privateKey: ByteArray
        ): CreatorTransition {
            require(
                event.verify() &&
                    event.pubkey == revocation.oldPubkey &&
                    event.pubkey == NostrEvent.pubkeyFromPrivkey(privateKey)
            )
            require(event.tags.filter { it.firstOrNull() == "g" } == listOf(listOf("g", groupId)))
            require(event.tags.filter { it.firstOrNull() == "t" } == listOf(listOf("t", "key_revocation")))
            val unsigned = CreatorTransition(
                event.id,
                event.createdAt,
                epoch,
                revocation.oldPubkey,
                revocation.newPubkey,
                revocation.successorProof,
                ""
            )
            return unsigned.copy(
                signature = Secp256k1.signSchnorr(
                    unsigned.digest(groupId),
                    privateKey,
                    ByteArray(32).also(SecureRandom()::nextBytes)
                ).toHex()
            ).also { require(it.verify(groupId)) }
        }

        /**
         * Checks the original group binding, unique event IDs, signatures and reachability from [root].
         * Forks are allowed. Callers check new timestamps and merge local facts to select current authority.
         */
        fun validate(groupId: String, root: String, createdAt: Long, proofs: List<CreatorTransition>): Boolean {
            if (!GroupIdentity.matches(groupId, root, createdAt) ||
                proofs.size > MAX_TRANSITIONS ||
                proofs.any { !it.verify(groupId) } ||
                proofs.map { it.eventId }.distinct().size != proofs.size
            ) {
                return false
            }
            val reachable = mutableSetOf(root)
            repeat(proofs.size) { proofs.filter { it.oldPubkey in reachable }.forEach { reachable += it.newPubkey } }
            return proofs.all { it.oldPubkey in reachable }
        }
    }
}
