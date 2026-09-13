package com.splitfree.sync.nearby

import android.app.Application
import androidx.room.Room
import com.splitfree.data.local.AppDatabase
import com.splitfree.data.local.entities.DeliveryEntity
import com.splitfree.data.local.entities.EventEntity
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.repository.ControlOperationJournal
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
import com.splitfree.domain.usecase.group.ControlOperationLock
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
import com.splitfree.sync.event.PostProcessOutcome
import com.splitfree.test.FakeSecureStorage
import io.mockk.coEvery
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
                .addCallback(AppDatabase.SYNC_REVISION_CALLBACK)
                .allowMainThreadQueries()
                .setQueryExecutor(direct)
                .setTransactionExecutor(direct)
                .build()
        val eventDao = db.eventDao()
        val deliveryDao = db.deliveryDao()
        val keyStore = FakeSecureStorage()
        val groupRepo = GroupRepository(db.groupDao(), keyStore)
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
            RotateGroupKeyUseCase(
                groupRepo,
                encryption,
                identity.contract,
                signer,
                publisher,
                eventRepo,
                ControlOperationJournal(db.controlOperationDao()),
                ControlOperationLock()
            )
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
        val store = RoomReconciliationStore(
            eventDao,
            deliveryDao,
            processor,
            groupRepo,
            identity.contract,
            db.syncRevisionDao()
        )
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
        return members.mapIndexed {
                i,
                m
            ->
            SplitEntry(
                m,
                if (i == 0) amount - base * (members.size - 1) else base
            )
        }
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
                tags = listOf(
                    listOf(
                        "d",
                        "$groupId:meta:$createdAt"
                    ),
                    listOf(
                        "g",
                        groupId
                    ),
                    listOf(
                        "t",
                        "group_meta"
                    )
                ),

                content = encrypted
            ).sign(device.identity.priv)
        }
    }

    private fun ingest(device: Device, event: NostrEvent): IngestOutcome = runBlocking {
        device.processor.process(
            event,
            knownGroupId = groupId,
            context = IngestionContext.RECONCILIATION
        ).outcome
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `two members sync 31 same-author expenses plus a 40 day old one without loss`() {
        val a = Device(1)
        val b = Device(2)
        val members = listOf(a.pub, b.pub)
        listOf(a, b).forEach { it.join(members, creator = a.pub) }
        val recent = (1..31).map { authorExpense(a, amount = 200L + it, description = "expense $it") }
        val old = authorExpense(
            a,
            amount = 500,
            description = "forty days ago",
            createdAt = nowSecs() - 40 * 86_400
        )
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

    /**
     * B applied C's identity revocation (C -> C2) before the creator A heard of it. A then rotates D out,
     * so its signed roster and envelopes still name C. B must not refuse the rotation: that row would be
     * parked as failed and B would sit at epoch 0 while every later epoch is a gap. B installs epoch 1
     * with C resolved to C2, keeps the tombstone, and continues to author and sync under the new key.
     */
    @Test
    fun `a rotation naming an identity this device already saw revoked still installs the new epoch`() {
        val a = Device(1)
        val b = Device(2)
        val c = Device(3)
        val dPub = TestIdentity(4).pub
        val c2 = TestIdentity(5).pub
        val members = listOf(a.pub, b.pub, c.pub, dPub)
        listOf(a, b, c).forEach { it.join(members, creator = a.pub) }
        assertTrue(runBlocking { b.groupRepo.applyIdentityRevocation(groupId, c.pub, c2, nowSecs() - 100, "rev") })
        assertEquals(listOf(a.pub, b.pub, c2, dPub), b.group().members)

        runBlocking { a.rotateGroupKey(groupId, dPub) }
        assertEquals(listOf(a.pub, b.pub, c.pub), a.group().members)
        val rotationForB = rotationsFor(a, b.pub).single()

        assertEquals(IngestOutcome.APPLIED, ingest(b, rotationForB))

        assertEquals(1, b.group().keyEpoch)
        assertEquals(a.keyForEpoch(1), b.keyForEpoch(1))
        assertEquals(listOf(a.pub, b.pub, c2), b.group().members)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, b.row(rotationForB.id)?.applyState)
        // The tombstone survives the rotation: C's old key still cannot come back on its own.
        assertFalse(runBlocking { b.groupRepo.applyMemberSelfUpdate(groupId, c.pub, nowSecs(), "back", true, null, 1) })
        // A's post-rotation meta (roster without C2) resolves the same way instead of dropping C2.
        val metaAfterRotation = runBlocking {
            a.eventDao.getEventsByType(groupId, "group_meta").filter { it.pubkey == a.pub }
                .mapNotNull { it.originalEventJson?.let(NostrEvent::fromJson) }.maxByOrNull { it.createdAt }
        }
        assertEquals(IngestOutcome.APPLIED, ingest(b, checkNotNull(metaAfterRotation)))
        assertEquals(listOf(a.pub, b.pub, c2), b.group().members)

        // A hears of the revocation after its rotation: both devices now hold the same roster, and B is a
        // live member at epoch 1 that authors under the new key and A reads.
        assertTrue(runBlocking { a.groupRepo.applyIdentityRevocation(groupId, c.pub, c2, nowSecs() - 100, "rev") })
        assertEquals(b.group().members, a.group().members)
        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        val e1 = authorExpense(b, amount = 500, description = "after the resolved rotation")
        router.pump()
        val e1AtA = checkNotNull(a.row(e1.id))
        assertEquals(EventEntity.APPLY_STATE_APPLIED, e1AtA.applyState)
        assertEquals(
            "after the resolved rotation",
            decryptExpense(a, e1AtA, checkNotNull(a.keyForEpoch(1))).description
        )
        assertEquals(0, a.stats(ep(b)).rejected)
        // B declines to courier A's envelope addressed to the revoked key (not a member in B's view);
        // that is the only record refused in either direction.
        assertEquals(1, b.stats(ep(a)).rejected)
        assertTrue(b.availableDeliveries().none { it.recipient == c.pub })
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
            b2.processor.process(
                rotationForB,
                knownGroupId = groupId,
                context = IngestionContext.RECONCILIATION
            )
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
        val staleMeta = authorMeta(
            a,
            epoch = 0,
            name = "Trip (old)",
            members = members,
            createdAt = now - 200
        )
        // Sep 1 noon: creator removes C (epoch 1).
        runBlocking { a.rotateGroupKey(groupId, cPub) }
        val rotationForB = rotationsFor(a, bPub).single()
        // Sep 2: creator renames again under the new key.
        val rename = authorMeta(
            a,
            epoch = 1,
            name = "Trip 2026",
            members = listOf(
                a.pub,
                bPub
            ),
            createdAt = now + 100
        )

        val orders =
            listOf(
                "rotation, rename, stale" to listOf(rotationForB, rename, staleMeta),
                "rotation, stale, rename" to listOf(rotationForB, staleMeta, rename),
                "stale, rotation, rename" to listOf(staleMeta, rotationForB, rename),
                "rename first is undecryptable until the key lands" to listOf(
                    rename,
                    rotationForB,
                    staleMeta,
                    rename
                )
            )
        for ((label, sequence) in orders) {
            val b = Device(2)
            b.join(members, creator = a.pub)
            sequence.forEach { ingest(b, it) }
            val g = b.group()
            assertEquals(label, 1, g.keyEpoch)
            assertEquals(label, "Trip 2026", g.name)
            assertEquals(
                "$label: the removed member must stay removed",
                setOf(
                    a.pub,
                    bPub
                ),
                g.members.toSet()
            )
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
    fun `a valid rotation for another group pulled for this one cannot advance this group's epoch`() {
        // The relay pull for group G also returns this member's per-recipient envelopes for every other
        // group. A creator-signed rotation for H, handed to the processor under G's scope, must be
        // refused: the group an event belongs to is its own signed tag, never the pull's.
        val creator = Device(1)
        val receiver = Device(2)
        val removed = TestIdentity(3).pub
        val roster = listOf(creator.pub, receiver.pub, removed)
        receiver.join(roster, creator = creator.pub)
        val otherId = UUID.randomUUID().toString()
        runBlocking {
            receiver.groupRepo.save(
                Group(otherId, "Other", createdBy = creator.pub, createdAt = 2, members = roster, relays = emptyList()),
                GroupEncryption(CompressionUtil).generateGroupKey()
            )
        }
        val epochOneKey = GroupEncryption(CompressionUtil).generateGroupKey()
        val conversationKey = Nip44.getConversationKey(creator.identity.priv, receiver.pub.hexToBytes())
        val payload = KeyRotation(
            1,
            mapOf(receiver.pub to Nip44.encrypt(epochOneKey, conversationKey)),
            listOf(creator.pub, receiver.pub),
            removed
        )
        val forOther = creator.signer.createSignedEvent(
            otherId,
            "key_rotation",
            Nip44.encrypt(json.encodeToString(KeyRotation.serializer(), payload), conversationKey),
            recipientPubkey = receiver.pub
        )
        assertTrue(forOther.verify())

        val result = runBlocking {
            receiver.processor.process(forOther, knownGroupId = groupId, context = IngestionContext.RECONCILIATION)
        }

        assertEquals(IngestOutcome.REJECTED, result.outcome)
        assertEquals("out of scope", result.reason)
        assertEquals(0, receiver.group().keyEpoch)
        assertEquals(roster, receiver.group().members)
        assertNull(receiver.keyForEpoch(1))
        assertNull(receiver.row(forOther.id))
        assertEquals(0, runBlocking { receiver.groupRepo.getById(otherId) }?.keyEpoch)
        // Under its own scope the very same event applies.
        val own = runBlocking {
            receiver.processor.process(forOther, knownGroupId = otherId, context = IngestionContext.RECONCILIATION)
        }
        assertEquals(IngestOutcome.APPLIED, own.outcome)
        assertEquals(1, runBlocking { receiver.groupRepo.getById(otherId) }?.keyEpoch)
        assertEquals(0, receiver.group().keyEpoch)
    }

    @Test
    fun `a courier refuses another member's gift wrap whose signed group is not the session's`() {
        val author = Device(1)
        val courier = Device(2)
        val recipient = Device(3)
        val members = listOf(author.pub, courier.pub, recipient.pub)
        listOf(author, courier, recipient).forEach { it.join(members, creator = author.pub) }
        val otherId = UUID.randomUUID().toString()
        val otherKey = GroupEncryption(CompressionUtil).generateGroupKey()
        val other =
            Group(otherId, "Other", createdBy = author.pub, createdAt = 1_000, members = members, relays = emptyList())
        runBlocking { listOf(author, courier, recipient).forEach { it.groupRepo.save(other, otherKey) } }
        val uuid = UUID.randomUUID().toString()
        val expense = Expense(
            id = uuid,
            amount = 300,
            currency = "USD",
            description = "in H",
            paidBy = author.pub,
            splitType = SplitType.EQUAL,
            splitAmong = splitAmong(members, 300),
            timestamp = nowSecs()
        )
        val inner = author.signer.createSignedEvent(
            otherId,
            "expense",
            author.encryption.encrypt(json.encodeToString(Expense.serializer(), expense), otherKey),
            expenseUuid = uuid
        )
        val wrap = author.giftWrap.wrapIfEnabled(inner, recipient.pub)
        assertTrue(wrap.verify())
        assertEquals(otherId, wrap.tag("g"))
        val item = InventoryItem(wrap.id, NearbyWire.KIND_DELIVERY, r = recipient.pub)

        val inG = runBlocking { courier.store.ingest(groupId, item, wrap.toJson(), author.pub) }

        assertEquals(RecordOutcome.REJECTED, inG.outcome)
        assertNull("a G session must not retain a signed H envelope", courier.delivery(wrap.id))
        assertTrue(runBlocking { courier.store.inventory(groupId) }.none { it.id == wrap.id })
        // In H's own session the same envelope is carried and offered onward.
        val inH = runBlocking { courier.store.ingest(otherId, item, wrap.toJson(), author.pub) }
        assertEquals(RecordOutcome.CARRIED, inH.outcome)
        assertEquals(otherId, courier.delivery(wrap.id)?.groupId)
        assertTrue(runBlocking { courier.store.inventory(otherId) }.any { it.id == wrap.id })
    }

    @Test
    fun `an envelope filed under the wrong group by an older build is refiled, not vouched for`() {
        // Rows written before outer-group checking may carry another group's label. A CARRIED receipt
        // for H must mean H's inventory holds the envelope, so the mislabelled row is replaced.
        val author = Device(1)
        val courier = Device(2)
        val recipient = Device(3)
        val members = listOf(author.pub, courier.pub, recipient.pub)
        listOf(author, courier, recipient).forEach { it.join(members, creator = author.pub) }
        val otherId = UUID.randomUUID().toString()
        val otherKey = GroupEncryption(CompressionUtil).generateGroupKey()
        val other =
            Group(otherId, "Other", createdBy = author.pub, createdAt = 1_000, members = members, relays = emptyList())
        runBlocking { listOf(author, courier, recipient).forEach { it.groupRepo.save(other, otherKey) } }
        val uuid = UUID.randomUUID().toString()
        val expense = Expense(
            id = uuid,
            amount = 300,
            currency = "USD",
            description = "in H",
            paidBy = author.pub,
            splitType = SplitType.EQUAL,
            splitAmong = splitAmong(members, 300),
            timestamp = nowSecs()
        )
        val inner = author.signer.createSignedEvent(
            otherId,
            "expense",
            author.encryption.encrypt(json.encodeToString(Expense.serializer(), expense), otherKey),
            expenseUuid = uuid
        )
        val wrap = author.giftWrap.wrapIfEnabled(inner, recipient.pub)
        val wrapJson = wrap.toJson()
        runBlocking {
            courier.deliveryDao.insertIfNew(
                DeliveryEntity(
                    envelopeId = wrap.id, groupId = groupId, recipient = recipient.pub, eventId = null,
                    envelopeJson = wrapJson, eventType = DeliveryEntity.TYPE_GIFT_WRAP,
                    state = DeliveryEntity.STATE_AVAILABLE, source = DeliveryEntity.SOURCE_CARRIED,
                    createdAt = wrap.createdAt, receivedAt = nowSecs(), sizeBytes = wrapJson.length
                )
            )
        }
        val item = InventoryItem(wrap.id, NearbyWire.KIND_DELIVERY, r = recipient.pub)
        assertEquals(listOf(item), runBlocking { courier.store.selectWanted(otherId, listOf(item), author.pub) })

        val inH = runBlocking { courier.store.ingest(otherId, item, wrapJson, author.pub) }

        assertEquals(RecordOutcome.CARRIED, inH.outcome)
        assertEquals(otherId, courier.delivery(wrap.id)?.groupId)
        assertEquals(recipient.pub, courier.delivery(wrap.id)?.recipient)
        assertTrue(runBlocking { courier.store.inventory(otherId) }.any { it.id == wrap.id })
        assertTrue(runBlocking { courier.store.inventory(groupId) }.none { it.id == wrap.id })
        // A correctly filed duplicate is still a plain receipt.
        assertEquals(
            RecordOutcome.CARRIED,
            runBlocking {
                courier.store.ingest(otherId, item, wrapJson, author.pub)
            }.outcome
        )
        assertEquals(1, runBlocking { courier.deliveryDao.getEnvelopeIds(otherId) }.count { it == wrap.id })
    }

    /** A signed expense by [device], not stored anywhere: callers decide which phone ingests it. */
    private fun signedExpense(device: Device, amount: Long, description: String): NostrEvent {
        val group = device.group()
        val key = checkNotNull(runBlocking { device.groupRepo.getGroupKey(groupId) })
        val id = UUID.randomUUID().toString()
        val expense = Expense(
            id = id,
            amount = amount,
            currency = "USD",
            description = description,
            paidBy = device.pub,
            splitType = SplitType.EQUAL,
            splitAmong = splitAmong(group.members, amount),
            timestamp = nowSecs()
        )
        val encrypted = device.encryption.encrypt(json.encodeToString(Expense.serializer(), expense), key)
        return device.signer.createSignedEvent(groupId, "expense", encrypted, expenseUuid = id)
    }

    /** A signed correction of [original] by [device], not stored anywhere. */
    private fun signedCorrection(device: Device, original: NostrEvent, amount: Long): NostrEvent {
        val group = device.group()
        val key = checkNotNull(runBlocking { device.groupRepo.getGroupKey(groupId) })
        val id = checkNotNull(original.tag("x"))
        val expense = Expense(
            id = id,
            amount = amount,
            currency = "USD",
            description = "corrected",
            paidBy = device.pub,
            splitType = SplitType.EQUAL,
            splitAmong = splitAmong(group.members, amount),
            timestamp = nowSecs()
        )
        val encrypted = device.encryption.encrypt(json.encodeToString(Expense.serializer(), expense), key)
        return device.signer.createSignedCommandEvent(groupId, "expense_correction", encrypted, expenseUuid = id)
    }

    /**
     * A signed correction of [original] by its author on [device], persisted through the publisher.
     * [amount] is the corrected amount.
     */
    private fun authorCorrection(device: Device, original: NostrEvent, amount: Long): NostrEvent = runBlocking {
        val group = device.group()
        val key = checkNotNull(device.groupRepo.getGroupKey(groupId))
        val id = checkNotNull(original.tag("x"))
        val expense = Expense(
            id = id,
            amount = amount,
            currency = "USD",
            description = "corrected",
            paidBy = device.pub,
            splitType = SplitType.EQUAL,
            splitAmong = splitAmong(group.members, amount),
            timestamp = nowSecs()
        )
        val encrypted = device.encryption.encrypt(json.encodeToString(Expense.serializer(), expense), key)
        val commandId = UUID.randomUUID().toString()
        val event = device.signer.createSignedCommandEvent(
            groupId,
            "expense_correction",
            encrypted,
            expenseUuid = id,
            commandId = commandId
        )
        check(
            device.publisher.publishMutation(
                event,
                group,
                "expense_correction",
                id,
                commandId,
                expectedRevisionId = original.id
            )
        ) { "correction of $id was not saved" }
        event
    }

    @Test
    fun `a correction that arrives before its original is held and applied when the original lands`() {
        val a = Device(1)
        val b = Device(2)
        listOf(a, b).forEach { it.join(listOf(a.pub, b.pub), creator = a.pub) }
        val original = authorExpense(a, amount = 100, description = "dinner")
        val correction = authorCorrection(a, original, amount = 200)

        val first = runBlocking {
            b.processor.process(correction, knownGroupId = groupId, context = IngestionContext.RECONCILIATION)
        }
        assertEquals(IngestOutcome.DEFERRED, first.outcome)
        assertEquals("missing original", first.reason)
        assertEquals(EventEntity.APPLY_STATE_PENDING, b.row(correction.id)?.applyState)
        // Invisible to the ledger while held: the applied view has no correction yet.
        assertTrue(b.appliedRows().none { it.eventId == correction.id })
        assertEquals(1, runBlocking { b.store.pendingCount(groupId) })
        // But offered onward: another phone may hold the original and be waiting for exactly this record.
        assertTrue(runBlocking { b.store.inventory(groupId) }.any { it.id == correction.id })

        val second = runBlocking {
            b.processor.process(original, knownGroupId = groupId, context = IngestionContext.RECONCILIATION)
        }
        assertEquals(IngestOutcome.APPLIED, second.outcome)
        assertEquals(1, runBlocking { b.processor.retryDeferred(groupId) })
        assertEquals(EventEntity.APPLY_STATE_APPLIED, b.row(correction.id)?.applyState)
        assertEquals(0, runBlocking { b.store.pendingCount(groupId) })
        assertEquals(setOf(original.id, correction.id), b.appliedRows().map { it.eventId }.toSet())
    }

    @Test
    fun `a dropped original frame is retried without losing the correction that arrived first`() {
        val a = Device(1)
        val b = Device(2)
        listOf(a, b).forEach { it.join(listOf(a.pub, b.pub), creator = a.pub) }
        // Signed records only, no envelopes: the transfer has exactly one path for each record.
        val original = signedExpense(a, amount = 100, description = "dinner")
        val correction = signedCorrection(a, original, amount = 200)
        assertEquals(IngestOutcome.APPLIED, ingest(a, original))
        assertEquals(IngestOutcome.APPLIED, ingest(a, correction))
        var dropped = false
        a.transport.dropIf = { message ->
            (message is Record && message.id == original.id && !dropped).also { if (it) dropped = true }
        }

        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertTrue(dropped)
        assertNull(b.row(original.id))
        // The correction crossed first and is held, not discarded; the original is still in flight.
        assertEquals(EventEntity.APPLY_STATE_PENDING, b.row(correction.id)?.applyState)
        assertEquals(PeerPhase.TRANSFERRING, b.phase(ep(a)))

        scheduler.advanceTimeBy(PeerSession.TRANSFER_TIMEOUT_MS + PeerSession.WATCHDOG_INTERVAL_MS)
        scheduler.runCurrent()
        router.pump()

        assertEquals(EventEntity.APPLY_STATE_APPLIED, b.row(original.id)?.applyState)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, b.row(correction.id)?.applyState)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
    }

    @Test
    fun `an expense refused for a missing key is asked for again when the key lands in a later snapshot`() {
        val a = Device(1)
        val b = Device(2)
        listOf(a, b).forEach { it.join(listOf(a.pub, b.pub), creator = a.pub) }
        // A is at epoch 1 with an expense under it; B is still at epoch 0 and has no rotation yet.
        val key1 = GroupEncryption(CompressionUtil).generateGroupKey()
        runBlocking {
            a.groupRepo.saveGroupKeyForEpoch(groupId, 1, key1)
            assertTrue(a.groupRepo.applyKeyRotation(groupId, 1, listOf(a.pub, b.pub), emptyMap()))
        }
        val expense = authorExpense(a, amount = 100, description = "under epoch 1")

        a.activate()
        b.activate()
        connect(a, b)
        router.pump()
        assertNull(b.row(expense.id))
        assertEquals(PeerPhase.INCOMPLETE, b.phase(ep(a)))
        // The signed event and A's gift-wrapped copy for B are both refused for want of the key.
        assertTrue(b.stats(ep(a)).rejected >= 1)

        // The rotation reaches A's store now (it was published to B by the creator, A is the creator here).
        val convKey = Nip44.getConversationKey(a.identity.priv, b.pub.hexToBytes())
        val payload = KeyRotation(1, mapOf(b.pub to Nip44.encrypt(key1, convKey)), listOf(a.pub, b.pub), "")
        val rotation = a.signer.createSignedEvent(
            groupId,
            "key_rotation",
            Nip44.encrypt(json.encodeToString(KeyRotation.serializer(), payload), convKey),
            recipientPubkey = b.pub
        )
        runBlocking { a.publisher.publishDirect(rotation, groupId, rotation.content, "key_rotation", null) }
        a.coordinator.notifyGroupChanged(groupId)
        router.pump()

        assertEquals(1, b.group().keyEpoch)
        assertEquals(key1, b.keyForEpoch(1))
        val requested = b.transport.sentMessages().filterIsInstance<Want>().flatMap {
            it.ids
        }.count { it == expense.id }
        assertTrue(
            "the refused expense must be asked for again once the key landed, requested $requested time(s)",
            requested >= 2
        )
        assertEquals(EventEntity.APPLY_STATE_APPLIED, b.row(expense.id)?.applyState)
        assertEquals(0, b.stats(ep(a)).rejected)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(a)))
        assertEquals(PeerPhase.UP_TO_DATE, a.phase(ep(b)))
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
        assertEquals(
            RoomReconciliationStore.MAX_CARRIED_PER_GROUP,
            runBlocking { b.deliveryDao.countCarried(groupId) }
        )
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
        assertEquals(
            RoomReconciliationStore.MAX_CARRIED_PER_GROUP,
            runBlocking { b.deliveryDao.countCarried(groupId) }
        )
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

    @Test
    fun `retrying a persisted member rename after removal cannot resurrect the member`() {
        val a = Device(1)
        val b = Device(2)
        val c = Device(3)
        val members = listOf(a.pub, b.pub, c.pub)
        listOf(a, b, c).forEach { it.join(members, a.pub) }
        val rename = authorMeta(c, 0, "Trip", members, nowSecs() - 100)
        val failedEffects = mockk<EventPostProcessor>()
        coEvery { failedEffects.handle(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            PostProcessOutcome.FAILED
        val interruptedProcessor = EventProcessor(
            a.eventDao, a.groupRepo, a.encryption, a.signer,
            a.identity.contract, a.giftWrap, EventValidator(), failedEffects,
            MembershipHistory(a.eventDao, a.groupRepo, a.identity.contract)
        )
        runBlocking {
            assertEquals(
                IngestOutcome.DEFERRED,
                interruptedProcessor.process(
                    rename,
                    context = IngestionContext.RECONCILIATION
                ).outcome
            )
            a.rotateGroupKey(groupId, c.pub)
            assertEquals(1, a.group().keyEpoch)
            assertFalse(c.pub in a.group().members)
            a.processor.retryDeferred(groupId)
        }
        assertFalse("old metadata cannot grant current membership", c.pub in a.group().members)
        assertEquals(EventEntity.APPLY_STATE_FAILED, a.row(rename.id)?.applyState)
        assertFalse(runBlocking { a.store.isAuthorizedForGroup(groupId, c.pub) })
        assertEquals(IngestOutcome.REJECTED, ingest(a, rename))
    }

    @Test
    fun `gifted join releases pending rotation over an already open session`() {
        val a = Device(1)
        val b = Device(2)
        val c = Device(3)
        val courier = Device(4)
        val removed = TestIdentity(5).pub
        val members = listOf(a.pub, b.pub, c.pub, courier.pub, removed)
        listOf(a, c, courier).forEach { it.join(members, a.pub) }
        b.join(members - c.pub, a.pub)
        runBlocking { a.rotateGroupKey(groupId, removed) }
        val rotation = rotationsFor(a, b.pub).single()
        assertEquals(IngestOutcome.DEFERRED, ingest(b, rotation))
        b.activate()
        courier.activate()
        connect(courier, b)
        router.pump()
        assertEquals(PeerPhase.WAITING_DEPENDENCY, b.phase(ep(courier)))
        val join = authorMeta(c, 0, "Trip", members - removed)
        val gift = c.giftWrap.wrapIfEnabled(join, b.pub)
        runBlocking {
            assertEquals(
                RecordOutcome.CARRIED,
                courier.store.ingest(
                    groupId,
                    InventoryItem(gift.id, NearbyWire.KIND_DELIVERY, r = b.pub),
                    gift.toJson(),
                    a.pub
                ).outcome
            )
        }
        router.pump()
        assertEquals(1, b.group().keyEpoch)
        assertEquals(a.keyForEpoch(1), b.keyForEpoch(1))
        assertFalse(removed in b.group().members)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, b.row(rotation.id)?.applyState)
        assertEquals(PeerPhase.UP_TO_DATE, b.phase(ep(courier)))
        assertEquals(PeerPhase.UP_TO_DATE, courier.phase(ep(b)))
    }

    @Test
    fun `stale cached key after a rotation never authorizes an old creator roster`() {
        val a = Device(1)
        val b = Device(2)
        val removed = TestIdentity(3).pub
        val members = listOf(a.pub, b.pub, removed)
        listOf(a, b).forEach { it.join(members, a.pub) }
        val oldMeta = authorMeta(a, 0, "Old-key rename", members, nowSecs() - 10)
        runBlocking { a.rotateGroupKey(groupId, removed) }
        assertEquals(IngestOutcome.APPLIED, ingest(b, rotationsFor(a, b.pub).single()))
        runBlocking {
            val result = b.processor.process(
                oldMeta,
                knownGroupKey = groupKey,
                context = IngestionContext.RECONCILIATION
            )
            assertEquals(IngestOutcome.APPLIED, result.outcome)
        }
        assertEquals(0, b.row(oldMeta.id)?.keyEpoch)
        assertFalse(removed in b.group().members)
        val expense = authorExpense(a, 120, "new epoch")
        assertEquals(
            IngestOutcome.APPLIED,
            runBlocking {
                b.processor.process(
                    expense,
                    knownGroupKey = groupKey,
                    context = IngestionContext.RECONCILIATION
                ).outcome
            }
        )
        assertEquals(1, b.row(expense.id)?.keyEpoch)
    }

    @Test
    fun `full courier admits a delivery-kind rotation and forwards it without the author`() {
        val a = Device(1)
        val b = Device(2)
        val c = Device(3)
        val courier = Device(4)
        val removed = TestIdentity(5).pub
        val members = listOf(a.pub, b.pub, c.pub, courier.pub, removed)
        listOf(a, b, c, courier).forEach { it.join(members, a.pub) }
        runBlocking { a.rotateGroupKey(groupId, removed) }
        val rotation = rotationsFor(a, c.pub).single()
        val base = nowSecs() - 3600
        runBlocking {
            repeat(RoomReconciliationStore.MAX_CARRIED_PER_GROUP) { i ->
                val old = DeliveryEntity(
                    "%064x".format(i + 1), groupId, c.pub, null, "{}",
                    DeliveryEntity.TYPE_GIFT_WRAP, DeliveryEntity.STATE_AVAILABLE,
                    DeliveryEntity.SOURCE_CARRIED, base + i, base + i, 2
                )
                b.deliveryDao.insert(old)
                courier.deliveryDao.insert(
                    old.copy(
                        state = DeliveryEntity.STATE_CONSUMED,
                        envelopeJson = null
                    )
                )
            }
            assertEquals(
                RecordOutcome.CARRIED,
                courier.store.ingest(
                    groupId,
                    InventoryItem(rotation.id, NearbyWire.KIND_EVENT),
                    rotation.toJson(),
                    a.pub
                ).outcome
            )
            val item = courier.store.inventory(groupId).single { it.id == rotation.id }
            assertEquals(NearbyWire.KIND_DELIVERY, item.t)
            assertTrue(b.store.selectWanted(groupId, listOf(item), courier.pub).contains(item))
        }
        b.activate()
        courier.activate()
        connect(courier, b)
        router.pump()
        assertNotNull(b.delivery(rotation.id)?.envelopeJson)
        assertEquals(
            RoomReconciliationStore.MAX_CARRIED_PER_GROUP,
            runBlocking { b.deliveryDao.countCarried(groupId) }
        )
        courier.coordinator.deactivate()
        router.pump()
        // Keep only the real rotation for the onward leg: old fixture envelopes intentionally are not signed.
        runBlocking {
            b.deliveryDao.evictOldestCarried(
                groupId,
                RoomReconciliationStore.MAX_CARRIED_PER_GROUP - 1
            )
        }
        c.activate()
        connect(b, c)
        router.pump()
        assertEquals(1, c.group().keyEpoch)
        assertEquals(a.keyForEpoch(1), c.keyForEpoch(1))
        assertFalse(removed in c.group().members)
        assertEquals(EventEntity.APPLY_STATE_APPLIED, c.row(rotation.id)?.applyState)
    }
}
