package com.splitfree.sync.nearby

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.DeliveryEntity
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.repository.EventRepository
import com.splitfree.data.repository.GroupRepository
import com.splitfree.data.util.CompressionUtil
import com.splitfree.domain.crypto.EventSigner
import com.splitfree.domain.crypto.GiftWrapService
import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.NostrKind
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.expense.Expense
import com.splitfree.domain.model.expense.SplitEntry
import com.splitfree.domain.model.expense.SplitType
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupMeta
import com.splitfree.domain.model.group.KeyRotation
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.domain.usecase.group.RevokeKeyUseCase
import com.splitfree.domain.usecase.group.RotateGroupKeyUseCase
import com.splitfree.domain.usecase.sync.SelfHealUseCase
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.validation.EventValidator
import com.splitfree.sync.event.EventPostProcessor
import com.splitfree.sync.event.EventProcessor
import com.splitfree.sync.event.EventPublisher
import com.splitfree.sync.event.IngestOutcome
import com.splitfree.sync.event.IngestionContext
import com.splitfree.sync.event.MembershipHistory
import com.splitfree.test.FakeSecureStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * End-to-end nearby reconciliation over the real stack: [RoomReconciliationStore] on an in-memory
 * Room database, the real [EventProcessor] pipeline, real NIP-44 / NIP-59 crypto and real BIP-340
 * signatures, driven by two or more [NearbySessionCoordinator]s over the deterministic in-memory
 * transport from [NearbyTestHarness].
 *
 * Every device gets its own database, key store, identity and coordinator. Room is configured with
 * same-thread executors so that all DAO work completes inside [Router.pump], exactly like the
 * scripted-store tests in [NearbySessionCoordinatorTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RoomReconciliationStoreTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val router = Router()
    private val devices = mutableListOf<Device>()
    private val json = Json { ignoreUnknownKeys = true }

    private val groupId = UUID.randomUUID().toString()
    private val groupKey = GroupEncryption(CompressionUtil).generateGroupKey()

    /** Runs Room's query and transaction work on the calling thread so the session pump is deterministic. */
    private val direct = Executor { it.run() }

    /**
     * One phone: identity, Room database, key store, the full ingestion pipeline, the Room-backed
     * reconciliation store and a coordinator on the shared in-memory transport.
     */
    private inner class Device(seed: Int) {
        val identity = TestIdentity(seed)
        val pub = identity.pub
        val db: AppDatabase =
            Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor(direct)
                .setTransactionExecutor(direct)
                .build()
        val eventDao = db.eventDao()
        val deliveryDao = db.deliveryDao()
        val groupRepo = GroupRepository(db.groupDao(), FakeSecureStorage())
        val encryption = GroupEncryption(CompressionUtil)
        val settings = mockk<SettingsContract>().also { every { it.giftWrapEnabled } returns true }
        val giftWrap = GiftWrapService(identity.contract, settings)
        val signer = EventSigner(identity.contract)
        val publisher =
            EventPublisher(
                eventDao,
                db.outboxDao(),
                deliveryDao,
                mockk<EventThrottler>(relaxed = true),
                giftWrap,
                groupRepo,
                identity.contract,
                db
            )
        val eventRepo = EventRepository(db, eventDao)
        val rotateGroupKey =
            RotateGroupKeyUseCase(groupRepo, encryption, identity.contract, signer, publisher, eventRepo)
        val postProcessor =
            EventPostProcessor(
                groupRepo,
                rotateGroupKey,
                mockk<RevokeKeyUseCase>(relaxed = true),
                mockk<SelfHealUseCase>(relaxed = true),
                publisher,
                identity.contract,
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
        val processor =
            EventProcessor(
                eventDao,
                groupRepo,
                encryption,
                signer,
                identity.contract,
                giftWrap,
                EventValidator(),
                postProcessor,
                MembershipHistory(eventDao, groupRepo, identity.contract)
            )
        val store = RoomReconciliationStore(eventDao, deliveryDao, processor, groupRepo, identity.contract)
        val transport = FakeTransport("d$seed").also { it.router = router }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val coordinator =
            NearbySessionCoordinator(transport, identity.contract, store, scope) { scheduler.currentTime }

        init {
            devices += this
        }

        fun progress(endpoint: String): PeerProgress? = coordinator.state.value.peers[endpoint]

        fun phase(endpoint: String): PeerPhase? = progress(endpoint)?.phase

        fun stats(endpoint: String): TransferStats = checkNotNull(progress(endpoint)).stats

        fun activate() = coordinator.activate(groupId)

        /** Store the shared group (same id, same epoch-0 key) as this device sees it. */
        fun join(members: List<String>, creator: String) = runBlocking {
            groupRepo.save(
                Group(
                    id = groupId,
                    name = "Trip",
                    createdBy = creator,
                    createdAt = 1_000,
                    members = members,
                    relays = listOf("wss://r"),
                    keyEpoch = 0
                ),
                groupKey
            )
        }

        fun group(): Group = runBlocking { checkNotNull(groupRepo.getById(groupId)) }

        fun keyForEpoch(epoch: Int): String? = runBlocking { groupRepo.getGroupKeyForEpoch(groupId, epoch) }

        fun appliedRows(): List<EventEntity> = runBlocking { eventDao.getEventsByGroup(groupId) }

        fun allEventIds(): List<String> = runBlocking { eventDao.getEventIds(groupId) }

        fun row(eventId: String): EventEntity? = runBlocking { eventDao.getEvent(eventId) }

        fun delivery(envelopeId: String): DeliveryEntity? = runBlocking { deliveryDao.get(envelopeId) }

        fun availableDeliveries(): List<DeliveryEntity> = runBlocking { deliveryDao.getAvailable(groupId) }

        fun close() {
            scope.cancel()
            db.close()
        }
    }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun teardown() {
        devices.forEach { it.close() }
        unmockkStatic(android.util.Log::class)
    }

    // ------------------------------------------------------------------ helpers

    private fun nowSecs(): Long = System.currentTimeMillis() / 1000

    private fun ep(of: Device) = "ep-${of.pub.take(6)}"

    private fun connect(a: Device, b: Device, aIncoming: Boolean = false) {
        val token = "chan-${a.pub.take(4)}-${b.pub.take(4)}".toByteArray()
        router.connect(a.transport, ep(b), b.transport, ep(a), token = token, aIncoming = aIncoming)
    }

    private fun NostrEvent.tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    /** Equal-ish split whose shares are all positive and sum exactly to [amount]. */
    private fun splitAmong(members: List<String>, amount: Long): List<SplitEntry> {
        val base = amount / members.size
        return members.mapIndexed { i, m -> SplitEntry(m, if (i == 0) amount - base * (members.size - 1) else base) }
    }

    /**
     * Author a real expense on [device]: encrypt under the device's current group key, sign with its
     * key and persist through [EventPublisher.publishExpense], which also retains one gift-wrap
     * envelope per other member. [createdAt] builds the event by hand so history can be backdated.
     */
    private fun authorExpense(device: Device, amount: Long, description: String, createdAt: Long? = null): NostrEvent =
        runBlocking {
            val group = device.group()
            val key = checkNotNull(device.groupRepo.getGroupKey(groupId))
            val id = UUID.randomUUID().toString()
            val expense =
                Expense(
                    id = id,
                    amount = amount,
                    currency = "USD",
                    description = description,
                    paidBy = device.pub,
                    splitType = SplitType.EQUAL,
                    splitAmong = splitAmong(group.members, amount),
                    timestamp = createdAt ?: nowSecs()
                )
            val encrypted = device.encryption.encrypt(json.encodeToString(Expense.serializer(), expense), key)
            val event =
                if (createdAt == null) {
                    device.signer.createSignedEvent(groupId, "expense", encrypted, expenseUuid = id)
                } else {
                    NostrEvent(
                        pubkey = device.pub,
                        createdAt = createdAt,
                        kind = NostrKind.APP_SPECIFIC,
                        tags = listOf(
                            listOf("d", "$groupId:$id"),
                            listOf("g", groupId),
                            listOf("t", "expense"),
                            listOf("x", id)
                        ),
                        content = encrypted
                    ).sign(device.identity.priv)
                }
            check(device.publisher.publishExpense(event, group, id)) { "expense $id was not saved" }
            event
        }

    private fun decryptExpense(device: Device, row: EventEntity, key: String): Expense =
        json.decodeFromString(Expense.serializer(), device.encryption.decrypt(row.contentEncrypted, key))

    /** The signed `key_rotation` events [author] published that are addressed (`p` tag) to [recipient]. */
    private fun rotationsFor(author: Device, recipient: String): List<NostrEvent> = runBlocking {
        author.eventDao.getEventsByType(groupId, "key_rotation")
            .filter { it.pubkey == author.pub }
            .map { checkNotNull(NostrEvent.fromJson(checkNotNull(it.originalEventJson))) }
            .filter { it.tag("p") == recipient }
    }

    /** Open a per-member rotation envelope as its recipient and read the epoch it installs. */
    private fun rotationEpoch(event: NostrEvent, recipient: Device): Int {
        val convKey = Nip44.getConversationKey(recipient.identity.priv, event.pubkey.hexToBytes())
        return json.decodeFromString(KeyRotation.serializer(), Nip44.decrypt(event.content, convKey)).epoch
    }

    /**
     * A creator `group_meta` authored on [device] under the key for [epoch], optionally backdated. This
     * is what the creator publishes on a rename or after a rotation.
     */
    private fun authorMeta(
        device: Device,
        epoch: Int,
        name: String,
        members: List<String>,
        createdAt: Long? = null
    ): NostrEvent = runBlocking {
        val group = device.group()
        val key = checkNotNull(device.groupRepo.getGroupKeyForEpoch(groupId, epoch))
        val meta =
            GroupMeta(
                name = name,
                description = group.description,
                createdBy = device.pub,
                createdAt = group.createdAt,
                members = members,
                relays = group.relays,
                memberNames = group.memberNames.filterKeys { it in members }
            )
        val encrypted = device.encryption.encrypt(json.encodeToString(GroupMeta.serializer(), meta), key)
        if (createdAt == null) {
            device.signer.createSignedEvent(groupId, "group_meta", encrypted)
        } else {
            NostrEvent(
                pubkey = device.pub,
                createdAt = createdAt,
                kind = NostrKind.APP_SPECIFIC,
                tags = listOf(listOf("d", "$groupId:meta:$createdAt"), listOf("g", groupId), listOf("t", "group_meta")),
                content = encrypted
            ).sign(device.identity.priv)
        }
    }

    private fun ingest(device: Device, event: NostrEvent): IngestOutcome = runBlocking {
        device.processor.process(event, knownGroupId = groupId, context = IngestionContext.RECONCILIATION).outcome
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `two members sync 31 same-author expenses plus a 40 day old one without loss`() {
        val a = Device(1)
        val b = Device(2)
        val members = listOf(a.pub, b.pub)
        listOf(a, b).forEach { it.join(members, creator = a.pub) }
        val recent = (1..31).map { authorExpense(a, amount = 200L + it, description = "expense $it") }
        val old = authorExpense(a, amount = 500, description = "forty days ago", createdAt = nowSecs() - 40 * 86_400)
        val authored = recent + old
        // The backdated event keeps its timestamp through the publisher.
        assertEquals(old.createdAt, a.row(old.id)?.createdAt)
        assertEquals(32, a.appliedRows().size)

        a.activate()
        b.activate()
        connect(a, b)
        router.pump()

        val rows = b.appliedRows()
        assertEquals(32, rows.size)
        assertEquals(authored.map { it.id }.toSet(), rows.map { it.eventId }.toSet())
        assertEquals("no pending or duplicate rows", 32, b.allEventIds().size)
        assertTrue(rows.all { it.pubkey == a.pub && EventSnapshot.isThirdPartyVerifiable(it.sig) })
        assertTrue(rows.all { checkNotNull(NostrEvent.fromJson(checkNotNull(it.originalEventJson))).verify() })
        assertEquals(old.createdAt, b.row(old.id)?.createdAt)
        // Every expense decrypts on B under the shared epoch-0 key.
        rows.forEach { row -> assertEquals(row.expenseUuid, decryptExpense(b, row, groupKey).id) }

        val received = b.stats(ep(a))
        assertEquals(32, received.applied)
        assertEquals(0, received.rejected)
        assertEquals(0, received.unresolved)
        assertEquals(0, received.deferred)
        // A offered every event plus one authored envelope per event addressed to B.
        assertTrue("sent=${a.stats(ep(b)).sent}", a.stats(ep(b)).sent >= 32)
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
    }

    @Test
    fun `signed original upgrades a rumor received via gift wrap`() {
        val a = Device(1)
        val b = Device(2)
        val cPub = TestIdentity(3).pub
        val members = listOf(a.pub, b.pub, cPub)
        listOf(a, b).forEach { it.join(members, creator = a.pub) }
        val e = authorExpense(a, amount = 300, description = "dinner")
        val wraps = a.availableDeliveries()
        assertEquals(setOf(b.pub, cPub), wraps.map { it.recipient }.toSet())

        // B first learns E as a NIP-59 rumor delivered live (e.g. over a relay).
        val wrapForB =
            checkNotNull(NostrEvent.fromJson(checkNotNull(wraps.single { it.recipient == b.pub }.envelopeJson)))
        val live = runBlocking { b.processor.process(wrapForB, context = IngestionContext.LIVE) }
        assertEquals(IngestOutcome.APPLIED, live.outcome)
        val rumor = checkNotNull(b.row(e.id))
        assertTrue(rumor.sig.startsWith(EventSnapshot.SEAL_SIG_PREFIX))
        assertFalse(EventSnapshot.isThirdPartyVerifiable(rumor.sig))
        assertEquals(a.pub, rumor.pubkey)

        a.activate()
        b.activate()
        connect(a, b)
        router.pump()

        val upgraded = checkNotNull(b.row(e.id))
        assertTrue(EventSnapshot.isThirdPartyVerifiable(upgraded.sig))
        assertEquals(e.sig, upgraded.sig)
        assertTrue(checkNotNull(NostrEvent.fromJson(checkNotNull(upgraded.originalEventJson))).verify())
        assertEquals(EventEntity.APPLY_STATE_APPLIED, upgraded.applyState)
        assertEquals(listOf(e.id), b.allEventIds())
        val s = b.stats(ep(a))
        assertEquals(1, s.upgraded)
        assertEquals(1, s.alreadyApplied)
        assertEquals(0, s.applied)
        assertEquals(0, s.rejected)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
    }

    @Test
    fun `B carries C's gift wrap from A and C applies it with A offline`() {
        val a = Device(1)
        val b = Device(2)
        val c = Device(3)
        val members = listOf(a.pub, b.pub, c.pub)
        listOf(a, b, c).forEach { it.join(members, creator = a.pub) }
        val e = authorExpense(a, amount = 300, description = "taxi")
        val envForC = a.availableDeliveries().single { it.recipient == c.pub }
        val envForB = a.availableDeliveries().single { it.recipient == b.pub }

        a.activate()
        b.activate()
        connect(a, b)
        router.pump()

        val appliedAtB = checkNotNull(b.row(e.id))
        assertEquals(a.pub, appliedAtB.pubkey)
        assertTrue(EventSnapshot.isThirdPartyVerifiable(appliedAtB.sig))
        val carried = checkNotNull(b.delivery(envForC.envelopeId))
        assertEquals(DeliveryEntity.STATE_AVAILABLE, carried.state)
        assertEquals(DeliveryEntity.SOURCE_CARRIED, carried.source)
        assertEquals(DeliveryEntity.TYPE_GIFT_WRAP, carried.eventType)
        assertEquals(c.pub, carried.recipient)
        // A courier stores the exact bytes it was handed and never learns the inner event.
        assertEquals(envForC.envelopeJson, carried.envelopeJson)
        assertNull(carried.eventId)
        // B's own envelope is consumed, not carried.
        assertEquals(DeliveryEntity.STATE_CONSUMED, b.delivery(envForB.envelopeId)?.state)
        assertTrue(b.stats(ep(a)).carried >= 1)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))

        a.coordinator.deactivate()
        router.pump()
        assertFalse(a.coordinator.state.value.active)

        c.activate()
        connect(b, c)
        router.pump()

        val appliedAtC = checkNotNull(c.row(e.id))
        assertEquals("C must credit the original author, never the courier", a.pub, appliedAtC.pubkey)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, appliedAtC.applyState)
        val expense = decryptExpense(c, appliedAtC, groupKey)
        assertEquals("taxi", expense.description)
        assertEquals(300L, expense.amount)
        assertEquals(a.pub, expense.paidBy)
        assertEquals(DeliveryEntity.STATE_CONSUMED, c.delivery(envForC.envelopeId)?.state)
        assertEquals(listOf(e.id), c.allEventIds())
        // The courier's copy is untouched by the hand-over.
        assertEquals(envForC.envelopeJson, b.delivery(envForC.envelopeId)?.envelopeJson)
        assertEquals(PeerPhase.UP_TO_DATE, c.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(c)))
    }

    @Test
    fun `rotation envelope for C passes through B and C installs the new epoch`() {
        val a = Device(1)
        val b = Device(2)
        val c = Device(3)
        val dPub = TestIdentity(4).pub
        val members = listOf(a.pub, b.pub, c.pub, dPub)
        listOf(a, b, c).forEach { it.join(members, creator = a.pub) }

        runBlocking { a.rotateGroupKey(groupId, dPub) }
        assertEquals(1, a.group().keyEpoch)
        assertEquals(listOf(a.pub, b.pub, c.pub), a.group().members)
        val rotationForC = rotationsFor(a, c.pub).single()

        a.activate()
        b.activate()
        connect(a, b)
        router.pump()

        assertEquals(1, b.group().keyEpoch)
        assertEquals(a.keyForEpoch(1), b.keyForEpoch(1))
        assertEquals(setOf(a.pub, b.pub, c.pub), b.group().members.toSet())
        val carriedRotation = checkNotNull(b.delivery(rotationForC.id))
        assertEquals(DeliveryEntity.TYPE_KEY_ROTATION, carriedRotation.eventType)
        assertEquals(c.pub, carriedRotation.recipient)
        assertEquals(DeliveryEntity.SOURCE_CARRIED, carriedRotation.source)
        assertEquals(DeliveryEntity.STATE_AVAILABLE, carriedRotation.state)
        assertEquals(rotationForC.toJson(), carriedRotation.envelopeJson)
        assertTrue(b.stats(ep(a)).carried >= 1)

        a.coordinator.deactivate()
        router.pump()

        c.activate()
        connect(b, c)
        router.pump()

        assertEquals(1, c.group().keyEpoch)
        assertEquals(b.keyForEpoch(1), c.keyForEpoch(1))
        assertEquals(setOf(a.pub, b.pub, c.pub), c.group().members.toSet())
        assertEquals(DeliveryEntity.STATE_CONSUMED, c.delivery(rotationForC.id)?.state)
        val rotationRow = checkNotNull(c.row(rotationForC.id))
        assertEquals(EventEntity.APPLY_STATE_APPLIED, rotationRow.applyState)
        assertEquals(a.pub, rotationRow.pubkey)
        // C never had to trust B for the key: the envelope was authored and signed by A.
        assertTrue(rotationRow.originalEventJson?.let { NostrEvent.fromJson(it) }?.verify() == true)
        // The rotation for A and B's own rotation are opaque to C and carried onward, never applied.
        assertEquals(DeliveryEntity.STATE_AVAILABLE, c.delivery(rotationsFor(a, b.pub).single().id)?.state)
        assertNull(c.row(rotationsFor(a, b.pub).single().id))
        // The key install and the epoch-1 group_meta both apply in one round: key material is
        // advertised first, and anything refused before its key landed is re-requested once it does.
        val afterRotation = c.stats(ep(b))
        assertEquals(2, afterRotation.applied)
        assertEquals(0, afterRotation.rejected)
        assertEquals(0, afterRotation.deferred)
        assertEquals(0, afterRotation.unresolved)
        assertEquals(PeerPhase.UP_TO_DATE, c.phase(ep(b)))

        // B is a member at epoch 1 and authors under the new key while still connected to C and with A
        // offline. The Room change observer re-advertises the delta and C decrypts it under epoch 1.
        val e1 = authorExpense(b, amount = 300, description = "epoch one")
        assertEquals(1, checkNotNull(b.row(e1.id)).keyEpoch)
        router.pump()

        val e1AtC = checkNotNull(c.row(e1.id))
        assertEquals(EventEntity.APPLY_STATE_APPLIED, e1AtC.applyState)
        assertEquals(b.pub, e1AtC.pubkey)
        assertTrue(EventSnapshot.isThirdPartyVerifiable(e1AtC.sig))
        val expense = decryptExpense(c, e1AtC, checkNotNull(c.keyForEpoch(1)))
        assertEquals("epoch one", expense.description)
        assertEquals(b.pub, expense.paidBy)
        assertEquals(afterRotation.applied + 1, c.stats(ep(b)).applied)
        assertEquals(afterRotation.rejected, c.stats(ep(b)).rejected)
        assertEquals(0, c.stats(ep(b)).unresolved)
        // D was excluded from the rotation: nobody holds a key or an envelope for D.
        assertTrue(c.availableDeliveries().none { it.recipient == dPub })
        assertTrue(b.availableDeliveries().none { it.recipient == dPub })
    }

    @Test
    fun `out of order rotations defer then apply`() {
        val a = Device(1)
        val b = Device(2)
        val cPub = TestIdentity(3).pub
        val dPub = TestIdentity(4).pub
        val ePub = TestIdentity(5).pub
        val members = listOf(a.pub, b.pub, cPub, dPub, ePub)
        listOf(a, b).forEach { it.join(members, creator = a.pub) }

        runBlocking {
            a.rotateGroupKey(groupId, dPub)
            a.rotateGroupKey(groupId, ePub)
        }
        assertEquals(2, a.group().keyEpoch)
        val forB = rotationsFor(a, b.pub)
        assertEquals(2, forB.size)
        val epoch1 = forB.single { rotationEpoch(it, b) == 1 }
        val epoch2 = forB.single { rotationEpoch(it, b) == 2 }

        // B never talked to A: epoch 2 arrives first.
        val deferred = runBlocking {
            b.processor.process(epoch2, knownGroupId = groupId, context = IngestionContext.RECONCILIATION)
        }
        assertEquals(IngestOutcome.DEFERRED, deferred.outcome)
        assertTrue(deferred.stored)
        assertEquals(EventEntity.APPLY_STATE_PENDING, b.row(epoch2.id)?.applyState)
        assertEquals(0, b.group().keyEpoch)
        assertNull(b.keyForEpoch(2))
        // B cannot use epoch 2 yet, but it is a signed record another phone may be waiting for.
        val offered = runBlocking { b.store.inventory(groupId) }.single { it.id == epoch2.id }
        assertNotNull(runBlocking { b.store.loadRecord(groupId, offered) })

        val applied = runBlocking {
            b.processor.process(epoch1, knownGroupId = groupId, context = IngestionContext.RECONCILIATION)
        }
        assertEquals(IngestOutcome.APPLIED, applied.outcome)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, b.row(epoch1.id)?.applyState)
        assertEquals(1, b.group().keyEpoch)
        assertEquals(a.keyForEpoch(1), b.keyForEpoch(1))
        assertEquals(setOf(a.pub, b.pub, cPub, ePub), b.group().members.toSet())

        assertEquals(1, runBlocking { b.processor.retryDeferred(groupId) })
        assertEquals(EventEntity.APPLY_STATE_APPLIED, b.row(epoch2.id)?.applyState)
        assertEquals(2, b.group().keyEpoch)
        assertEquals(a.keyForEpoch(2), b.keyForEpoch(2))
        assertFalse(dPub in b.group().members)
        assertEquals(0, runBlocking { b.processor.retryDeferred(groupId) })

        val replay = runBlocking {
            b.processor.process(epoch2, knownGroupId = groupId, context = IngestionContext.RECONCILIATION)
        }
        assertEquals(IngestOutcome.ALREADY_APPLIED, replay.outcome)
        assertEquals(2, b.group().keyEpoch)
    }

    @Test
    fun `stranger with a key cannot open the group and learns no id`() {
        val a = Device(1)
        val z = Device(9)
        a.join(listOf(a.pub), creator = a.pub)
        // Z somehow holds the group key and knows the id, but is not a member of A's roster.
        z.join(listOf(z.pub), creator = a.pub)
        authorExpense(a, amount = 100, description = "private")

        a.activate()
        z.activate()
        connect(a, z)
        router.pump()

        assertEquals(PeerPhase.UNAUTHORIZED, a.phase(ep(z)))
        assertEquals(NearbyWire.CLOSE_UNAUTHORIZED, a.progress(ep(z))?.closeReason)
        assertEquals(PeerPhase.UNAUTHORIZED, z.phase(ep(a)))
        assertTrue(a.transport.sentMessages().none { it is OpenGroup || it is InventoryPage || it is Record })
        assertTrue(a.transport.sentMessages().any { it is Close && it.reason == NearbyWire.CLOSE_UNAUTHORIZED })
        val leaked = a.transport.sentFrames.any { String(it.second, Charsets.UTF_8).contains(groupId) }
        assertFalse("group id must not cross the wire to a non-member", leaked)
        assertTrue(z.allEventIds().isEmpty())
        assertTrue(a.transport.disconnected.contains(ep(z)))
    }

    @Test
    fun `history before removal is admitted in reconciliation but not live`() {
        val a = Device(1)
        val c = Device(3)
        val bPub = TestIdentity(2).pub
        val members = listOf(a.pub, bPub, c.pub)
        listOf(a, c).forEach { it.join(members, creator = a.pub) }

        // C writes history under epoch 0, then the creator removes C.
        val e0 = authorExpense(c, amount = 300, description = "before removal")
        runBlocking { a.rotateGroupKey(groupId, c.pub) }
        assertEquals(1, a.group().keyEpoch)
        val rotationForB = rotationsFor(a, bPub).single()

        // A fresh phone for B that has the invite (epoch 0) but none of the history yet.
        val b2 = Device(2)
        b2.join(members, creator = a.pub)
        val rotation = runBlocking {
            b2.processor.process(rotationForB, knownGroupId = groupId, context = IngestionContext.RECONCILIATION)
        }
        assertEquals(IngestOutcome.APPLIED, rotation.outcome)
        assertEquals(1, b2.group().keyEpoch)
        assertEquals(setOf(a.pub, bPub), b2.group().members.toSet())

        val live = runBlocking { b2.processor.process(e0, context = IngestionContext.LIVE) }
        assertEquals(IngestOutcome.REJECTED, live.outcome)
        assertEquals("not a member", live.reason)
        assertNull(b2.row(e0.id))

        val reconciled = runBlocking { b2.processor.process(e0, context = IngestionContext.RECONCILIATION) }
        assertEquals(IngestOutcome.APPLIED, reconciled.outcome)
        val row = checkNotNull(b2.row(e0.id))
        assertEquals(EventEntity.APPLY_STATE_APPLIED, row.applyState)
        assertEquals(c.pub, row.pubkey)
        assertEquals(0, row.keyEpoch)
        assertEquals("before removal", decryptExpense(b2, row, groupKey).description)
        assertNotNull(b2.keyForEpoch(1))
    }

    @Test
    fun `delayed rotation then newer rename apply in every arrival order and a stale meta cannot re-add`() {
        val a = Device(1)
        val bPub = TestIdentity(2).pub
        val cPub = TestIdentity(3).pub
        val members = listOf(a.pub, bPub, cPub)
        a.join(members, creator = a.pub)
        val now = nowSecs()

        // Sep 1 morning: a rename under epoch 0 that still lists C (authored before the removal).
        val staleMeta = authorMeta(a, epoch = 0, name = "Trip (old)", members = members, createdAt = now - 200)
        // Sep 1 noon: creator removes C (epoch 1).
        runBlocking { a.rotateGroupKey(groupId, cPub) }
        val rotationForB = rotationsFor(a, bPub).single()
        // Sep 2: creator renames again under the new key.
        val rename = authorMeta(a, epoch = 1, name = "Trip 2026", members = listOf(a.pub, bPub), createdAt = now + 100)

        val orders =
            listOf(
                "rotation, rename, stale" to listOf(rotationForB, rename, staleMeta),
                "rotation, stale, rename" to listOf(rotationForB, staleMeta, rename),
                "stale, rotation, rename" to listOf(staleMeta, rotationForB, rename),
                "rename first is undecryptable until the key lands" to listOf(rename, rotationForB, staleMeta, rename)
            )
        for ((label, sequence) in orders) {
            val b = Device(2)
            b.join(members, creator = a.pub)
            sequence.forEach { ingest(b, it) }
            val g = b.group()
            assertEquals(label, 1, g.keyEpoch)
            assertEquals(label, "Trip 2026", g.name)
            assertEquals("$label: the removed member must stay removed", setOf(a.pub, bPub), g.members.toSet())
            assertEquals(a.keyForEpoch(1), b.keyForEpoch(1))
        }
    }

    @Test
    fun `a stale pre rotation meta applied after the rotation updates the name but not the roster`() {
        val a = Device(1)
        val bPub = TestIdentity(2).pub
        val cPub = TestIdentity(3).pub
        val members = listOf(a.pub, bPub, cPub)
        a.join(members, creator = a.pub)
        val staleMeta = authorMeta(
            a,
            epoch = 0,
            name = "Renamed before removal",
            members = members,
            createdAt =
            nowSecs() - 50
        )
        runBlocking { a.rotateGroupKey(groupId, cPub) }
        val b = Device(2)
        b.join(members, creator = a.pub)
        assertEquals(IngestOutcome.APPLIED, ingest(b, rotationsFor(a, bPub).single()))
        assertEquals(IngestOutcome.APPLIED, ingest(b, staleMeta))
        val g = b.group()
        assertEquals("Renamed before removal", g.name)
        assertEquals(setOf(a.pub, bPub), g.members.toSet())
        assertEquals(1, g.keyEpoch)
    }

    @Test
    fun `an envelope for another group delivered in a session is rejected before storage`() {
        val a = Device(1)
        val b = Device(2)
        a.join(listOf(a.pub, b.pub), creator = a.pub)
        b.join(listOf(a.pub, b.pub), creator = a.pub)
        // Both are also members of a second group H.
        val otherId = UUID.randomUUID().toString()
        val otherKey = GroupEncryption(CompressionUtil).generateGroupKey()
        val other =
            Group(
                id = otherId,
                name = "Other",
                createdBy = a.pub,
                createdAt = 1_000,
                members = listOf(a.pub, b.pub),
                relays = listOf("wss://r")
            )
        runBlocking {
            a.groupRepo.save(other, otherKey)
            b.groupRepo.save(other, otherKey)
        }
        // A authors an expense in H; its gift wrap for B is what a peer could try to deliver in G's session.
        val uuid = UUID.randomUUID().toString()
        val expense =
            Expense(
                id = uuid,
                amount = 500,
                currency = "USD",
                description = "in H",
                paidBy = a.pub,
                splitType = SplitType.EQUAL,
                splitAmong = splitAmong(other.members, 500),
                timestamp = nowSecs()
            )
        val encrypted = a.encryption.encrypt(json.encodeToString(Expense.serializer(), expense), otherKey)
        val event = a.signer.createSignedEvent(otherId, "expense", encrypted, expenseUuid = uuid)
        runBlocking { check(a.publisher.publishExpense(event, other, uuid)) }
        val envForB = runBlocking { a.deliveryDao.getAvailable(otherId) }.single { it.recipient == b.pub }
        val item = InventoryItem(envForB.envelopeId, NearbyWire.KIND_DELIVERY, r = b.pub)

        val inG = runBlocking { b.store.ingest(groupId, item, checkNotNull(envForB.envelopeJson), a.pub) }
        assertEquals(RecordOutcome.REJECTED, inG.outcome)
        assertNull("nothing about H may change during G's session", b.row(event.id))
        assertNull(b.delivery(envForB.envelopeId))
        assertEquals(0, runBlocking { b.eventDao.getEventCount(otherId) })

        val inH = runBlocking { b.store.ingest(otherId, item, checkNotNull(envForB.envelopeJson), a.pub) }
        assertEquals(RecordOutcome.APPLIED, inH.outcome)
        assertEquals(otherId, b.row(event.id)?.groupId)
    }

    @Test
    fun `a full courier cache evicts the oldest carried envelope rather than refusing a new key or gift`() {
        val a = Device(1)
        val b = Device(2)
        val cPub = TestIdentity(3).pub
        val members = listOf(a.pub, b.pub, cPub)
        listOf(a, b).forEach { it.join(members, creator = a.pub) }
        // B already carries a full cache for C (recent enough to survive the retention prune).
        val base = nowSecs() - 3_600
        runBlocking {
            repeat(RoomReconciliationStore.MAX_CARRIED_PER_GROUP) { i ->
                val row =
                    DeliveryEntity(
                        envelopeId = "%064x".format(java.math.BigInteger.valueOf(i.toLong() + 1)),
                        groupId = groupId, recipient = cPub, eventId = null, envelopeJson = "{\"old\":$i}",
                        eventType = DeliveryEntity.TYPE_GIFT_WRAP, state = DeliveryEntity.STATE_AVAILABLE,
                        source = DeliveryEntity.SOURCE_CARRIED, createdAt = base + i, receivedAt = base + i,
                        sizeBytes = 16
                    )
                b.deliveryDao.insert(row)
                // A has already seen these (tombstones), so this test isolates B's quota handling.
                a.deliveryDao.insert(
                    row.copy(state = DeliveryEntity.STATE_CONSUMED, envelopeJson = null, sizeBytes = 0)
                )
            }
        }
        assertEquals(RoomReconciliationStore.MAX_CARRIED_PER_GROUP, runBlocking { b.deliveryDao.countCarried(groupId) })
        val oldest = "%064x".format(java.math.BigInteger.ONE)

        authorExpense(a, amount = 100, description = "new for C")
        val envForC = a.availableDeliveries().single { it.recipient == cPub }
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()

        val carried = checkNotNull(b.delivery(envForC.envelopeId))
        assertEquals(DeliveryEntity.STATE_AVAILABLE, carried.state)
        assertNull("the oldest carried envelope makes room", b.delivery(oldest))
        assertEquals(RoomReconciliationStore.MAX_CARRIED_PER_GROUP, runBlocking { b.deliveryDao.countCarried(groupId) })
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(0, b.stats(ep(a)).busy)
    }

    @Test
    fun `gift only forwarding - a courier that never held the signed original still delivers it`() {
        val a = Device(1)
        val b = Device(2)
        val c = Device(3)
        val members = listOf(a.pub, b.pub, c.pub)
        listOf(a, b, c).forEach { it.join(members, creator = a.pub) }
        val e = authorExpense(a, amount = 700, description = "gift only")
        val envForC = a.availableDeliveries().single { it.recipient == c.pub }

        // B receives only C's envelope (say, from a relay or another courier), never A's signed original.
        val carried = runBlocking {
            b.store.ingest(
                groupId,
                InventoryItem(envForC.envelopeId, NearbyWire.KIND_DELIVERY, r = c.pub),
                checkNotNull(envForC.envelopeJson),
                a.pub
            )
        }
        assertEquals(RecordOutcome.CARRIED, carried.outcome)
        assertNull(b.row(e.id))
        assertTrue(b.appliedRows().isEmpty())

        b.activate()
        c.activate()
        connect(b, c)
        router.pump()

        val atC = checkNotNull(c.row(e.id))
        assertEquals(a.pub, atC.pubkey)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, atC.applyState)
        assertFalse(
            "C holds a rumor: only the envelope's seal proves the author",
            EventSnapshot.isThirdPartyVerifiable(atC.sig)
        )
        assertEquals("gift only", decryptExpense(c, atC, groupKey).description)
        assertEquals(DeliveryEntity.STATE_CONSUMED, c.delivery(envForC.envelopeId)?.state)
        assertNull("the courier still never learns the inner event", b.row(e.id))
        assertEquals(PeerPhase.UP_TO_DATE, c.phase(ep(b)))
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(c)))
        // C's rumor row is not offered onward (a peer could not verify it).
        assertTrue(runBlocking { c.store.inventory(groupId) }.none { it.id == e.id })
    }
}
