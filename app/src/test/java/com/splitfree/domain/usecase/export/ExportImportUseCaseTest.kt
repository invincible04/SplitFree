package com.splitfree.domain.usecase.export

import com.splitfree.domain.crypto.GroupEncryption
import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.crypto.nip.Nip44
import com.splitfree.domain.model.export.ExportedEvent
import com.splitfree.domain.model.export.SplitFreeExport
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.repository.EventRepositoryContract
import com.splitfree.domain.repository.EventSnapshot
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
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
    fun `export produces valid JSON with HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { groupRepo.getById(groupId) } returns group
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventsByGroup(groupId) } returns listOf(sampleEntity)
        val identity = mockk<IdentityContract>()
        every { identity.getPrivateKeyBytes() } returns memberPrivKey.copyOf()
        every { identity.getPublicKeyBytes() } returns memberPubkey.let { hex ->
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
        val useCase = ExportGroupUseCase(eventRepo, groupRepo, identity)

        val result = useCase(groupId)
        val export = json.decodeFromString<SplitFreeExport>(result)

        assertEquals(1, export.version)
        assertEquals(groupId, export.groupId)
        assertEquals(1, export.events.size)
        assertEquals("evt1", export.events[0].eventId)
        assertTrue(export.hmac.isNotEmpty())
        assertTrue(export.encryptedGroupKey.isNotEmpty())
        assertEquals("Test", export.groupName)
        assertEquals(listOf("wss://relay.test"), export.relays)
    }

    @Test
    fun `export with no group key produces empty HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        coEvery { groupRepo.getById(groupId) } returns null
        coEvery { eventRepo.getEventsByGroup(groupId) } returns listOf(sampleEntity)
        val identity = mockk<IdentityContract>()
        val useCase = ExportGroupUseCase(eventRepo, groupRepo, identity)

        val result = useCase(groupId)
        val export = json.decodeFromString<SplitFreeExport>(result)
        assertEquals("", export.hmac)
        assertEquals("", export.encryptedGroupKey)
    }

    @Test
    fun `export with empty events`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { groupRepo.getById(groupId) } returns group
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns groupKey
        coEvery { eventRepo.getEventsByGroup(groupId) } returns emptyList()
        val identity = mockk<IdentityContract>()
        every { identity.getPrivateKeyBytes() } returns memberPrivKey.copyOf()
        every { identity.getPublicKeyBytes() } returns memberPubkey.let { hex ->
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
        val useCase = ExportGroupUseCase(eventRepo, groupRepo, identity)

        val result = useCase(groupId)
        val export = json.decodeFromString<SplitFreeExport>(result)
        assertTrue(export.events.isEmpty())
    }

    // --- ImportGroupUseCase ---

    private fun computeHmac(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(
            SecretKeySpec(
                Base64
                    .getDecoder()
                    .decode(key),
                "HmacSHA256"
            )
        )
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun buildExportJson(events: List<ExportedEvent>, hmac: String = "", gid: String = groupId): String {
        val serializer = kotlinx.serialization.builtins
            .ListSerializer(ExportedEvent.serializer())
        val eventsJson = Json.encodeToString(serializer, events)
        val h = if (hmac.isEmpty()) computeHmac(eventsJson, groupKey) else hmac
        val export = SplitFreeExport(version = 1, groupId = gid, exportedAt = 1700000000, events = events, hmac = h)
        return Json.encodeToString(SplitFreeExport.serializer(), export)
    }

    private val identityMock = mockk<IdentityContract>().also {
        every { it.getPublicKeyHex() } returns memberPubkey
        // Callers zero the key after use, so hand out a fresh copy on every call like IdentityManager does.
        every { it.getPrivateKeyBytes() } answers { memberPrivKey.copyOf() }
        every { it.getPublicKeyBytes() } returns memberPubkey.let { hex ->
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }

    @Test
    fun `import stores new events and returns count`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group

        val events = listOf(buildSignedExportedEvent(privateKey = strangerPrivKey))
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)
        val count = importUseCase(buildExportJson(events))
        assertEquals(0, count)
    }

    @Test
    fun `import allows group_meta from non-members`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects unsupported version`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        val badJson = """{"version":2,"groupId":"$groupId","exportedAt":0,"events":[],"hmac":"abc"}"""
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)
        importUseCase(badJson)
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `import rejects unknown group`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns null
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)
        importUseCase(buildExportJson(emptyList()))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects missing HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        val noHmac = """{"version":1,"groupId":"$groupId","exportedAt":0,"events":[],"hmac":""}"""
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)
        importUseCase(noHmac)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects tampered HMAC`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        val events = listOf(ExportedEvent("evt1", "pub1", 1700000000, 30078, "enc", "expense", "uuid1", "sig1"))
        val serializer = kotlinx.serialization.builtins
            .ListSerializer(ExportedEvent.serializer())
        val eventsJson = Json.encodeToString(serializer, events)
        val badHmac = "ff".repeat(32)
        val export = SplitFreeExport(groupId = groupId, exportedAt = 0, events = events, hmac = badHmac)
        val importUseCase = ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)
        importUseCase(Json.encodeToString(SplitFreeExport.serializer(), export))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import rejects invalid HMAC hex`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        val badJson = """{"version":1,"groupId":"$groupId","exportedAt":0,"events":[],"hmac":"xyz"}"""
        ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(badJson)
        Unit
    }

    @Test
    fun `import skips event with invalid original JSON signature`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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
    fun `import skips correction from wrong author`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
        coEvery { eventRepo.getEventIds(groupId) } returns emptyList()
        coEvery { groupRepo.getById(groupId) } returns group
        coEvery { eventRepo.getExpenseByUuid("uuid1", groupId) } returns sampleEntity.copy(pubkey = "other-pub")
        every { eventValidator.isWithinRateLimit(any()) } returns true
        every {
            eventValidator.isCorrectionAuthorValid(
                "expense_correction",
                memberPubkey,
                "other-pub"
            )
        } returns false

        val events = listOf(
            buildSignedExportedEvent(eventType = "expense_correction", expenseUuid = "uuid1")
        )
        val count =
            ImportGroupUseCase(eventRepo, groupRepo, encryption, eventValidator, identityMock)(buildExportJson(events))
        assertEquals(0, count)
    }

    @Test
    fun `import handles decryption failure gracefully`() = runBlocking {
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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

    /** A legacy import: group id derived from [memberPubkey], but the local row has no creator yet. */
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
        coEvery { groupRepo.getGroupKey(boundGroupId) } returns groupKey
        coEvery { groupRepo.getGroupKeyForEpoch(boundGroupId, any()) } returns groupKey
        coEvery { groupRepo.getById(boundGroupId) } returns legacyGroup
        coEvery { groupRepo.updateCreator(any(), any(), any()) } just Runs
        coEvery { groupRepo.updateFromMeta(any(), any(), any(), any(), any(), any(), any()) } just Runs
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
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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
        coEvery { groupRepo.getGroupKey(groupId) } returns groupKey
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

        coEvery { groupRepo.getById(gid) } answers { store.group }
        coEvery { groupRepo.save(any(), any()) } answers {
            store.group = firstArg()
            store.epochKeys[firstArg<Group>().keyEpoch] = secondArg()
        }
        coEvery { groupRepo.getGroupKey(gid) } answers { store.group?.let { store.epochKeys[it.keyEpoch] } }
        coEvery { groupRepo.getGroupKeyForEpoch(gid, any()) } answers { store.epochKeys[secondArg()] }
        coEvery { groupRepo.saveGroupKeyForEpoch(gid, any(), any()) } answers {
            store.epochKeys[secondArg()] = thirdArg()
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
        }
    }

    private fun encryptKeyToSelf(key: String): String {
        val convKey = Nip44.getConversationKey(memberPrivKey, identityMock.getPublicKeyBytes())
        return Nip44.encrypt(key, convKey)
    }

    private fun buildFreshDeviceExport(events: List<ExportedEvent>, gid: String, groupName: String): String {
        val serializer = kotlinx.serialization.builtins.ListSerializer(ExportedEvent.serializer())
        val eventsJson = Json.encodeToString(serializer, events)
        val export = SplitFreeExport(
            version = 1,
            groupId = gid,
            exportedAt = 1700000100,
            events = events,
            hmac = computeHmac(eventsJson, groupKey),
            encryptedGroupKey = encryptKeyToSelf(groupKey),
            groupName = groupName,
            relays = listOf("wss://relay.test"),
            keyEpoch = 0,
            encryptedEpochKeys = mapOf("0" to encryptKeyToSelf(groupKey))
        )
        return Json.encodeToString(SplitFreeExport.serializer(), export)
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
}
