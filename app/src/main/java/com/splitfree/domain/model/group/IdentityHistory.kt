package com.splitfree.domain.model.group

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.Settlement
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.util.toHex
import com.splitfree.domain.validation.EventValidator
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Evidence admission is separate from money eligibility: incomplete pages never authorize a ledger. */
class IdentityHistory(private val groups: GroupRepositoryContract, private val encryption: GroupEncryption) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun authenticate(event: NostrEvent, groupId: String, epoch: Int): IdentityHistoryPage? {
        if (event.content.length > 65536 ||
            !event.verify() ||
            event.kind != 30078 ||
            event.tags.filter { it.firstOrNull() == "g" } != listOf(listOf("g", groupId)) ||
            event.tags.filter { it.firstOrNull() == "t" } != listOf(listOf("t", IdentityHistoryPage.TYPE))
        ) {
            return null
        }
        val key = groups.getGroupKeyForEpoch(groupId, epoch) ?: return null
        val page = try {
            val plaintext = encryption.decrypt(event.content, key)
            if (plaintext.toByteArray(Charsets.UTF_8).size > 32768) return null
            json.decodeFromString<IdentityHistoryPage>(plaintext)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (!page.hasValidShape() || page.groupId != groupId || page.newPubkey != event.pubkey) return null
        val revocation = page.revocation ?: return null
        if (!revocation.verify() ||
            revocation.kind != 30078 ||
            revocation.pubkey != page.oldPubkey ||
            revocation.createdAt <= 0 ||
            revocation.createdAt > System.currentTimeMillis() / 1000 + 3600 ||
            revocation.tags.filter { it.firstOrNull() == "g" } != listOf(listOf("g", groupId)) ||
            revocation.tags.filter { it.firstOrNull() == "t" } != listOf(listOf("t", "key_revocation"))
        ) {
            return null
        }
        val payload = try {
            json.decodeFromString<KeyRevocation>(encryption.decrypt(revocation.content, key))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (!payload.isAuthorizedBy(page.oldPubkey) ||
            payload.newPubkey != page.newPubkey ||
            !payload.provesSuccessor(groupId)
        ) {
            return null
        }
        val fact = groups.authenticatedRevocations(groupId).any {
            it.id == revocation.id &&
                it.author == page.oldPubkey &&
                it.successor == page.newPubkey &&
                it.proven &&
                it.epoch == epoch &&
                it.timestamp == revocation.createdAt
        }
        val group = groups.getById(groupId) ?: return null
        val completesTransition = epoch == group.keyEpoch && page.newPubkey in group.members
        val competes = groups.hasRevocationInEpoch(groupId, page.oldPubkey, epoch)
        val currentMember = epoch == group.keyEpoch &&
            page.oldPubkey in group.members &&
            page.oldPubkey !in groups.retiredIdentities(groupId).revoked
        if (!fact && !currentMember && !completesTransition && !competes) return null
        return page
    }

    suspend fun installRevocation(event: NostrEvent, groupId: String, epoch: Int): Boolean {
        val page = authenticate(event, groupId, epoch) ?: return false
        val revocation = checkNotNull(page.revocation)
        return groups.applyAuthenticatedRevocation(
            groupId,
            page.oldPubkey,
            page.newPubkey,
            revocation.createdAt,
            revocation.id,
            epoch,
            successorProven = true
        )
    }

    suspend fun validMoney(
        row: EventSnapshot,
        members: Set<String>,
        rows: List<EventSnapshot>,
        requireSignature: Boolean = false
    ): Boolean {
        if (row.eventType !in IdentityHistoryPage.MONEY_TYPES) return false
        val event = row.originalEventJson?.let(NostrEvent::fromJson) ?: return false
        val authentic = event.verify() ||
            (
                !requireSignature &&
                    row.sig.startsWith(EventSnapshot.SEAL_SIG_PREFIX) &&
                    event.sig.isEmpty() &&
                    event.id == event.computeId().toHex()
                )
        if (!authentic ||
            event.id != row.eventId ||
            event.pubkey != row.pubkey ||
            event.createdAt != row.createdAt ||
            event.kind != 30078 ||
            event.content != row.contentEncrypted ||
            event.tags.filter { it.firstOrNull() == "g" } != listOf(listOf("g", row.groupId)) ||
            event.tags.filter { it.firstOrNull() == "t" } != listOf(listOf("t", row.eventType)) ||
            row.expenseUuid == null ||
            event.tags.filter { it.firstOrNull() == "x" } != listOf(listOf("x", row.expenseUuid))
        ) {
            return false
        }
        val key = groups.getGroupKeyForEpoch(row.groupId, row.keyEpoch) ?: return false
        return try {
            val content = encryption.decrypt(row.contentEncrypted, key)
            val validator = EventValidator()
            if (!validator.isContentSafe(content)) return false
            if (row.eventType in setOf("expense_correction", "expense_delete") &&
                rows.none {
                    it.pubkey == row.pubkey &&
                        it.expenseUuid == row.expenseUuid &&
                        it.eventType == "expense" &&
                        it.applyState == 0
                }
            ) {
                return false
            }
            when (row.eventType) {
                "expense", "expense_correction" -> json.decodeFromString<Expense>(content).let {
                    it.id == row.expenseUuid && validator.isExpenseValid(it, members)
                }
                "settlement" -> json.decodeFromString<Settlement>(content).let {
                    it.id == row.expenseUuid && validator.isSettlementValid(it, row.pubkey, members)
                }
                else -> true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    data class Evidence(val authorized: Map<String, Set<String>>, val unresolved: Set<String>) {
        fun permits(author: String, eventId: String): Boolean = eventId in authorized[author].orEmpty()
    }

    suspend fun evaluate(groupId: String, rows: List<EventSnapshot>): Evidence {
        val retired = groups.retiredIdentities(groupId).revoked
        if (retired.isEmpty() && rows.none { it.eventType == IdentityHistoryPage.TYPE }) {
            return Evidence(emptyMap(), emptySet())
        }
        val facts = groups.authenticatedRevocations(groupId).groupBy { it.author }
        val pages = rows.filter { it.eventType == IdentityHistoryPage.TYPE && it.applyState == 0 }.mapNotNull { row ->
            val event = row.originalEventJson?.let(NostrEvent::fromJson) ?: return@mapNotNull null
            if (event.id != row.eventId || event.pubkey != row.pubkey || event.content != row.contentEncrypted) {
                return@mapNotNull null
            }
            authenticate(event, groupId, row.keyEpoch)
        }.groupBy { it.oldPubkey }
        val authorized = mutableMapOf<String, Set<String>>()
        val unresolved = mutableSetOf<String>()
        for (author in retired + pages.keys) {
            val revocations = facts[author].orEmpty().distinctBy { it.id }
            val evidence = pages[author].orEmpty()
            if (revocations.size != 1 || !revocations.single().proven || evidence.isEmpty()) {
                unresolved += author
                continue
            }
            val fact = revocations.single()
            val matching = evidence.filter { it.revocation?.id == fact.id && it.newPubkey == fact.successor }
            if (matching.size != evidence.size || matching.map { it.root to it.eventCount }.distinct().size != 1) {
                unresolved += author
                continue
            }
            val unique = matching.map { it.copy(revocationEventJson = checkNotNull(it.revocation).toJson()) }.distinct()
            val first = unique.first()
            if (unique.size != first.pageCount || unique.map { it.pageIndex }.toSet().size != first.pageCount) {
                unresolved += author
                continue
            }
            val ids = unique.sortedBy { it.pageIndex }.flatMap { it.eventIds }
            if (ids != ids.distinct().sorted() ||
                ids.size != first.eventCount ||
                IdentityHistoryPage.root(ids) != first.root
            ) {
                unresolved += author
                continue
            }
            authorized[author] = ids.toSet()
        }
        for (author in retired) {
            val visited = mutableSetOf<String>()
            var current = author
            while (current in retired) {
                if (!visited.add(current) || visited.size > 1024) {
                    unresolved += visited
                    break
                }
                val links = facts[current].orEmpty()
                if (links.size != 1 || !links.single().proven || links.single().successor.isEmpty()) break
                current = links.single().successor
            }
        }
        unresolved.forEach(authorized::remove)
        return Evidence(authorized, unresolved)
    }
}
