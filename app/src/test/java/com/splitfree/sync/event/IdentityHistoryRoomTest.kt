package com.splitfree.sync.event

import android.app.Application
import androidx.room.Room
import com.splitfree.data.identity.IdentityManager
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.repository.ControlOperationJournal
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.settings.UserPreferences
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.IdentityHistoryPage
import com.splitfree.domain.model.group.KeyRevocation
import com.splitfree.domain.repository.EventPublisherContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.usecase.expense.BalanceUnavailableException
import com.splitfree.domain.usecase.expense.ComputeBalancesUseCase
import com.splitfree.domain.usecase.export.ExportGroupUseCase
import com.splitfree.domain.usecase.export.ImportGroupUseCase
import com.splitfree.domain.usecase.group.ControlOperationLock
import com.splitfree.domain.usecase.group.PreparedRevocation
import com.splitfree.domain.usecase.group.RevocationIntent
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.test.FakeSecureStorage
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class IdentityHistoryRoomTest {
    private val devices = mutableListOf<Device>()
    private val encryption = GroupEncryption(CompressionUtil)
    private val key = encryption.generateGroupKey()
    private val time = System.currentTimeMillis() / 1000 - 1000
    private val creatorKey = ByteArray(32).also { it[31] = 1 }
    private val oldKey = ByteArray(32).also { it[31] = 2 }
    private val successorKey = ByteArray(32).also { it[31] = 3 }
    private val creator = NostrEvent.pubkeyFromPrivkey(creatorKey)
    private val old = NostrEvent.pubkeyFromPrivkey(oldKey)
    private val successor = NostrEvent.pubkeyFromPrivkey(successorKey)
    private val groupId = GroupIdentity.derive(creator, time)

    private inner class Device(seed: Int = 1, concurrentTransactions: Boolean = false) {
        val app: Application = RuntimeEnvironment.getApplication()
        val storage = FakeSecureStorage()
        val identity = IdentityManager(app, storage).apply {
            importKey("00".repeat(31) + seed.toString(16).padStart(2, '0'))
        }
        val transactionExecutor = if (concurrentTransactions) Dispatchers.IO.asExecutor() else Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(transactionExecutor)
            .addCallback(AppDatabase.SYNC_REVISION_CALLBACK).build()
        val groups = GroupRepository(db.groupDao(), FakeSecureStorage())
        val events = EventRepository(db, db.eventDao())
        val settings = UserPreferences(app).apply { giftWrapEnabled = false }
        val journal = ControlOperationJournal(db.controlOperationDao())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val signer = EventSigner(identity)
        val wrap = GiftWrapService(identity, settings)
        fun publisher(repository: GroupRepositoryContract = groups) = EventPublisher(
            db.eventDao(), db.outboxDao(), db.deliveryDao(), mockk(relaxed = true),
            mockk(relaxed = true), wrap, repository, identity, db, settings
        )
        val publisher = publisher()
        val history = MembershipHistory(db.eventDao(), groups, identity)
        val lock = ControlOperationLock()
        fun revoker(publish: EventPublisherContract = publisher) = RevokeKeyUseCase(
            identity, groups, encryption,
            signer, publish, journal, lock, settings, history
        )
        fun rotator() = RotateGroupKeyUseCase(groups, encryption, identity, signer, publisher, events, journal, lock)
        fun processor(): EventProcessor {
            val rotate = rotator()
            val post = EventPostProcessor(groups, rotate, revoker(), mockk(relaxed = true), publisher, identity, scope)
            return EventProcessor(
                db.eventDao(), groups, encryption, signer, identity, wrap, EventValidator(), post, history
            )
        }
        var ingest = processor()
        init {
            devices += this
        }
        suspend fun join() {
            groups.save(
                Group(
                    groupId,
                    "History",
                    createdBy = creator,
                    createdAt = time,
                    members = listOf(creator, old),
                    relays = emptyList()
                ),
                key
            )
        }
        suspend fun receive(event: NostrEvent) = ingest.process(
            event,
            context = IngestionContext.RECONCILIATION,
            expectedGroupId = groupId
        )
        suspend fun balances() =
            ComputeBalancesUseCase(events, groups, encryption)(groupId).associate { it.pubkey to it.net }
        suspend fun unavailable() {
            assertTrue(runCatching { balances() }.exceptionOrNull() is BalanceUnavailableException)
        }
        suspend fun export() = ExportGroupUseCase(events, groups, identity).invoke(groupId)
        suspend fun restore(content: String) =
            ImportGroupUseCase(events, groups, encryption, EventValidator(), identity)(content)
    }

    @After fun cleanup() {
        devices.forEach {
            it.scope.cancel()
            it.db.close()
        }
    }

    private fun signed(
        author: ByteArray,
        type: String,
        payload: String,
        at: Long = time + 1,
        uuid: String? = null,
        group: String = groupId,
        groupKey: String = key
    ): NostrEvent = NostrEvent(
        pubkey = NostrEvent.pubkeyFromPrivkey(author),
        createdAt = at,
        kind = 30078,
        tags = listOf(listOf("g", group), listOf("t", type)) +
            (uuid?.let { listOf(listOf("x", it)) } ?: emptyList()),
        content = encryption.encrypt(payload, groupKey)
    ).sign(author)

    private fun expense(
        amount: Long = 100,
        id: String = UUID.randomUUID().toString(),
        type: String = "expense",
        groupKey: String = key,
        at: Long = time + 1
    ) = signed(
        oldKey,
        type,
        Json.encodeToString(
            Expense(
                id,
                amount,
                "INR",
                "History",
                old,
                SplitType.EQUAL,
                listOf(SplitEntry(old, amount / 2), SplitEntry(creator, amount / 2)),
                at
            )
        ),
        at = at,
        uuid = id,
        groupKey = groupKey
    )

    private fun retirement(from: ByteArray = oldKey, to: ByteArray = successorKey, at: Long = time + 2): NostrEvent {
        val a = NostrEvent.pubkeyFromPrivkey(from)
        val b = NostrEvent.pubkeyFromPrivkey(to)
        return signed(
            from,
            "key_revocation",
            Json.encodeToString(
                KeyRevocation(
                    a,
                    b,
                    successorProof =
                    KeyRevocation.proveSuccessor(groupId, a, b, to)
                )
            ),
            at
        )
    }

    private fun pages(revoke: NostrEvent, ids: List<String>, signer: ByteArray = successorKey): List<NostrEvent> =
        IdentityHistoryPage.create(groupId, revoke.pubkey, NostrEvent.pubkeyFromPrivkey(signer), revoke, ids).map {
            signed(signer, IdentityHistoryPage.TYPE, Json.encodeToString(it), time + 3)
        }

    @Test
    fun `listed expense converges in all six arrival permutations including page only retirement`() = runBlocking {
        val expense = expense()
        val revoke = retirement()
        val page = pages(revoke, listOf(expense.id)).single()
        val inputs = listOf(expense, revoke, page)
        for (a in inputs.indices) {
            for (b in inputs.indices.filter { it != a }) {
                val device = Device().also { it.join() }
                val order = listOf(inputs[a], inputs[b], inputs.single { it != inputs[a] && it != inputs[b] })
                order.forEach { assertTrue(device.receive(it).outcome != IngestOutcome.REJECTED) }
                device.ingest.retryDeferred(groupId)
                assertEquals(mapOf(successor to 50L, creator to -50L), device.balances())
                assertEquals(0, device.ingest.unresolvedIdentityHistory(groupId))
            }
        }
        val only = Device().also { it.join() }
        assertEquals(IngestOutcome.APPLIED, only.receive(page).outcome)
        only.unavailable()
        only.receive(expense)
        assertEquals(mapOf(successor to 50L, creator to -50L), only.balances())
    }

    @Test
    fun `missing pages and referenced events recover after processor recreation`() = runBlocking {
        val device = Device().also { it.join() }
        val events = (0..IdentityHistoryPage.PAGE_SIZE).map { expense() }
        val pages = pages(retirement(), events.map { it.id })
        device.receive(pages.first())
        device.unavailable()
        events.forEach { device.receive(it) }
        device.ingest = device.processor()
        device.receive(pages.last())
        events.forEach { device.receive(it) }
        device.ingest.retryDeferred(groupId)
        assertEquals(mapOf(successor to 6450L, creator to -6450L), device.balances())
    }

    @Test
    fun `unlisted backdated forgery cannot change balances or leave pending debt after complete pages`() = runBlocking {
        val device = Device().also { it.join() }
        val real = expense()
        val revoke = retirement()
        device.receive(revoke)
        val forgery = expense(200)
        assertEquals(IngestOutcome.DEFERRED, device.receive(forgery).outcome)
        device.receive(pages(revoke, listOf(real.id)).single())
        device.receive(real)
        device.ingest.retryDeferred(groupId)
        assertEquals(2, device.db.eventDao().getEvent(forgery.id)!!.applyState)
        assertEquals(0, device.db.eventDao().countPending(groupId))
        assertEquals(mapOf(successor to 50L, creator to -50L), device.balances())
        assertEquals(IngestOutcome.REJECTED, device.receive(expense(300)).outcome)
    }

    @Test
    fun `previously applied unlisted history is preserved and explicitly unresolved`() = runBlocking {
        val device = Device().also { it.join() }
        val unlisted = expense()
        device.receive(unlisted)
        device.receive(pages(retirement(), emptyList()).single())
        device.unavailable()
        assertEquals(0, device.db.eventDao().getEvent(unlisted.id)!!.applyState)
        assertTrue(device.ingest.unresolvedIdentityHistory(groupId) > 0)
    }

    @Test
    fun `legacy retirement never invents authorization for delayed history`() = runBlocking {
        val device = Device().also { it.join() }
        device.receive(retirement())
        assertEquals(IngestOutcome.DEFERRED, device.receive(expense()).outcome)
        device.unavailable()
    }

    @Test
    fun `outsider altered cross group and malformed pages cannot poison a valid ledger`() = runBlocking {
        val device = Device().also { it.join() }
        val real = expense()
        device.receive(real)
        val page = pages(retirement(), listOf(real.id)).single()
        assertEquals(IngestOutcome.REJECTED, device.receive(page.copy(content = page.content + "x")).outcome)
        val outsider = ByteArray(32).also { it[31] = 7 }
        val outsiderR = retirement(outsider)
        assertEquals(IngestOutcome.REJECTED, device.receive(pages(outsiderR, emptyList()).single()).outcome)
        assertEquals(
            IngestOutcome.REJECTED,
            device.receive(page.copy(tags = listOf(listOf("g", "other"))).sign(successorKey)).outcome
        )
        val payload = Json.decodeFromString<IdentityHistoryPage>(encryption.decrypt(page.content, key))
        val invalid =
            signed(
                successorKey,
                IdentityHistoryPage.TYPE,
                Json.encodeToString(payload.copy(eventCount = Int.MAX_VALUE))
            )
        assertEquals(IngestOutcome.REJECTED, device.receive(invalid).outcome)
        assertEquals(mapOf(old to 50L, creator to -50L), device.balances())
    }

    @Test
    fun `competing page only retirement invalidates complete balances in either order`() = runBlocking {
        val attacker = ByteArray(32).also { it[31] = 4 }
        val honest = pages(retirement(), emptyList()).single()
        val fork = pages(retirement(to = attacker, at = time + 4), emptyList(), attacker).single()
        for (order in listOf(listOf(honest, fork), listOf(fork, honest))) {
            val device = Device().also { it.join() }
            order.forEach { assertEquals(IngestOutcome.APPLIED, device.receive(it).outcome) }
            assertEquals(2, device.groups.authenticatedRevocations(groupId).size)
            device.unavailable()
        }
    }

    @Test
    fun `successor cycle with complete pages remains unresolved`() = runBlocking {
        val device = Device().also { it.join() }
        assertEquals(IngestOutcome.APPLIED, device.receive(pages(retirement(), emptyList()).single()).outcome)
        assertEquals(
            IngestOutcome.APPLIED,
            device.receive(pages(retirement(successorKey, oldKey, time + 4), emptyList(), oldKey).single()).outcome
        )
        assertEquals(2, device.groups.authenticatedRevocations(groupId).size)
        device.unavailable()
    }

    @Test
    fun `retired successor can carry exact predecessor history after another replacement`() = runBlocking {
        val device = Device().also { it.join() }
        val third = ByteArray(32).also { it[31] = 4 }
        val original = retirement()
        val money = expense()
        assertEquals(IngestOutcome.APPLIED, device.receive(original).outcome)
        assertEquals(
            IngestOutcome.APPLIED,
            device.receive(pages(retirement(successorKey, third, time + 4), emptyList(), third).single()).outcome
        )
        assertEquals(IngestOutcome.APPLIED, device.receive(pages(original, listOf(money.id)).single()).outcome)
        assertEquals(IngestOutcome.APPLIED, device.receive(money).outcome)
        assertEquals(mapOf(NostrEvent.pubkeyFromPrivkey(third) to 50L, creator to -50L), device.balances())
    }

    @Test
    fun `deletion and correction history wait for same author originals then converge`() = runBlocking {
        val device = Device().also { it.join() }
        val original = expense()
        val uuid = original.tags.single { it[0] == "x" }[1]
        val correction = expense(200, uuid, "expense_correction")
        val deletion = signed(oldKey, "expense_delete", "{}", uuid = uuid)
        device.receive(pages(retirement(), listOf(original.id, correction.id, deletion.id)).single())
        device.receive(deletion)
        device.receive(correction)
        device.unavailable()
        device.ingest = device.processor()
        device.receive(original)
        device.ingest.retryDeferred(groupId)
        assertEquals(emptyMap<String, Long>(), device.balances())
        assertEquals(
            setOf(original.id, correction.id, deletion.id),
            device.events.getEventsByGroup(groupId)
                .filter { it.eventType in IdentityHistoryPage.MONEY_TYPES }.map { it.eventId }.toSet()
        )
    }

    @Test
    fun `prepared revocation resumes exact pages and blocks money after interrupted delivery`() = runBlocking {
        val author = Device(2).also { it.join() }
        val original = expense()
        author.receive(original)
        val failing = object : EventPublisherContract by author.publisher {
            override suspend fun publishIdentityHistory(event: NostrEvent, groupId: String, epoch: Int) {
                author.publisher.publishIdentityHistory(event, groupId, epoch)
                error("injected after page durable commit")
            }
        }
        assertTrue(runCatching { author.revoker(failing)() }.isFailure)
        val operation = author.journal.get("identity-revocation")!!
        val exact = author.db.eventDao().getEventsByType(groupId, IdentityHistoryPage.TYPE).single().originalEventJson
        assertNotNull(operation.preparedJson)
        assertEquals(old, author.identity.getPublicKeyHex())
        assertTrue(
            runCatching {
                author.publisher.publishExpense(expense(), author.groups.getById(groupId)!!, "late")
            }.isFailure
        )
        val replacement = author.revoker()()
        assertEquals(
            exact,
            author.db.eventDao().getEventsByType(groupId, IdentityHistoryPage.TYPE).single().originalEventJson
        )
        assertEquals(mapOf(replacement to 50L, creator to -50L), author.balances())
        assertEquals(null, author.journal.get("identity-revocation"))
    }

    @Test
    fun `capture refuses changed group set and unverifiable money before intent`() = runBlocking {
        val author = Device(2).also { it.join() }
        val snapshot = author.groups.getAll()
        author.groups.save(snapshot.single().copy(id = "new-group"), key)
        var persisted = false
        assertTrue(
            runCatching {
                author.publisher.captureRevocationHistory(old, snapshot) { persisted = true }
            }.isFailure
        )
        assertFalse(persisted)
        author.events.insert(
            EventSnapshot("00".repeat(32), groupId, old, time, contentEncrypted = "junk", eventType = "expense")
        )
        assertTrue(runCatching { author.revoker()() }.isFailure)
        assertEquals(null, author.journal.get("identity-revocation"))
        assertFalse(author.identity.hasPendingKeyPair())
    }

    @Test
    fun `wrapped exact history retains rumor ID and deduplicates repeated outer envelopes`() = runBlocking {
        val author = Device(2).also { it.join() }
        val receiver = Device().also { it.join() }
        val original = expense()
        author.settings.giftWrapEnabled = true
        val wrapped = author.wrap.wrapIfEnabled(original, creator)
        receiver.receive(pages(retirement(), listOf(original.id)).single())
        assertEquals(IngestOutcome.APPLIED, receiver.receive(wrapped).outcome)
        assertEquals(
            IngestOutcome.ALREADY_APPLIED,
            receiver.receive(author.wrap.wrapIfEnabled(original, creator)).outcome
        )
        assertEquals(mapOf(successor to 50L, creator to -50L), receiver.balances())
        assertTrue(receiver.db.eventDao().getEvent(original.id)!!.sig.startsWith("seal:"))
    }

    @Test
    fun `same root conflicting page and a different root are unresolved not first arrival winners`() = runBlocking {
        val revoke = retirement()
        val original = expense()
        val first = pages(revoke, listOf(original.id)).single()
        val payload = Json.decodeFromString<IdentityHistoryPage>(encryption.decrypt(first.content, key))
        val conflict = signed(
            successorKey,
            IdentityHistoryPage.TYPE,
            Json.encodeToString(payload.copy(eventIds = listOf("ff".repeat(32))))
        )
        for (other in listOf(conflict, pages(revoke, emptyList()).single())) {
            val device = Device().also { it.join() }
            device.receive(first)
            device.receive(original)
            device.balances()
            assertEquals(IngestOutcome.APPLIED, device.receive(other).outcome)
            device.unavailable()
        }
    }

    @Test
    fun `backup staged history and page first import converge without standalone revocation`() = runBlocking {
        val source = Device().also { it.join() }
        val root = signed(
            creatorKey,
            "group_meta",
            Json.encodeToString(
                GroupMeta(
                    name = "History",
                    createdBy = creator,
                    createdAt = time,
                    members = listOf(creator, old)
                )
            ),
            time
        )
        source.receive(root)
        val original = expense()
        val revoke = retirement()
        source.receive(revoke)
        source.receive(original)
        val heldBackup = source.export()
        val target = Device()
        target.restore(heldBackup)
        target.unavailable()
        assertEquals(3, target.db.eventDao().getEvent(original.id)!!.applyState)
        source.receive(pages(revoke, listOf(original.id)).single())
        val complete = source.export()
        target.restore(complete)
        assertEquals(mapOf(successor to 50L, creator to -50L), target.balances())
        val pageOnly = Device().also { it.join() }
        pageOnly.receive(root)
        val page = pages(revoke, listOf(original.id)).single()
        pageOnly.events.insert(snapshot(page))
        val firstTarget = Device()
        firstTarget.restore(pageOnly.export())
        firstTarget.unavailable()
        assertTrue(old in firstTarget.groups.retiredIdentities(groupId).revoked)
        firstTarget.restore(complete)
        assertEquals(target.balances(), firstTarget.balances())
    }

    @Test
    fun `seal only restored own history refuses capture without discarding identity or records`() = runBlocking {
        val author = Device(2).also { it.join() }
        val original = expense()
        val rumor = original.copy(sig = "")
        author.events.insert(snapshot(rumor).copy(sig = "seal:" + original.sig))
        assertTrue(runCatching { author.revoker()() }.exceptionOrNull()?.message?.contains("unverifiable") == true)
        assertEquals(old, author.identity.getPublicKeyHex())
        assertFalse(author.identity.hasPendingKeyPair())
        assertEquals(null, author.journal.get("identity-revocation"))
        assertNotNull(author.db.eventDao().getEvent(original.id))
    }

    @Test
    fun `checkpoint and money under retained historical epoch apply after group epoch advances`() = runBlocking {
        val device = Device().also { it.join() }
        val revoke = retirement()
        val money = expense()
        assertEquals(IngestOutcome.APPLIED, device.receive(revoke).outcome)
        device.groups.saveGroupKeyForEpoch(groupId, 1, encryption.generateGroupKey())
        assertTrue(device.groups.applyKeyRotation(groupId, 1, listOf(creator, successor), emptyMap()))
        assertEquals(IngestOutcome.APPLIED, device.receive(pages(revoke, listOf(money.id)).single()).outcome)
        assertEquals(IngestOutcome.APPLIED, device.receive(money).outcome)
        assertEquals(0, device.db.eventDao().getEvent(money.id)!!.keyEpoch)
        assertEquals(mapOf(successor to 50L, creator to -50L), device.balances())
    }

    @Test
    fun `rejoined member exact money history survives later identity replacement`() = runBlocking {
        val receiver = Device().also { it.join() }
        val author = Device(2).also { it.join() }
        remove(receiver, author)
        val epochKey = checkNotNull(receiver.groups.getGroupKeyForEpoch(groupId, 1))
        author.groups.saveGroupKeyForEpoch(groupId, 1, epochKey)
        val rejoin = signed(
            oldKey,
            "group_meta",
            Json.encodeToString(
                GroupMeta(
                    members = listOf(creator, old),
                    keyEpoch = 1
                )
            ),
            at = System.currentTimeMillis() / 1000 + 1,
            groupKey = epochKey
        )
        assertEquals(IngestOutcome.APPLIED, author.receive(rejoin).outcome)
        assertEquals(IngestOutcome.APPLIED, receiver.receive(rejoin).outcome)
        assertTrue(old in checkNotNull(receiver.groups.getById(groupId)).members)
        val money = expense(groupKey = epochKey, at = rejoin.createdAt + 1)
        assertTrue(
            author.publisher.publishExpense(
                money,
                checkNotNull(author.groups.getById(groupId)),
                money.tags.single { it[0] == "x" }[1]
            )
        )
        val replacement = author.revoker()()
        val checkpoint = checkNotNull(
            NostrEvent.fromJson(
                author.db.eventDao()
                    .getEventsByType(groupId, IdentityHistoryPage.TYPE).single().originalEventJson!!
            )
        )
        assertEquals(IngestOutcome.APPLIED, receiver.receive(checkpoint).outcome)
        receiver.unavailable()
        assertEquals(IngestOutcome.APPLIED, receiver.receive(money).outcome)
        assertEquals(1, receiver.db.eventDao().getEvent(money.id)!!.keyEpoch)
        assertEquals(1, receiver.history.removalEpochOf(groupId, old))
        val page = Json.decodeFromString<IdentityHistoryPage>(encryption.decrypt(checkpoint.content, epochKey))
        assertEquals(listOf(money.id), page.eventIds)
        assertEquals(page.revocation!!.id, receiver.groups.authenticatedRevocations(groupId).single().id)
        assertEquals(mapOf(replacement to 50L, creator to -50L), receiver.balances())
        assertEquals(author.balances(), receiver.balances())
    }

    @Test
    fun `removed member without current key cannot authorize successor history`() = runBlocking {
        val receiver = Device().also { it.join() }
        val removed = Device(2).also { it.join() }
        remove(receiver, removed)
        val revoke = retirement()
        val checkpoint = pages(revoke, listOf(expense().id)).single()
        assertTrue(revoke.verify())
        assertTrue(checkpoint.verify())
        assertEquals(IngestOutcome.REJECTED, receiver.receive(checkpoint).outcome)
        assertEquals(IngestOutcome.REJECTED, receiver.receive(revoke).outcome)
        assertTrue(receiver.groups.authenticatedRevocations(groupId).isEmpty())
        assertTrue(receiver.db.eventDao().getEventsByType(groupId, IdentityHistoryPage.TYPE).isEmpty())
        assertEquals(null, receiver.db.eventDao().getEvent(revoke.id))
        assertEquals(listOf(creator), receiver.groups.getById(groupId)!!.members)
        assertEquals(emptyMap<String, Long>(), receiver.balances())
    }

    private suspend fun remove(creatorDevice: Device, removed: Device) {
        creatorDevice.rotator()(groupId, old)
        val payload = creatorDevice.groups.authenticatedRotations(groupId).values.single()
        val removal = NostrEvent(
            pubkey = creator,
            createdAt = System.currentTimeMillis() / 1000,
            kind = 30078,
            tags = listOf(listOf("g", groupId), listOf("t", "key_rotation"), listOf("p", old)),
            content = Nip44.encrypt(
                Json.encodeToString(payload),
                Nip44.getConversationKey(creatorKey, old.hexToBytes())
            )
        ).sign(creatorKey)
        assertEquals(IngestOutcome.APPLIED, removed.receive(removal).outcome)
        for (device in listOf(creatorDevice, removed)) {
            assertEquals(1, device.groups.getById(groupId)!!.keyEpoch)
            assertEquals(1, device.history.removalEpochOf(groupId, old))
            assertFalse(old in device.groups.getById(groupId)!!.members)
        }
        assertEquals(null, removed.groups.getGroupKeyForEpoch(groupId, 1))
    }

    @Test
    fun `money transaction before capture is included in durable intent and prepared page`() = runBlocking {
        val author = Device(2, concurrentTransactions = true).also { it.join() }
        val group = checkNotNull(author.groups.getById(groupId))
        val money = expense()
        val moneyEntered = CompletableDeferred<Unit>()
        val releaseMoney = CompletableDeferred<Unit>()
        val captureReady = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        val captureAttempted = CompletableDeferred<Unit>()
        val blocked = object : GroupRepositoryContract by author.groups {
            override suspend fun getById(groupId: String): Group? {
                val snapshot = author.groups.getById(groupId)
                assertTrue(author.db.inTransaction())
                moneyEntered.complete(Unit)
                releaseMoney.await()
                return snapshot
            }
        }
        val publisher = author.publisher(blocked)
        val revokePublisher = object : EventPublisherContract by author.publisher {
            override suspend fun captureRevocationHistory(
                oldPubkey: String,
                groups: List<Group>,
                persist: suspend (Map<String, List<EventSnapshot>>) -> Unit
            ) {
                captureReady.complete(Unit)
                releaseCapture.await()
                captureAttempted.complete(Unit)
                author.publisher.captureRevocationHistory(oldPubkey, groups, persist)
            }
            override suspend fun publishIdentityHistory(event: NostrEvent, groupId: String, epoch: Int) {
                author.publisher.publishIdentityHistory(event, groupId, epoch)
                error("injected after page durable commit")
            }
        }
        val replacing = async { runCatching { author.revoker(revokePublisher)() } }
        captureReady.await()
        val writing = async { publisher.publishExpense(money, group, money.tags.single { it[0] == "x" }[1]) }
        try {
            moneyEntered.await()
            releaseCapture.complete(Unit)
            captureAttempted.await()
            assertFalse(replacing.isCompleted)
            assertFalse(writing.isCompleted)
        } finally {
            releaseCapture.complete(Unit)
            releaseMoney.complete(Unit)
        }
        assertTrue(writing.await())
        assertEquals("injected after page durable commit", replacing.await().exceptionOrNull()?.message)
        assertCaptured(author, listOf(money.id))
        assertEquals(money.toJson(), author.db.eventDao().getEvent(money.id)!!.originalEventJson)
        assertEquals(money.toJson(), author.db.outboxDao().getAll().single { it.eventId == money.id }.eventJson)
    }

    @Test
    fun `capture transaction before money refuses competing write without rows or outbox`() = runBlocking {
        val author = Device(2, concurrentTransactions = true).also { it.join() }
        val group = checkNotNull(author.groups.getById(groupId))
        val money = expense()
        val captured = CompletableDeferred<Unit>()
        val releaseCapture = CompletableDeferred<Unit>()
        val publisher = object : EventPublisherContract by author.publisher {
            override suspend fun captureRevocationHistory(
                oldPubkey: String,
                groups: List<Group>,
                persist: suspend (Map<String, List<EventSnapshot>>) -> Unit
            ) {
                author.publisher.captureRevocationHistory(oldPubkey, groups) { history ->
                    assertTrue(author.db.inTransaction())
                    captured.complete(Unit)
                    releaseCapture.await()
                    persist(history)
                }
            }
            override suspend fun publishIdentityHistory(event: NostrEvent, groupId: String, epoch: Int) {
                author.publisher.publishIdentityHistory(event, groupId, epoch)
                error("injected after page durable commit")
            }
        }
        val replacing = async { runCatching { author.revoker(publisher)() } }
        captured.await()
        // Start outside the capture coroutine so the writer cannot inherit Room's transaction context.
        val writing = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { author.publisher.publishExpense(money, group, money.tags.single { it[0] == "x" }[1]) }
        }
        try {
            assertFalse(writing.isCompleted)
            assertFalse(replacing.isCompleted)
        } finally {
            releaseCapture.complete(Unit)
        }
        assertTrue(writing.await().exceptionOrNull()?.message?.contains("Identity replacement is pending") == true)
        assertEquals("injected after page durable commit", replacing.await().exceptionOrNull()?.message)
        assertCaptured(author, emptyList())
        assertEquals(null, author.db.eventDao().getEvent(money.id))
        assertTrue(author.db.outboxDao().getAll().none { it.eventId == money.id })
    }

    private suspend fun assertCaptured(author: Device, expectedIds: List<String>) {
        val operation = checkNotNull(author.journal.get("identity-revocation"))
        val intent = Json.decodeFromString<RevocationIntent>(operation.intentJson)
        assertEquals(mapOf(groupId to expectedIds), intent.moneyHistory)
        val prepared = Json.decodeFromString<PreparedRevocation>(checkNotNull(operation.preparedJson))
        val event = prepared.historyEvents.single().event()
        assertTrue(event.verify())
        val page = Json.decodeFromString<IdentityHistoryPage>(encryption.decrypt(event.content, key))
        assertEquals(expectedIds, page.eventIds)
        assertEquals(IdentityHistoryPage.root(expectedIds), page.root)
        assertEquals(event.toJson(), author.db.eventDao().getEvent(event.id)!!.originalEventJson)
        assertEquals(event.toJson(), author.db.outboxDao().getAll().single { it.eventId == event.id }.eventJson)
        assertEquals(old, author.identity.getPublicKeyHex())
    }

    private fun snapshot(event: NostrEvent) = EventSnapshot(
        event.id, groupId, event.pubkey, event.createdAt,
        contentEncrypted = event.content, eventType = event.tags.single { it[0] == "t" }[1],
        expenseUuid = event.tags.firstOrNull {
            it[0] == "x"
        }?.get(1),
        sig = event.sig, originalEventJson = event.toJson()
    )
}
