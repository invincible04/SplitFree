package com.splitfree.domain.usecase.export

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.export.ExportedEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.util.hexToBytes
import com.splitfree.domain.util.toHex
import com.splitfree.domain.validation.EventValidator
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExportImportUseCaseTest {
    private val eventRepo = mockk<EventRepositoryContract>(relaxed = true)
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val encryption = mockk<GroupEncryption>()
    private val eventValidator =
        mockk<EventValidator> {
            every { isWithinRateLimit(any()) } returns true
            every { isWithinGroupRateLimit(any()) } returns true
            every { isTimestampValid(any()) } returns true
            every { isTimestampValidLenient(any()) } returns true
            every { isContentSafe(any()) } returns true
            every { isCorrectionAuthorValid(any(), any(), any()) } returns true
            every { isDeletedExpense(any(), any(), any()) } returns false
            every { isExpenseValid(any(), any()) } returns true
            every { isSettlementValid(any(), any(), any()) } returns true
        }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>(), any()) } returns 0
        // The relaxed repo would otherwise swallow the transaction body; run it like Room does.
        coEvery { eventRepo.withTransaction(captureLambda<suspend () -> Int>()) } coAnswers {
            lambda<suspend () -> Int>().captured.invoke()
        }
        // Import decrypts with the epoch key of each row when available; default to "not stored".
        coEvery { groupRepo.getGroupKeyForEpoch(any(), any()) } returns null
    }

    @After
    fun teardown() {
        unmockkStatic(android.util.Log::class)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val groupKey =
        Base64
            .getEncoder()
            .encodeToString(ByteArray(32) { 1 })
    private val groupId = "test-group-id"
    private val memberPrivKey = ByteArray(32) { 1 }
    private val memberPubkey = NostrEvent.pubkeyFromPrivkey(memberPrivKey)
    private val strangerPrivKey = ByteArray(32) { 2 }

    private val sampleEntity =
        EventSnapshot(
            eventId = "evt1",
            groupId = groupId,
            pubkey = memberPubkey,
            createdAt = 1700000000,
            kind = 30078,
            contentEncrypted = "enc",
            eventType = "expense",
            expenseUuid = "uuid1",
            sig = "sig1",
            receivedAt = 1700000000,
            originalEventJson = "{}"
        )

    private val group =
        Group(
            id = groupId,
            name = "Test",
            createdBy = memberPubkey,
            createdAt = 1700000000,
            members = listOf(memberPubkey, "pub2"),
            relays = listOf("wss://relay.test")
        )

    /** A decrypted expense payload that is valid for [paidBy] as long as they are a member. */
    private fun expenseJson(id: String = "uuid1", paidBy: String = memberPubkey, amount: Long = 100): String =
        """{"id":"$id","amount":$amount,"currency":"USD","description":"test","paid_by":"$paidBy",""" +
            """"split_type":"equal","split_among":[{"pubkey":"$paidBy","share":$amount}],"timestamp":1000}"""

    private fun buildSignedExportedEvent(
        privateKey: ByteArray = memberPrivKey,
        eventType: String = "expense",
        expenseUuid: String? = "uuid1",
        contentEncrypted: String = "enc",
        createdAt: Long = 1700000000,
        gid: String = groupId,
        mutateWrapper: (ExportedEvent) -> ExportedEvent = { it }
    ): ExportedEvent {
        val pubkey = NostrEvent.pubkeyFromPrivkey(privateKey)
        val signedEvent = NostrEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            kind = 30078,
            tags = buildList {
                add(listOf("g", gid))
                add(listOf("t", eventType))
                if (expenseUuid != null) add(listOf("x", expenseUuid))
            },
            content = contentEncrypted
        ).sign(privateKey)
        val wrapper = ExportedEvent(
            eventId = signedEvent.id,
            pubkey = signedEvent.pubkey,
            createdAt = signedEvent.createdAt,
            kind = signedEvent.kind,
            contentEncrypted = signedEvent.content,
            eventType = eventType,
            expenseUuid = expenseUuid,
            sig = signedEvent.sig,
            originalEventJson = signedEvent.toJson()
        )
        return mutateWrapper(wrapper)
    }

    // --- ExportGroupUseCase ---

    @Test
    fun `export produces version 2 JSON whose MAC verifies under the exporter's identity key`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { groupRepo.getById(groupId) } returns group
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventsByGroup(groupId) } returns listOf(sampleEntity)
        val identity = mockk<IdentityContract>()
        every { identity.getPrivateKeyBytes() } answers { memberPrivKey.copyOf() }
        every { identity.getPublicKeyBytes() } returns memberPubkey.hexToBytes()
        val useCase = ExportGroupUseCase(eventRepo, groupRepo, identity)

        val result = useCase(groupId)
        val export = json.decodeFromString<SplitFreeExport>(result)

        assertEquals(2, export.version)
        assertEquals(groupId, export.groupId)
        assertEquals(1, export.events.size)
        assertEquals("evt1", export.events[0].eventId)
        assertTrue(export.encryptedGroupKey.isNotEmpty())
        assertEquals("Test", export.groupName)
        assertEquals(listOf("wss://relay.test"), export.relays)
        // Independent recomputation: HKDF(privkey) → HMAC over the canonical body with hmac blanked.
        assertEquals(independentMac(export, memberPrivKey), export.hmac)
    }

    @Test
    fun `export with no group key still authenticates the file and leaves the keys empty`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        coEvery { groupRepo.getById(groupId) } returns null
        coEvery { eventRepo.getEventsByGroup(groupId) } returns listOf(sampleEntity)
        val identity = mockk<IdentityContract>()
        every { identity.getPrivateKeyBytes() } answers { memberPrivKey.copyOf() }
        val useCase = ExportGroupUseCase(eventRepo, groupRepo, identity)

        val result = useCase(groupId)
        val export = json.decodeFromString<SplitFreeExport>(result)
        assertEquals(independentMac(export, memberPrivKey), export.hmac)
        assertEquals("", export.encryptedGroupKey)
        assertTrue(export.encryptedEpochKeys.isEmpty())
    }

    @Test
    fun `export with empty events`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { groupRepo.getById(groupId) } returns group
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventsByGroup(groupId) } returns emptyList()
        val identity = mockk<IdentityContract>()
        every { identity.getPrivateKeyBytes() } answers { memberPrivKey.copyOf() }
        every { identity.getPublicKeyBytes() } returns memberPubkey.hexToBytes()
        val useCase = ExportGroupUseCase(eventRepo, groupRepo, identity)

        val result = useCase(groupId)
        val export = json.decodeFromString<SplitFreeExport>(result)
        assertTrue(export.events.isEmpty())
    }

    @Test
    fun `export zeroes the private key it borrowed`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { groupRepo.getById(groupId) } returns group
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventsByGroup(groupId) } returns emptyList()
        val handedOut = memberPrivKey.copyOf()
        val identity = mockk<IdentityContract>()
        every { identity.getPrivateKeyBytes() } returns handedOut
        every { identity.getPublicKeyBytes() } returns memberPubkey.hexToBytes()

        ExportGroupUseCase(eventRepo, groupRepo, identity)(groupId)

        assertTrue(handedOut.all { it == 0.toByte() })
    }

    // --- ImportGroupUseCase ---

    /**
     * Test-side reimplementation of the export MAC so the tests do not merely assert that the
     * production code agrees with itself: HKDF-Extract(salt="splitfree-export-v2", ikm=privkey),
     * HKDF-Expand(info="mac", 32), HMAC-SHA256 over the canonical JSON with `hmac` blanked.
     */
    private fun independentMac(export: SplitFreeExport, privKey: ByteArray): String {
        val prk = Nip44.hkdfExtract("splitfree-export-v2".toByteArray(), privKey)
        val macKey = Nip44.hkdfExpand(prk, "mac".toByteArray(), 32)
        val body = Json { encodeDefaults = true }.encodeToString(SplitFreeExport.serializer(), export.copy(hmac = ""))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** Returns [export] with its `hmac` set for [privKey]'s identity. */
    private fun signed(export: SplitFreeExport, privKey: ByteArray = memberPrivKey): SplitFreeExport =
        export.copy(hmac = independentMac(export, privKey))

    private fun buildExportJson(events: List<ExportedEvent>, hmac: String = "", gid: String = groupId): String {
        val export = SplitFreeExport(version = 2, groupId = gid, exportedAt = 1700000000, events = events)
        val authenticated = if (hmac.isEmpty()) signed(export) else export.copy(hmac = hmac)
        return Json.encodeToString(SplitFreeExport.serializer(), authenticated)
    }

    private val identityMock = mockk<IdentityContract>().also {
        every { it.getPublicKeyHex() } returns memberPubkey
        // Callers zero the key after use, so hand out a fresh copy on every call like IdentityManager does.
        every { it.getPrivateKeyBytes() } answers { memberPrivKey.copyOf() }
        every { it.getPublicKeyBytes() } returns memberPubkey.hexToBytes()
    }

    private fun newImport() = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)

    @Test
    fun `import stores new events and returns count`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { encryption.decrypt(any(), any()) } returns expenseJson()
        coEvery { eventRepo.insert(any<EventSnapshot>()) } just Runs

        val events = listOf(buildSignedExportedEvent())
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)
        val count = importUseCase(buildExportJson(events))
        assertEquals(1, count)
    }

    @Test
    fun `import deduplicates existing events`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        val existingEvent = buildSignedExportedEvent()
        coEvery { eventRepo.getEventIds(groupId) } returns listOf(existingEvent.eventId)
        coEvery { groupRepo.getById(groupId) } returns group

        val events = listOf(existingEvent)
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)
        val count = importUseCase(buildExportJson(events))
        assertEquals(0, count)
    }

    @Test
    fun `import skips events from non-members`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group

        val events = listOf(buildSignedExportedEvent(privateKey = strangerPrivKey))
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)
        val count = importUseCase(buildExportJson(events))
        assertEquals(0, count)
    }

    @Test
    fun `import allows group_meta from non-members`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { encryption.decrypt(any(), any()) } returns "decrypted"
        coEvery { eventRepo.insert(any<EventSnapshot>()) } just Runs

        // Reset rate limiter to avoid interference
        every { eventValidator.isWithinRateLimit(any()) } returns true

        val events = listOf(
            buildSignedExportedEvent(
                privateKey = strangerPrivKey,
                eventType = "group_meta",
                expenseUuid = null
            )
        )
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)
        val count = importUseCase(buildExportJson(events))
        assertEquals(1, count)
    }

    @Test
    fun `import rejects a version 1 file with a clear message before touching anything`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        val v1 = """{"version":1,"groupId":"$groupId","exportedAt":0,"events":[],"hmac":"abc"}"""

        val e = assertThrows(IllegalArgumentException::class.java) { runBlocking { newImport()(v1) } }

        assertEquals("Unsupported backup version 1; re-export from the current app", e.message)
        coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        coVerify(exactly = 0) { eventRepo.withTransaction(any<suspend () -> Int>()) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects a newer version`() = runBlocking {
        val v3 = """{"version":3,"groupId":"$groupId","exportedAt":0,"events":[],"hmac":"abc"}"""
        newImport()(v3)
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `import rejects unknown group`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns null
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns null
        newImport()(buildExportJson(emptyList()))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects missing HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        val noHmac = """{"version":2,"groupId":"$groupId","exportedAt":0,"events":[],"hmac":""}"""
        newImport()(noHmac)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects tampered HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        val events = listOf(ExportedEvent("evt1", "pub1", 1700000000, 30078, "enc", "expense", "uuid1", "sig1"))
        val badHmac = "ff".repeat(32)
        val export = SplitFreeExport(groupId = groupId, exportedAt = 0, events = events, hmac = badHmac)
        newImport()(Json.encodeToString(SplitFreeExport.serializer(), export))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects invalid HMAC hex`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        val badJson = """{"version":2,"groupId":"$groupId","exportedAt":0,"events":[],"hmac":"xyz"}"""
        newImport()(badJson)
        Unit
    }

    // --- ImportGroupUseCase: the MAC covers the whole file and is bound to the identity ---

    /** A fully populated, correctly signed export that the tamper tests mutate one field of. */
    private fun signedFullExport(gid: String = groupId): SplitFreeExport = signed(
        SplitFreeExport(
            version = 2,
            groupId = gid,
            exportedAt = 1700000100,
            events = listOf(buildSignedExportedEvent(gid = gid)),
            encryptedGroupKey = encryptKeyToSelf(groupKey),
            groupName = "Trip",
            relays = listOf("wss://relay.test"),
            keyEpoch = 0,
            encryptedEpochKeys = mapOf("0" to encryptKeyToSelf(groupKey))
        )
    )

    private fun assertRejectedUntouched(tampered: SplitFreeExport) {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        val e = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { newImport()(Json.encodeToString(SplitFreeExport.serializer(), tampered)) }
        }
        assertTrue(e.message!!.contains("integrity check failed"))
        // Rejected before the group, the epoch keys or any event were written.
        coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { eventRepo.withTransaction(any<suspend () -> Int>()) }
        coVerify(exactly = 0) { eventRepo.insert(any<EventSnapshot>()) }
    }

    @Test
    fun `MAC covers groupName`() {
        assertRejectedUntouched(signedFullExport().copy(groupName = "Hijacked"))
    }

    @Test
    fun `MAC covers relays`() {
        assertRejectedUntouched(signedFullExport().copy(relays = listOf("wss://attacker.example")))
    }

    @Test
    fun `MAC covers encryptedEpochKeys`() {
        val original = signedFullExport()
        val swapped = original.encryptedEpochKeys + ("1" to encryptKeyToSelf(groupKey))
        assertRejectedUntouched(original.copy(encryptedEpochKeys = swapped))
    }

    @Test
    fun `MAC covers keyEpoch and exportedAt`() {
        assertRejectedUntouched(signedFullExport().copy(keyEpoch = 7))
        assertRejectedUntouched(signedFullExport().copy(exportedAt = 1))
    }

    @Test
    fun `MAC covers events`() {
        val original = signedFullExport()
        // Appending a perfectly valid, properly signed event is still a modification of the file.
        val extra = buildSignedExportedEvent(expenseUuid = "uuid2", contentEncrypted = "enc2")
        assertRejectedUntouched(original.copy(events = original.events + extra))
        // So is flipping the exporter's seal marker on an existing row.
        val marked = original.events.map { it.copy(sig = EventSnapshot.SEAL_SIG_PREFIX + "00".repeat(64)) }
        assertRejectedUntouched(original.copy(events = marked))
    }

    @Test
    fun `MAC signed by another identity is rejected and attributed to an identity mismatch`() {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        val export = SplitFreeExport(version = 2, groupId = groupId, exportedAt = 1700000100, events = emptyList())
        val foreign = signed(export, privKey = strangerPrivKey)

        val e = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { newImport()(Json.encodeToString(SplitFreeExport.serializer(), foreign)) }
        }

        assertTrue(e.message!!.contains("different identity"))
        verify { android.util.Log.w("ImportGroupUseCase", match<String> { it.contains("different identity") }) }
        coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        coVerify(exactly = 0) { eventRepo.withTransaction(any<suspend () -> Int>()) }
    }

    @Test
    fun `import accepts a file whose defaults were omitted by the writer`() = runBlocking {
        // A hand-minified file that leaves out every default still has the same canonical body.
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        val export = signed(SplitFreeExport(groupId = groupId, exportedAt = 5, events = emptyList()))
        val minified = """{"groupId":"$groupId","exportedAt":5,"events":[],"hmac":"${export.hmac}"}"""

        assertEquals(0, newImport()(minified))
    }

    @Test
    fun `import skips event with invalid original JSON signature`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { eventValidator.isWithinRateLimit(any()) } returns true

        val events =
            listOf(
                ExportedEvent(
                    "evt1",
                    memberPubkey,
                    1700000000,
                    30078,
                    "enc",
                    "expense",
                    "uuid1",
                    "sig1",
                    originalEventJson =
                    """{"id":"bad","pubkey":"pub1","created_at":1,""" +
                        """"kind":1,"tags":[],"content":"x","sig":"badsig"}"""
                )
            )
        val count =
            ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(buildExportJson(events))
        assertEquals(0, count)
    }

    @Test
    fun `import skips correction whose author stored no original under that uuid`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        // Another member's original under the same uuid is not this author's expense.
        coEvery { eventRepo.getExpenseByUuid("uuid1", groupId) } returns sampleEntity.copy(pubkey = "other-pub")
        coEvery { eventRepo.getExpenseByAuthor("uuid1", groupId, memberPubkey) } returns null
        every { eventValidator.isWithinRateLimit(any()) } returns true
        every { eventValidator.isCorrectionAuthorValid("expense_correction", memberPubkey, null) } returns false

        val events = listOf(
            buildSignedExportedEvent(eventType = "expense_correction", expenseUuid = "uuid1")
        )
        val count =
            ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(buildExportJson(events))
        assertEquals(0, count)
        coVerify(exactly = 1) { eventRepo.getExpenseByAuthor("uuid1", groupId, memberPubkey) }
        coVerify(exactly = 0) { eventRepo.getExpenseByUuid(any(), any()) }
    }

    @Test
    fun `import handles decryption failure gracefully`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { encryption.decrypt(any(), any()) } throws RuntimeException("bad")
        coEvery { eventRepo.insert(any<EventSnapshot>()) } just Runs
        every { eventValidator.isWithinRateLimit(any()) } returns true

        val events = listOf(buildSignedExportedEvent())
        val count =
            ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(buildExportJson(events))
        assertEquals(1, count) // still imported, decrypted is null
    }

    @Test
    fun `import with null group still imports member events`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns null
        coEvery { groupRepo.save(any(), any()) } just Runs
        every { encryption.decrypt(any(), any()) } returns expenseJson()
        coEvery { eventRepo.insert(any<EventSnapshot>()) } just Runs
        every { eventValidator.isWithinRateLimit(any()) } returns true

        val events = listOf(buildSignedExportedEvent())
        val count =
            ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(buildExportJson(events))
        assertEquals(1, count)
    }

    @Test
    fun `import skips event when originalEventJson is missing`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group

        val unsignedWrapper = ExportedEvent(
            eventId = "evt1",
            pubkey = memberPubkey,
            createdAt = 1700000000,
            kind = 30078,
            contentEncrypted = "enc",
            eventType = "expense",
            expenseUuid = "uuid1",
            sig = "sig",
            originalEventJson = null
        )
        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(
            buildExportJson(listOf(unsignedWrapper))
        )
        assertEquals(0, count)
    }

    @Test
    fun `import stores parsed signed fields when wrapper fields differ`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { encryption.decrypt(any(), any()) } returns expenseJson()
        val inserted = slot<EventSnapshot>()
        coEvery { eventRepo.insert(capture(inserted)) } just Runs

        val event = buildSignedExportedEvent(
            mutateWrapper = {
                it.copy(
                    eventId = "wrapper-id",
                    pubkey = "wrapper-pubkey",
                    createdAt = 123L,
                    kind = 1,
                    contentEncrypted = "wrapper-content",
                    eventType = "group_meta",
                    expenseUuid = "wrapper-uuid",
                    sig = "wrapper-sig"
                )
            }
        )

        val count =
            ImportGroupUseCase(
                eventRepo,
                groupRepo,
                encryption,
                eventValidator,
                identityMock
            )(buildExportJson(listOf(event)))
        assertEquals(1, count)
        val parsed = NostrEvent.fromJson(event.originalEventJson!!)!!
        assertEquals(parsed.id, inserted.captured.eventId)
        assertEquals(parsed.pubkey, inserted.captured.pubkey)
        assertEquals(parsed.createdAt, inserted.captured.createdAt)
        assertEquals(parsed.kind, inserted.captured.kind)
        assertEquals(parsed.content, inserted.captured.contentEncrypted)
        assertEquals(parsed.sig, inserted.captured.sig)
        assertEquals("expense", inserted.captured.eventType)
        assertEquals("uuid1", inserted.captured.expenseUuid)
        coVerify(exactly = 1) { eventRepo.insert(any<EventSnapshot>()) }
    }

    @Test
    fun `import does not drop valid historical events due to live rate limits`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        // Each row decrypts to a payload whose id matches its own x tag.
        every { encryption.decrypt(any(), any()) } answers {
            expenseJson(id = "uuid-" + firstArg<String>().removePrefix("enc-"))
        }
        coEvery { eventRepo.insert(any<EventSnapshot>()) } just Runs

        val events = (1..31).map { i ->
            buildSignedExportedEvent(
                eventType = "expense",
                expenseUuid = "uuid-$i",
                contentEncrypted = "enc-$i",
                createdAt = 1700000000L + i
            )
        }

        val useCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, EventValidator(), identityMock)
        val count = useCase(buildExportJson(events))
        assertEquals(31, count)
    }

    // --- ImportGroupUseCase.replayPostImport: creator bootstrap ---

    private val strangerPubkey = NostrEvent.pubkeyFromPrivkey(strangerPrivKey)
    private val boundCreatedAt = 1_690_000_000L

    /** A freshly imported group: id derived from [memberPubkey], but the local row has no creator yet. */
    private val boundGroupId = GroupIdentity.derive(memberPubkey, boundCreatedAt)
    private val legacyGroup =
        Group(
            id = boundGroupId,
            name = "Imported Group",
            createdBy = "",
            createdAt = 1700000000,
            members = listOf(memberPubkey),
            relays = listOf("wss://relay.test")
        )

    private fun metaJson(createdBy: String, createdAt: Long, members: List<String>, name: String = "Trip") =
        """{"name":"$name","created_by":"$createdBy","created_at":$createdAt,""" +
            """"members":[${members.joinToString(",") { "\"$it\"" }}],"relays":["wss://relay.test"]}"""

    private fun snapshotOf(event: ExportedEvent, gid: String) = EventSnapshot(
        eventId = event.eventId,
        groupId = gid,
        pubkey = event.pubkey,
        createdAt = event.createdAt,
        kind = event.kind,
        contentEncrypted = event.contentEncrypted,
        eventType = event.eventType,
        expenseUuid = event.expenseUuid,
        sig = event.sig,
        receivedAt = event.createdAt,
        originalEventJson = event.originalEventJson
    )

    private fun stubReplayRepo(snapshots: List<EventSnapshot>) {
        coEvery { groupRepo.getGroupKeyForEpoch(boundGroupId, any()) } returns groupKey
        coEvery { groupRepo.getById(boundGroupId) } returns legacyGroup
        coEvery { groupRepo.updateCreator(any(), any(), any()) } just Runs
        coEvery { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) } returns true
        coEvery { eventRepo.getEventIds(boundGroupId) } returns emptyList()
        coEvery { eventRepo.insert(any<EventSnapshot>()) } just Runs
        coEvery { eventRepo.getEventsByGroup(boundGroupId) } returns snapshots
    }

    @Test
    fun `replayPostImport sets createdBy only for the author the group id is bound to`() = runBlocking {
        // Stranger publishes FIRST (earliest group_meta) and claims to be the creator.
        val strangerMeta = buildSignedExportedEvent(
            privateKey = strangerPrivKey,
            eventType = "group_meta",
            expenseUuid = null,
            contentEncrypted = "enc-stranger",
            createdAt = boundCreatedAt + 10,
            gid = boundGroupId
        )
        val creatorMeta = buildSignedExportedEvent(
            privateKey = memberPrivKey,
            eventType = "group_meta",
            expenseUuid = null,
            contentEncrypted = "enc-creator",
            createdAt = boundCreatedAt + 20,
            gid = boundGroupId
        )
        every { encryption.decrypt("enc-stranger", any()) } returns
            metaJson(strangerPubkey, boundCreatedAt, listOf(strangerPubkey), name = "Hijacked")
        every { encryption.decrypt("enc-creator", any()) } returns
            metaJson(memberPubkey, boundCreatedAt, listOf(memberPubkey, strangerPubkey))
        stubReplayRepo(listOf(snapshotOf(strangerMeta, boundGroupId), snapshotOf(creatorMeta, boundGroupId)))

        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)
        val count = importUseCase(buildExportJson(listOf(strangerMeta, creatorMeta), gid = boundGroupId))

        assertEquals(2, count)
        coVerify(exactly = 1) { groupRepo.updateCreator(boundGroupId, memberPubkey, boundCreatedAt) }
        coVerify(exactly = 0) { groupRepo.updateCreator(any(), strangerPubkey, any()) }
        // Stranger's meta was applied in restricted mode (name preserved, no createdBy) ...
        coVerify {
            groupRepo.updateFromMeta(
                boundGroupId,
                "Imported Group",
                any(),
                any(),
                boundCreatedAt + 10,
                "",
                any()
            )
        }
        // ... while the bound creator's meta was applied with full authority.
        coVerify {
            groupRepo.updateFromMeta(
                boundGroupId,
                "Trip",
                listOf(memberPubkey, strangerPubkey),
                any(),
                boundCreatedAt + 20,
                memberPubkey,
                any()
            )
        }
    }

    @Test
    fun `replayPostImport leaves createdBy empty when no group_meta is bound to the group id`() = runBlocking {
        // Same author as the id was derived from, but a created_at that does not reproduce the id.
        val wrongCreatedAt = buildSignedExportedEvent(
            privateKey = memberPrivKey,
            eventType = "group_meta",
            expenseUuid = null,
            contentEncrypted = "enc-wrong-ts",
            createdAt = boundCreatedAt + 10,
            gid = boundGroupId
        )
        // created_by names someone other than the signing author.
        val inconsistent = buildSignedExportedEvent(
            privateKey = memberPrivKey,
            eventType = "group_meta",
            expenseUuid = null,
            contentEncrypted = "enc-inconsistent",
            createdAt = boundCreatedAt + 20,
            gid = boundGroupId
        )
        every { encryption.decrypt("enc-wrong-ts", any()) } returns
            metaJson(memberPubkey, boundCreatedAt + 1, listOf(memberPubkey))
        every { encryption.decrypt("enc-inconsistent", any()) } returns
            metaJson(strangerPubkey, boundCreatedAt, listOf(memberPubkey))
        stubReplayRepo(listOf(snapshotOf(wrongCreatedAt, boundGroupId), snapshotOf(inconsistent, boundGroupId)))

        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)
        val count = importUseCase(buildExportJson(listOf(wrongCreatedAt, inconsistent), gid = boundGroupId))

        assertEquals(2, count)
        coVerify(exactly = 0) { groupRepo.updateCreator(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), not(""), any()) }
        verify { android.util.Log.w("ImportGroupUseCase", match<String> { it.contains("creator stays unknown") }) }
    }

    // --- ImportGroupUseCase: gift-wrapped rumors (seal-authenticated rows) ---

    private val sealMarker = EventSnapshot.SEAL_SIG_PREFIX + "ab".repeat(64)

    /**
     * An exported row as EventProcessor stores a received gift wrap: the rumor itself is unsigned
     * (`sig == ""`, id self-consistent) and the row's `sig` column holds the `seal:` marker.
     */
    private fun buildSealedRumorExportedEvent(
        privateKey: ByteArray = strangerPrivKey,
        eventType: String = "expense",
        expenseUuid: String? = "uuid1",
        contentEncrypted: String = "enc",
        createdAt: Long = 1700000000,
        gid: String = groupId,
        rowSig: String = sealMarker,
        mutateRumor: (NostrEvent) -> NostrEvent = { it }
    ): ExportedEvent {
        val pubkey = NostrEvent.pubkeyFromPrivkey(privateKey)
        val unsigned = NostrEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            kind = 30078,
            tags = buildList {
                add(listOf("g", gid))
                add(listOf("t", eventType))
                if (expenseUuid != null) add(listOf("x", expenseUuid))
            },
            content = contentEncrypted,
            sig = ""
        )
        val rumor = mutateRumor(unsigned.copy(id = unsigned.computeId().toHex()))
        return ExportedEvent(
            eventId = rumor.id,
            pubkey = rumor.pubkey,
            createdAt = rumor.createdAt,
            kind = rumor.kind,
            contentEncrypted = rumor.content,
            eventType = eventType,
            expenseUuid = expenseUuid,
            sig = rowSig,
            originalEventJson = rumor.toJson()
        )
    }

    private val strangerGroup = group.copy(members = listOf(memberPubkey, strangerPubkey))

    @Test
    fun `import accepts unsigned rumor whose exported row carries a seal marker`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns strangerGroup
        every { encryption.decrypt(any(), any()) } returns expenseJson(paidBy = strangerPubkey)
        val inserted = slot<EventSnapshot>()
        coEvery { eventRepo.insert(capture(inserted)) } just Runs

        val rumorRow = buildSealedRumorExportedEvent()
        val count =
            ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(
                buildExportJson(listOf(rumorRow))
            )

        assertEquals(1, count)
        assertEquals(strangerPubkey, inserted.captured.pubkey)
        // The marker survives the round trip so the restored row is still not forwarded as signed.
        assertEquals(sealMarker, inserted.captured.sig)
        assertFalse(EventSnapshot.isThirdPartyVerifiable(inserted.captured.sig))
        assertEquals(rumorRow.originalEventJson, inserted.captured.originalEventJson)
    }

    @Test
    fun `import rejects unsigned rumor without a seal marker`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns strangerGroup
        every { encryption.decrypt(any(), any()) } returns expenseJson(paidBy = strangerPubkey)

        val rows = listOf(
            buildSealedRumorExportedEvent(rowSig = ""),
            buildSealedRumorExportedEvent(rowSig = "sig1", contentEncrypted = "enc2"),
            // A marker on the row cannot resurrect a *signed* event whose signature is bad.
            buildSealedRumorExportedEvent(contentEncrypted = "enc3", mutateRumor = { it.copy(sig = "ff".repeat(64)) })
        )
        val count =
            ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(buildExportJson(rows))

        assertEquals(0, count)
        coVerify(exactly = 0) { eventRepo.insert(any<EventSnapshot>()) }
    }

    @Test
    fun `import rejects seal-marked rumor whose id is not self-consistent`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns strangerGroup
        every { encryption.decrypt(any(), any()) } returns expenseJson(paidBy = strangerPubkey)

        val forged = buildSealedRumorExportedEvent(mutateRumor = { it.copy(id = "f".repeat(64)) })
        val count =
            ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(
                buildExportJson(listOf(forged))
            )

        assertEquals(0, count)
        coVerify(exactly = 0) { eventRepo.insert(any<EventSnapshot>()) }
    }

    @Test
    fun `import still enforces membership for seal-marked rumors`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group // stranger is not a member
        every { encryption.decrypt(any(), any()) } returns expenseJson(paidBy = strangerPubkey)

        val count =
            ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(
                buildExportJson(listOf(buildSealedRumorExportedEvent()))
            )

        assertEquals(0, count)
    }

    @Test
    fun `import applies payload validation to decryptable expenses`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns strangerGroup
        // Payload id disagrees with the x tag. EventProcessor rejects this, so import must too.
        every { encryption.decrypt(any(), any()) } returns expenseJson(id = "other-uuid", paidBy = strangerPubkey)

        val count =
            ImportGroupUseCase(eventRepo, groupRepo, encryption, EventValidator(), identityMock)(
                buildExportJson(listOf(buildSealedRumorExportedEvent(expenseUuid = "uuid1")))
            )

        assertEquals(0, count)
        coVerify(exactly = 0) { eventRepo.insert(any<EventSnapshot>()) }
    }

    @Test
    fun `import rejects expense that fails validator`() = runBlocking {
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        every { encryption.decrypt(any(), any()) } returns expenseJson()
        every { eventValidator.isExpenseValid(any(), any()) } returns false

        val count =
            ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(
                buildExportJson(listOf(buildSignedExportedEvent()))
            )

        assertEquals(0, count)
        verify { eventValidator.isExpenseValid(any(), eq(group.members.toSet())) }
    }

    // --- ImportGroupUseCase: fresh-device restore reconstructs membership before filtering ---

    private val thirdPrivKey = ByteArray(32) { 3 }
    private val thirdPubkey = NostrEvent.pubkeyFromPrivkey(thirdPrivKey)

    /** In-memory stand-ins for Room so a multi-pass import can observe its own writes. */
    private class FakeStore {
        val events = mutableListOf<EventSnapshot>()
        var group: Group? = null
        val epochKeys = mutableMapOf<Int, String>()
    }

    private fun wireFakeStore(gid: String, store: FakeStore) {
        coEvery { eventRepo.insert(any<EventSnapshot>()) } answers { store.events += firstArg<EventSnapshot>() }
        coEvery { eventRepo.getEventIds(gid) } answers { store.events.map { it.eventId } }
        coEvery { eventRepo.getEventsByGroup(gid) } answers { store.events.toList() }
        coEvery { eventRepo.getExpenseByUuid(any(), gid) } answers {
            store.events.firstOrNull { it.eventType == "expense" && it.expenseUuid == firstArg<String>() }
        }
        coEvery { eventRepo.getExpenseByAuthor(any(), gid, any()) } answers {
            store.events.firstOrNull {
                it.eventType == "expense" && it.expenseUuid == firstArg<String>() && it.pubkey == thirdArg<String>()
            }
        }

        coEvery { groupRepo.getById(gid) } answers { store.group }
        coEvery { groupRepo.save(any(), any()) } answers {
            store.group = firstArg()
            store.epochKeys[firstArg<Group>().keyEpoch] = secondArg()
        }
        coEvery { groupRepo.getGroupKey(gid) } answers { store.group?.let { store.epochKeys[it.keyEpoch] } }
        coEvery { groupRepo.getGroupKeyForEpoch(gid, any()) } answers { store.epochKeys[secondArg()] }
        coEvery { groupRepo.saveGroupKeyForEpoch(gid, any(), any()) } answers {
            // Same immutability rule as GroupRepository: a second, different key for an epoch is refused.
            val existing = store.epochKeys[secondArg()]
            check(existing == null || existing == thirdArg<String>()) { "epoch ${secondArg<Int>()} already keyed" }
            store.epochKeys[secondArg()] = thirdArg()
        }
        coEvery { groupRepo.applyKeyRotation(gid, any(), any(), any(), any()) } answers {
            // Guarded like GroupDao.applyKeyRotation: only a strictly newer epoch lands.
            val current = store.group
            if (current == null || secondArg<Int>() <= current.keyEpoch) {
                false
            } else {
                store.group = current.copy(keyEpoch = secondArg(), members = thirdArg(), memberNames = arg(3))
                true
            }
        }
        coEvery { groupRepo.updateCreator(gid, any(), any()) } answers {
            store.group = store.group?.copy(createdBy = secondArg(), createdAt = thirdArg())
        }
        coEvery { groupRepo.updateFromMeta(gid, any(), any(), any(), any(), any(), any()) } answers {
            val createdBy = arg<String>(5)
            store.group = store.group?.copy(
                name = secondArg(),
                members = thirdArg(),
                relays = arg(3),
                createdBy = createdBy.ifEmpty { store.group!!.createdBy },
                memberNames = arg(6)
            )
            true
        }
    }

    private fun encryptKeyToSelf(key: String): String {
        val convKey = Nip44.getConversationKey(memberPrivKey, identityMock.getPublicKeyBytes())
        return Nip44.encrypt(key, convKey)
    }

    private fun buildFreshDeviceExport(
        events: List<ExportedEvent>,
        gid: String,
        groupName: String,
        relays: List<String> = listOf("wss://relay.test")
    ): String {
        val export = SplitFreeExport(
            version = 2,
            groupId = gid,
            exportedAt = 1700000100,
            events = events,
            encryptedGroupKey = encryptKeyToSelf(groupKey),
            groupName = groupName,
            relays = relays,
            keyEpoch = 0,
            encryptedEpochKeys = mapOf("0" to encryptKeyToSelf(groupKey))
        )
        return Json.encodeToString(SplitFreeExport.serializer(), signed(export))
    }

    @Test
    fun `fresh-device import restores events from every member by rebuilding membership first`() = runBlocking {
        // The stranger created the group; I (memberPubkey) and a third person joined later.
        val createdAt = 1_690_000_000L
        val gid = GroupIdentity.derive(strangerPubkey, createdAt)
        val store = FakeStore()
        wireFakeStore(gid, store)

        // Creator's group_meta arrived direct (signed). Its position in the file is AFTER the
        // expenses, which is exactly what defeats a single-pass importer.
        val creatorMeta = buildSignedExportedEvent(
            privateKey = strangerPrivKey,
            eventType = "group_meta",
            expenseUuid = null,
            contentEncrypted = "enc-meta",
            createdAt = createdAt + 5,
            gid = gid
        )
        // My own expense (signed by me).
        val mine = buildSignedExportedEvent(
            privateKey = memberPrivKey,
            expenseUuid = "u-me",
            contentEncrypted = "enc-me",
            createdAt = createdAt + 10,
            gid = gid
        )
        // Expenses from the two others arrived gift-wrapped: unsigned rumors, seal-marked rows.
        val strangers = buildSealedRumorExportedEvent(
            privateKey = strangerPrivKey,
            expenseUuid = "u-stranger",
            contentEncrypted = "enc-stranger",
            createdAt = createdAt + 20,
            gid = gid
        )
        val thirds = buildSealedRumorExportedEvent(
            privateKey = thirdPrivKey,
            expenseUuid = "u-third",
            contentEncrypted = "enc-third",
            createdAt = createdAt + 30,
            gid = gid
        )
        every { encryption.decrypt("enc-meta", any()) } returns
            metaJson(strangerPubkey, createdAt, listOf(strangerPubkey, memberPubkey, thirdPubkey), name = "Trip")
        every { encryption.decrypt("enc-me", any()) } returns expenseJson(id = "u-me", paidBy = memberPubkey)
        every { encryption.decrypt("enc-stranger", any()) } returns
            expenseJson(id = "u-stranger", paidBy = strangerPubkey)
        every { encryption.decrypt("enc-third", any()) } returns expenseJson(id = "u-third", paidBy = thirdPubkey)

        val useCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, EventValidator(), identityMock)
        val count = useCase(buildFreshDeviceExport(listOf(mine, strangers, thirds, creatorMeta), gid, groupName = ""))

        assertEquals(4, count)
        assertEquals(
            setOf(memberPubkey, strangerPubkey, thirdPubkey),
            store.events.filter { it.eventType == "expense" }.map { it.pubkey }.toSet()
        )
        // Membership and creator were reconstructed from the meta before the expenses were filtered.
        val restored = store.group!!
        assertEquals(setOf(strangerPubkey, memberPubkey, thirdPubkey), restored.members.toSet())
        assertEquals(strangerPubkey, restored.createdBy)
        assertEquals("Trip", restored.name)
        // Rumor rows keep their seal marker; my own event keeps its real signature.
        assertEquals(sealMarker, store.events.single { it.pubkey == strangerPubkey && it.eventType == "expense" }.sig)
        assertEquals(sealMarker, store.events.single { it.pubkey == thirdPubkey }.sig)
        assertTrue(EventSnapshot.isThirdPartyVerifiable(store.events.single { it.pubkey == memberPubkey }.sig))
    }

    @Test
    fun `fresh-device import runs both passes inside one transaction`() = runBlocking {
        val gid = GroupIdentity.derive(strangerPubkey, 1_690_000_000L)
        val store = FakeStore()
        wireFakeStore(gid, store)
        every { encryption.decrypt("enc-meta", any()) } returns
            metaJson(strangerPubkey, 1_690_000_000L, listOf(strangerPubkey, memberPubkey))
        every { encryption.decrypt("enc-me", any()) } returns expenseJson(id = "u-me", paidBy = memberPubkey)

        // Track the transaction boundary and assert every write (and the replay) happens inside it.
        var inTransaction = false
        var transactions = 0
        coEvery { eventRepo.withTransaction(captureLambda<suspend () -> Int>()) } coAnswers {
            transactions++
            inTransaction = true
            try {
                lambda<suspend () -> Int>().captured.invoke()
            } finally {
                inTransaction = false
            }
        }
        coEvery { eventRepo.insert(any<EventSnapshot>()) } answers {
            assertTrue("insert must run inside the import transaction", inTransaction)
            store.events += firstArg<EventSnapshot>()
        }
        coEvery { groupRepo.updateFromMeta(gid, any(), any(), any(), any(), any(), any()) } answers {
            assertTrue("membership replay must run inside the import transaction", inTransaction)
            store.group = store.group?.copy(name = secondArg(), members = thirdArg())
            true
        }

        val meta = buildSignedExportedEvent(
            privateKey = strangerPrivKey,
            eventType = "group_meta",
            expenseUuid = null,
            contentEncrypted = "enc-meta",
            createdAt = 1_690_000_005L,
            gid = gid
        )
        val mine = buildSignedExportedEvent(expenseUuid = "u-me", contentEncrypted = "enc-me", gid = gid)
        val count =
            ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(
                buildFreshDeviceExport(listOf(mine, meta), gid, groupName = "Trip")
            )

        assertEquals(2, count)
        assertEquals(1, transactions)
        assertEquals(2, store.events.size)
    }

    @Test
    fun `import with blank group name still creates the group as Imported group`() = runBlocking {
        val gid = GroupIdentity.derive(strangerPubkey, 1_690_000_000L)
        val store = FakeStore()
        wireFakeStore(gid, store)
        every { encryption.decrypt(any(), any()) } returns expenseJson(id = "u-me", paidBy = memberPubkey)

        val useCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)
        val count = useCase(
            buildFreshDeviceExport(
                listOf(buildSignedExportedEvent(expenseUuid = "u-me", gid = gid)),
                gid,
                groupName = "   "
            )
        )

        // A blank name must still create the group so the events are not orphaned.
        assertEquals(1, count)
        val created = store.group!!
        assertEquals("Imported group", created.name)
        assertEquals(listOf(memberPubkey), created.members)
        assertEquals(listOf("wss://relay.test"), created.relays)
        assertEquals("", created.createdBy)
        coVerify(exactly = 1) { groupRepo.save(match { it.id == gid && it.name == "Imported group" }, groupKey) }
    }

    @Test
    fun `import uses the exported group name when present`() = runBlocking {
        val gid = GroupIdentity.derive(strangerPubkey, 1_690_000_000L)
        val store = FakeStore()
        wireFakeStore(gid, store)

        ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(
            buildFreshDeviceExport(emptyList(), gid, groupName = "Ski weekend")
        )

        assertEquals("Ski weekend", store.group!!.name)
    }

    // --- ImportGroupUseCase: untrusted group metadata is sanitised on a fresh device ---

    @Test
    fun `import keeps only relays an invite link can carry and at most ten of them`() = runBlocking {
        val gid = GroupIdentity.derive(strangerPubkey, 1_690_000_000L)
        val store = FakeStore()
        wireFakeStore(gid, store)
        val relays = listOf(
            "ws://insecure.example",
            "wss://ok.example",
            "https://not-a-relay.example",
            "wss://" + "a".repeat(300),
            "wss://ok.example"
        ) + (1..12).map { "wss://relay$it.example" }

        newImport()(buildFreshDeviceExport(emptyList(), gid, groupName = "Trip", relays = relays))

        val kept = store.group!!.relays
        assertEquals(10, kept.size)
        assertEquals("wss://ok.example", kept.first())
        assertTrue(kept.all(InviteLinkCodec::relayFits))
        assertEquals(kept.size, kept.distinct().size)
    }

    @Test
    fun `import keeps a 254-byte relay and drops a 255-byte one`() = runBlocking {
        val gid = GroupIdentity.derive(strangerPubkey, 1_690_000_000L)
        val store = FakeStore()
        wireFakeStore(gid, store)
        val maximal = "wss://" + "a".repeat(240) + ".example"
        val oneByteTooLong = "wss://" + "a".repeat(241) + ".example"
        assertEquals(254, maximal.toByteArray(Charsets.UTF_8).size)
        assertEquals(255, oneByteTooLong.toByteArray(Charsets.UTF_8).size)

        newImport()(
            buildFreshDeviceExport(emptyList(), gid, groupName = "Trip", relays = listOf(oneByteTooLong, maximal))
        )

        assertEquals(listOf(maximal), store.group!!.relays)
    }

    @Test
    fun `import measures a relay in UTF-8 bytes so a short multi-byte URL over the budget is dropped`() = runBlocking {
        val gid = GroupIdentity.derive(strangerPubkey, 1_690_000_000L)
        val store = FakeStore()
        wireFakeStore(gid, store)
        // 200 chars but 6 + 194 x 2 = 394 UTF-8 bytes, well past the 254-byte bound an invite link enforces.
        val accented = "wss://" + "é".repeat(194)
        assertEquals(200, accented.length)
        assertEquals(394, accented.toByteArray(Charsets.UTF_8).size)

        newImport()(
            buildFreshDeviceExport(emptyList(), gid, groupName = "Trip", relays = listOf(accented, "wss://ok.example"))
        )

        assertEquals(listOf("wss://ok.example"), store.group!!.relays)
    }

    @Test
    fun `import caps eleven valid relays at ten`() = runBlocking {
        val gid = GroupIdentity.derive(strangerPubkey, 1_690_000_000L)
        val store = FakeStore()
        wireFakeStore(gid, store)
        val relays = (1..11).map { "wss://relay$it.example" }

        newImport()(buildFreshDeviceExport(emptyList(), gid, groupName = "Trip", relays = relays))

        assertEquals(relays.take(10), store.group!!.relays)
    }

    @Test
    fun `import truncates an oversized group name and strips control characters`() = runBlocking {
        val gid = GroupIdentity.derive(strangerPubkey, 1_690_000_000L)
        val store = FakeStore()
        wireFakeStore(gid, store)
        val hostile = "  \u202Etrip\u0000 " + "x".repeat(150)

        newImport()(buildFreshDeviceExport(emptyList(), gid, groupName = hostile))

        val name = store.group!!.name
        assertEquals(100, name.length)
        assertTrue(name.startsWith("trip"))
        assertFalse(name.any { it < ' ' || it == '\u202E' })
    }

    @Test
    fun `import falls back to the default name when only control characters remain`() = runBlocking {
        val gid = GroupIdentity.derive(strangerPubkey, 1_690_000_000L)
        val store = FakeStore()
        wireFakeStore(gid, store)

        newImport()(buildFreshDeviceExport(emptyList(), gid, groupName = "\u200E\u200F \u0007"))

        assertEquals("Imported group", store.group!!.name)
    }

    // --- ImportGroupUseCase: events of since-removed members survive a restore ---

    @Test
    fun `expense from a member removed before export is restored via historical group_meta`() = runBlocking {
        val createdAt = 1_690_000_000L
        val gid = GroupIdentity.derive(strangerPubkey, createdAt)
        val store = FakeStore()
        wireFakeStore(gid, store)

        // Creator's first meta admits the third member; the later one drops them again.
        val metaWithThird = buildSignedExportedEvent(
            privateKey = strangerPrivKey,
            eventType = "group_meta",
            expenseUuid = null,
            contentEncrypted = "enc-meta-1",
            createdAt = createdAt + 5,
            gid = gid
        )
        val metaWithoutThird = buildSignedExportedEvent(
            privateKey = strangerPrivKey,
            eventType = "group_meta",
            expenseUuid = null,
            contentEncrypted = "enc-meta-2",
            createdAt = createdAt + 50,
            gid = gid
        )
        // The third member paid for something while they were still in.
        val thirdsExpense = buildSealedRumorExportedEvent(
            privateKey = thirdPrivKey,
            expenseUuid = "u-third",
            contentEncrypted = "enc-third",
            createdAt = createdAt + 20,
            gid = gid
        )
        every { encryption.decrypt("enc-meta-1", any()) } returns
            metaJson(strangerPubkey, createdAt, listOf(strangerPubkey, memberPubkey, thirdPubkey))
        every { encryption.decrypt("enc-meta-2", any()) } returns
            metaJson(strangerPubkey, createdAt, listOf(strangerPubkey, memberPubkey))
        every { encryption.decrypt("enc-third", any()) } returns expenseJson(id = "u-third", paidBy = thirdPubkey)

        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, EventValidator(), identityMock)(
            buildFreshDeviceExport(listOf(thirdsExpense, metaWithoutThird, metaWithThird), gid, groupName = "")
        )

        assertEquals(3, count)
        // The final membership no longer contains the third member ...
        assertEquals(setOf(strangerPubkey, memberPubkey), store.group!!.members.toSet())
        // ... but their expense (accepted while they were a member) is still part of the history.
        assertEquals(1, store.events.count { it.eventType == "expense" && it.pubkey == thirdPubkey })
    }

    @Test
    fun `expense from a member removed by key_rotation is restored`() = runBlocking {
        val createdAt = 1_690_000_000L
        val gid = GroupIdentity.derive(strangerPubkey, createdAt)
        val store = FakeStore()
        wireFakeStore(gid, store)

        // Only the post-rotation meta is in the file; the rotation payload is the only record of the third member.
        val meta = buildSignedExportedEvent(
            privateKey = strangerPrivKey,
            eventType = "group_meta",
            expenseUuid = null,
            contentEncrypted = "enc-meta",
            createdAt = createdAt + 50,
            gid = gid
        )
        val rotation = buildSignedExportedEvent(
            privateKey = strangerPrivKey,
            eventType = "key_rotation",
            expenseUuid = null,
            contentEncrypted = "enc-rotation",
            createdAt = createdAt + 40,
            gid = gid
        )
        val thirdsExpense = buildSealedRumorExportedEvent(
            privateKey = thirdPrivKey,
            expenseUuid = "u-third",
            contentEncrypted = "enc-third",
            createdAt = createdAt + 20,
            gid = gid
        )
        every { encryption.decrypt("enc-meta", any()) } returns
            metaJson(strangerPubkey, createdAt, listOf(strangerPubkey, memberPubkey))
        every { encryption.decrypt("enc-rotation", any()) } returns
            """{"epoch":1,"encrypted_keys":{},"members":["$strangerPubkey","$memberPubkey"],""" +
            """"removed_member":"$thirdPubkey"}"""
        every { encryption.decrypt("enc-third", any()) } returns expenseJson(id = "u-third", paidBy = thirdPubkey)

        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, EventValidator(), identityMock)(
            buildFreshDeviceExport(listOf(thirdsExpense, meta, rotation), gid, groupName = "")
        )

        assertEquals(3, count)
        assertEquals(setOf(strangerPubkey, memberPubkey), store.group!!.members.toSet())
        assertEquals(1, store.events.count { it.eventType == "expense" && it.pubkey == thirdPubkey })
    }

    @Test
    fun `expense from a pubkey that was never a member is still dropped`() = runBlocking {
        val createdAt = 1_690_000_000L
        val gid = GroupIdentity.derive(strangerPubkey, createdAt)
        val store = FakeStore()
        wireFakeStore(gid, store)

        val meta = buildSignedExportedEvent(
            privateKey = strangerPrivKey,
            eventType = "group_meta",
            expenseUuid = null,
            contentEncrypted = "enc-meta",
            createdAt = createdAt + 5,
            gid = gid
        )
        val outsider = buildSealedRumorExportedEvent(
            privateKey = thirdPrivKey,
            expenseUuid = "u-third",
            contentEncrypted = "enc-third",
            createdAt = createdAt + 20,
            gid = gid
        )
        every { encryption.decrypt("enc-meta", any()) } returns
            metaJson(strangerPubkey, createdAt, listOf(strangerPubkey, memberPubkey))
        every { encryption.decrypt("enc-third", any()) } returns expenseJson(id = "u-third", paidBy = thirdPubkey)

        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, EventValidator(), identityMock)(
            buildFreshDeviceExport(listOf(outsider, meta), gid, groupName = "")
        )

        assertEquals(1, count)
        assertTrue(store.events.none { it.pubkey == thirdPubkey })
    }

    // --- ImportGroupUseCase: corrections and deletes resolve their original by (author, uuid) ---

    /** A stored group with two members and the epoch-0 key, wired to the fake store. */
    private fun storeWithTwoMembers(): FakeStore {
        val store = FakeStore().apply {
            group = this@ExportImportUseCaseTest.group.copy(members = listOf(memberPubkey, strangerPubkey))
            epochKeys[0] = groupKey
        }
        wireFakeStore(groupId, store)
        return store
    }

    /** The two members' originals under one shared uuid, followed by a [mutation] the stranger signs. */
    private fun sharedUuidRecords(mutation: String): List<ExportedEvent> = listOf(
        buildSignedExportedEvent(expenseUuid = "shared", contentEncrypted = "a-original", createdAt = 1700000100),
        buildSignedExportedEvent(
            privateKey = strangerPrivKey,
            expenseUuid = "shared",
            contentEncrypted = "b-original",
            createdAt = 1700000101
        ),
        buildSignedExportedEvent(
            privateKey = strangerPrivKey,
            eventType = mutation,
            expenseUuid = "shared",
            contentEncrypted = "b-$mutation",
            createdAt = 1700000102
        )
    )

    private fun stubSharedUuidPayloads() {
        every { encryption.decrypt("a-original", groupKey) } returns expenseJson("shared", memberPubkey, 100)
        every { encryption.decrypt("b-original", groupKey) } returns expenseJson("shared", strangerPubkey, 200)
        every { encryption.decrypt("b-expense_correction", groupKey) } returns
            expenseJson("shared", strangerPubkey, 300)
        every { encryption.decrypt("b-expense_delete", groupKey) } returns """{"id":"shared"}"""
    }

    @Test
    fun `restore keeps a second owner's correction sharing a uuid`() = runBlocking {
        val store = storeWithTwoMembers()
        stubSharedUuidPayloads()
        val records = sharedUuidRecords("expense_correction")

        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, EventValidator(), identityMock)(
            buildExportJson(records)
        )

        assertEquals(3, count)
        val correction = store.events.single { it.eventType == "expense_correction" }
        assertEquals(strangerPubkey, correction.pubkey)
        assertEquals(2, store.events.count { it.eventType == "expense" && it.expenseUuid == "shared" })
        // The original was resolved for the correction's own author, never by uuid alone.
        coVerify { eventRepo.getExpenseByAuthor("shared", groupId, strangerPubkey) }
        coVerify(exactly = 0) { eventRepo.getExpenseByUuid(any(), any()) }
    }

    @Test
    fun `restore keeps a second owner's delete sharing a uuid`() = runBlocking {
        val store = storeWithTwoMembers()
        stubSharedUuidPayloads()
        val records = sharedUuidRecords("expense_delete")

        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, EventValidator(), identityMock)(
            buildExportJson(records)
        )

        assertEquals(3, count)
        assertEquals(strangerPubkey, store.events.single { it.eventType == "expense_delete" }.pubkey)
        coVerify { eventRepo.getExpenseByAuthor("shared", groupId, strangerPubkey) }
    }

    @Test
    fun `restore stores an original before the correction that depends on it`() = runBlocking {
        val store = storeWithTwoMembers()
        // The correction is listed first and even carries the earlier timestamp.
        val correction = buildSignedExportedEvent(
            eventType = "expense_correction",
            expenseUuid = "u1",
            contentEncrypted = "enc-correction",
            createdAt = 1700000050
        )
        val original =
            buildSignedExportedEvent(expenseUuid = "u1", contentEncrypted = "enc-original", createdAt = 1700000060)
        every { encryption.decrypt("enc-original", groupKey) } returns expenseJson("u1", memberPubkey, 100)
        every { encryption.decrypt("enc-correction", groupKey) } returns expenseJson("u1", memberPubkey, 150)

        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, EventValidator(), identityMock)(
            buildExportJson(listOf(correction, original))
        )

        assertEquals(2, count)
        assertEquals(listOf("expense", "expense_correction"), store.events.map { it.eventType })
    }

    @Test
    fun `restore skips a correction whose original is neither in the backup nor stored`() = runBlocking {
        val store = storeWithTwoMembers()
        val correction = buildSignedExportedEvent(
            eventType = "expense_correction",
            expenseUuid = "orphan",
            contentEncrypted = "enc-orphan"
        )
        every { encryption.decrypt("enc-orphan", groupKey) } returns expenseJson("orphan", memberPubkey, 150)

        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, EventValidator(), identityMock)(
            buildExportJson(listOf(correction))
        )

        assertEquals(0, count)
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun `restore admits a correction whose original is already stored locally`() = runBlocking {
        val store = storeWithTwoMembers()
        val original = buildSignedExportedEvent(expenseUuid = "u1", contentEncrypted = "enc-original")
        store.events += snapshotOf(original, groupId)
        val correction = buildSignedExportedEvent(
            eventType = "expense_correction",
            expenseUuid = "u1",
            contentEncrypted = "enc-correction",
            createdAt = 1700000050
        )
        every { encryption.decrypt("enc-correction", groupKey) } returns expenseJson("u1", memberPubkey, 150)

        val count = ImportGroupUseCase(eventRepo, groupRepo, encryption, EventValidator(), identityMock)(
            buildExportJson(listOf(correction))
        )

        assertEquals(1, count)
        assertEquals(1, store.events.count { it.eventType == "expense_correction" })
    }

    // --- ImportGroupUseCase: epoch and key reconciliation on an existing group ---

    private val key1 = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val key2 = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })

    /** An authenticated backup of [groupId] at [keyEpoch] carrying [epochKeys] and, optionally, a current key. */
    private fun keyedBackup(
        keyEpoch: Int,
        epochKeys: Map<Int, String>,
        currentKey: String? = epochKeys[keyEpoch],
        events: List<ExportedEvent> = emptyList()
    ): SplitFreeExport = signed(
        SplitFreeExport(
            groupId = groupId,
            exportedAt = 1700000100,
            events = events,
            encryptedGroupKey = currentKey?.let(::encryptKeyToSelf) ?: "",
            keyEpoch = keyEpoch,
            encryptedEpochKeys = epochKeys.entries.associate { (epoch, key) ->
                epoch.toString() to encryptKeyToSelf(key)
            }
        )
    )

    /** The stored group at [keyEpoch] holding exactly [epochKeys], wired to the fake store. */
    private fun storeAtEpoch(keyEpoch: Int, epochKeys: Map<Int, String>): FakeStore {
        val store = FakeStore().apply {
            group = this@ExportImportUseCaseTest.group.copy(keyEpoch = keyEpoch)
            this.epochKeys += epochKeys
        }
        wireFakeStore(groupId, store)
        return store
    }

    @Test
    fun `a newer authenticated backup advances the current epoch with its keys`() = runBlocking {
        val store = storeAtEpoch(0, mapOf(0 to groupKey))
        every { encryption.decrypt("enc-e1", key1) } returns expenseJson("u1", memberPubkey, 100)
        val newer = buildSignedExportedEvent(expenseUuid = "u1", contentEncrypted = "enc-e1") { it.copy(keyEpoch = 1) }

        val count = newImport()(keyedBackup(1, mapOf(0 to groupKey, 1 to key1), events = listOf(newer)))

        assertEquals(1, count)
        assertEquals(mapOf(0 to groupKey, 1 to key1), store.epochKeys)
        assertEquals(1, store.group!!.keyEpoch)
        // The advance goes through the guarded rotation path and leaves the roster to the structural replay.
        coVerify(exactly = 1) { groupRepo.applyKeyRotation(groupId, 1, group.members, group.memberNames, null) }
        coVerify(exactly = 0) { groupRepo.updateKeyEpoch(any(), any()) }
        assertEquals(group.members, store.group!!.members)
        assertEquals(1, store.events.single().keyEpoch)
    }

    @Test
    fun `the backup's epoch key may come from its epoch map alone`() = runBlocking {
        val store = storeAtEpoch(0, mapOf(0 to groupKey))

        newImport()(keyedBackup(1, mapOf(0 to groupKey, 1 to key1), currentKey = null))

        assertEquals(key1, store.epochKeys[1])
        assertEquals(1, store.group!!.keyEpoch)
    }

    @Test
    fun `an older backup restores a missing historical key without downgrading the epoch`() = runBlocking {
        val store = storeAtEpoch(2, mapOf(2 to key2))

        val count = newImport()(keyedBackup(1, mapOf(0 to groupKey, 1 to key1)))

        assertEquals(0, count)
        assertEquals(mapOf(0 to groupKey, 1 to key1, 2 to key2), store.epochKeys)
        assertEquals(2, store.group!!.keyEpoch)
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.updateKeyEpoch(any(), any()) }
    }

    @Test
    fun `a backup at the group's own epoch leaves the epoch alone`() = runBlocking {
        val store = storeAtEpoch(1, mapOf(0 to groupKey, 1 to key1))

        newImport()(keyedBackup(1, mapOf(0 to groupKey, 1 to key1)))

        assertEquals(1, store.group!!.keyEpoch)
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a backup carrying a different key for a stored epoch fails closed before any write`() = runBlocking {
        val store = storeAtEpoch(0, mapOf(0 to groupKey))
        val backup = keyedBackup(
            1,
            mapOf(0 to key2, 1 to key1),
            events = listOf(buildSignedExportedEvent(expenseUuid = "u1", contentEncrypted = "enc-e1"))
        )

        val e = assertThrows(IllegalArgumentException::class.java) { runBlocking { newImport()(backup) } }

        assertTrue(e.message!!.contains("different key for epoch 0"))
        assertEquals(mapOf(0 to groupKey), store.epochKeys)
        assertEquals(0, store.group!!.keyEpoch)
        assertTrue(store.events.isEmpty())
        coVerify(exactly = 0) { groupRepo.saveGroupKeyForEpoch(any(), any(), any()) }
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { eventRepo.insert(any<EventSnapshot>()) }
    }

    @Test
    fun `a newer backup without the key for its epoch never reuses an older key`() = runBlocking {
        val store = storeAtEpoch(0, mapOf(0 to groupKey))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { newImport()(keyedBackup(1, mapOf(0 to groupKey), currentKey = null)) }
        }

        assertEquals(mapOf(0 to groupKey), store.epochKeys)
        assertEquals(0, store.group!!.keyEpoch)
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a backup whose current key disagrees with its own epoch key is refused untouched`() {
        val store = storeAtEpoch(0, mapOf(0 to groupKey))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { newImport()(keyedBackup(1, mapOf(0 to groupKey, 1 to key1), currentKey = key2)) }
        }

        assertEquals(mapOf(0 to groupKey), store.epochKeys)
        assertEquals(0, store.group!!.keyEpoch)
        coVerify(exactly = 0) { eventRepo.withTransaction(any<suspend () -> Int>()) }
    }

    @Test
    fun `malformed epoch labels and key material are refused before the transaction`() {
        val store = storeAtEpoch(0, mapOf(0 to groupKey))
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(31))
        val malformed = listOf("-1", "2", "01", "epoch").map { label ->
            signed(
                SplitFreeExport(
                    groupId = groupId,
                    exportedAt = 1700000100,
                    events = emptyList(),
                    keyEpoch = 1,
                    encryptedEpochKeys = mapOf(label to encryptKeyToSelf(key1))
                )
            )
        } + keyedBackup(0, mapOf(0 to shortKey)) +
            signed(SplitFreeExport(groupId = groupId, exportedAt = 1700000100, events = emptyList(), keyEpoch = -1)) +
            keyedBackup(0, emptyMap(), currentKey = null).let {
                signed(it.copy(encryptedEpochKeys = mapOf("0" to "not-a-nip44-payload")))
            }

        for (backup in malformed) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { newImport()(backup) } }
        }

        assertEquals(mapOf(0 to groupKey), store.epochKeys)
        assertEquals(0, store.group!!.keyEpoch)
        coVerify(exactly = 0) { eventRepo.withTransaction(any<suspend () -> Int>()) }
    }

    @Test
    fun `a fresh device restores a newer backup at the backup's epoch`() = runBlocking {
        val gid = GroupIdentity.derive(strangerPubkey, 1_690_000_000L)
        val store = FakeStore()
        wireFakeStore(gid, store)
        val backup = signed(
            SplitFreeExport(
                groupId = gid,
                exportedAt = 1700000100,
                events = emptyList(),
                encryptedGroupKey = encryptKeyToSelf(key1),
                groupName = "Trip",
                keyEpoch = 1,
                encryptedEpochKeys = mapOf("0" to encryptKeyToSelf(groupKey), "1" to encryptKeyToSelf(key1))
            )
        )

        newImport()(backup)

        assertEquals(1, store.group!!.keyEpoch)
        assertEquals(mapOf(0 to groupKey, 1 to key1), store.epochKeys)
        coVerify(exactly = 1) { groupRepo.save(match { it.id == gid && it.keyEpoch == 1 }, key1) }
        coVerify(exactly = 0) { groupRepo.applyKeyRotation(any(), any(), any(), any(), any()) }
    }
}
