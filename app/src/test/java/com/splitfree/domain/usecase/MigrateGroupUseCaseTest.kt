package com.splitfree.domain.usecase

import com.splitfree.data.local.EventDao
import com.splitfree.data.local.OutboxDao
import com.splitfree.data.nostr.EventThrottler
import com.splitfree.data.repository.GroupRepository
import com.splitfree.domain.crypto.*
import com.splitfree.domain.model.Group
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MigrateGroupUseCaseTest {
    private val groupRepo = mockk<GroupRepository>(relaxed = true)
    private val encryption = mockk<GroupEncryption>()
    private val identity = mockk<IdentityManager>()
    private val signer = mockk<EventSigner>()
    private val eventDao = mockk<EventDao>(relaxed = true)
    private val outboxDao = mockk<OutboxDao>(relaxed = true)
    private val throttler = mockk<EventThrottler>(relaxed = true)

    private lateinit var useCase: MigrateGroupUseCase

    private val myPubkey = "aa".repeat(32)
    private val memberPubkey = "bb".repeat(32)
    private val removePubkey = "cc".repeat(32)
    private val oldGroupId = "old-group"
    private val groupKey = "key123"

    private val oldGroup =
        Group(
            id = oldGroupId,
            name = "Trip",
            createdBy = myPubkey,
            createdAt = 1000,
            members = listOf(myPubkey, memberPubkey, removePubkey),
            relays = listOf("wss://relay.test"),
        )

    private val fakeEvent =
        NostrEvent(
            id = "evt1",
            pubkey = myPubkey,
            createdAt = 1000,
            kind = 30078,
            tags = emptyList(),
            content = "enc",
            sig = "sig",
        )

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0

        mockkObject(Nip44)
        every { Nip44.getConversationKey(any(), any()) } returns ByteArray(32)
        every { Nip44.encrypt(any<String>(), any()) } returns "nip44encrypted"
        every { Nip44.decrypt(any<String>(), any()) } returns "decryptedKey"

        every { identity.getPublicKeyHex() } returns myPubkey
        every { identity.getPrivateKeyBytes() } returns ByteArray(32) { 1 }
        every { encryption.generateGroupKey() } returns "newKey"
        every { encryption.encrypt(any(), any()) } returns "encrypted"
        every { signer.createSignedEvent(any(), any(), any(), any()) } returns fakeEvent
        coEvery { groupRepo.getById(oldGroupId) } returns oldGroup
        coEvery { groupRepo.getGroupKey(oldGroupId) } returns groupKey

        useCase = MigrateGroupUseCase(groupRepo, encryption, identity, signer, eventDao, outboxDao, throttler)
    }

    @After
    fun teardown() {
        unmockkObject(Nip44)
        unmockkStatic(android.util.Log::class)
    }

    // --- invoke ---

    @Test
    fun `invoke creates new group without removed member`() =
        runBlocking {
            val newGroup = useCase(oldGroupId, removePubkey)
            assertEquals(listOf(myPubkey, memberPubkey), newGroup.members)
            assertEquals("Trip", newGroup.name)
            assertNotEquals(oldGroupId, newGroup.id)
        }

    @Test
    fun `invoke publishes migrate and meta events`() =
        runBlocking {
            useCase(oldGroupId, removePubkey)
            verify(exactly = 2) { signer.createSignedEvent(any(), any(), any(), any()) }
            coVerify(atLeast = 2) { outboxDao.insert(any()) }
            coVerify(atLeast = 2) { throttler.enqueue(any()) }
        }

    @Test
    fun `invoke deletes old group key`() =
        runBlocking {
            useCase(oldGroupId, removePubkey)
            verify { groupRepo.deleteGroupKey(oldGroupId) }
        }

    @Test
    fun `invoke saves new group`() =
        runBlocking {
            useCase(oldGroupId, removePubkey)
            coVerify { groupRepo.save(match { it.id != oldGroupId }, "newKey") }
        }

    @Test(expected = IllegalStateException::class)
    fun `invoke fails if group not found`() =
        runBlocking {
            coEvery { groupRepo.getById(oldGroupId) } returns null
            useCase(oldGroupId, removePubkey)
            Unit
        }

    @Test(expected = IllegalStateException::class)
    fun `invoke fails if caller is not creator`() =
        runBlocking {
            coEvery { groupRepo.getById(oldGroupId) } returns oldGroup.copy(createdBy = "someone-else")
            useCase(oldGroupId, removePubkey)
            Unit
        }

    @Test(expected = IllegalStateException::class)
    fun `invoke fails if member not in group`() =
        runBlocking {
            useCase(oldGroupId, "dd".repeat(32))
            Unit
        }

    @Test(expected = IllegalStateException::class)
    fun `invoke fails if trying to remove self`() =
        runBlocking {
            useCase(oldGroupId, myPubkey)
            Unit
        }

    // --- handleMigration ---

    @Test
    fun `handleMigration joins new group`() =
        runBlocking {
            val newId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            every { identity.getPublicKeyHex() } returns memberPubkey
            coEvery { groupRepo.getById(newId) } returns null
            coEvery { groupRepo.getById(oldGroupId) } returns oldGroup

            val migration =
                GroupMigration(
                    newGroupId = newId,
                    encryptedKeys = mapOf(memberPubkey to "encKey"),
                    members = listOf(myPubkey, memberPubkey),
                    removedMember = removePubkey,
                )
            useCase.handleMigration(Json.encodeToString(GroupMigration.serializer(), migration), myPubkey, oldGroupId)
            coVerify { groupRepo.save(match { it.id == newId }, any()) }
        }

    @Test
    fun `handleMigration ignores if I was removed`() =
        runBlocking {
            every { identity.getPublicKeyHex() } returns removePubkey
            val migration =
                GroupMigration(
                    newGroupId = "new-id",
                    encryptedKeys = emptyMap(),
                    members = listOf(myPubkey, memberPubkey),
                    removedMember = removePubkey,
                )
            useCase.handleMigration(Json.encodeToString(GroupMigration.serializer(), migration), myPubkey, oldGroupId)
            coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        }

    @Test
    fun `handleMigration ignores if new group already exists`() =
        runBlocking {
            every { identity.getPublicKeyHex() } returns memberPubkey
            coEvery { groupRepo.getById("new-id") } returns Group("new-id", "G", "", myPubkey, 1000, listOf(myPubkey), listOf("wss://r"))
            val migration =
                GroupMigration(
                    newGroupId = "new-id",
                    encryptedKeys = mapOf(memberPubkey to "k"),
                    members = listOf(myPubkey, memberPubkey),
                    removedMember = removePubkey,
                )
            useCase.handleMigration(Json.encodeToString(GroupMigration.serializer(), migration), myPubkey, oldGroupId)
            coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        }

    @Test
    fun `handleMigration rejects from non-creator`() =
        runBlocking {
            every { identity.getPublicKeyHex() } returns memberPubkey
            coEvery { groupRepo.getById("new-id") } returns null
            coEvery { groupRepo.getById(oldGroupId) } returns oldGroup
            val migration =
                GroupMigration(
                    newGroupId = "new-id",
                    encryptedKeys = mapOf(memberPubkey to "k"),
                    members = listOf(myPubkey, memberPubkey),
                    removedMember = removePubkey,
                )
            useCase.handleMigration(Json.encodeToString(GroupMigration.serializer(), migration), "stranger", oldGroupId)
            coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        }

    @Test
    fun `handleMigration rejects invalid JSON`() =
        runBlocking {
            every { identity.getPublicKeyHex() } returns memberPubkey
            useCase.handleMigration("not json", myPubkey, oldGroupId)
            coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        }

    @Test
    fun `handleMigration rejects invalid newGroupId format`() =
        runBlocking {
            every { identity.getPublicKeyHex() } returns memberPubkey
            coEvery { groupRepo.getById("not-a-uuid") } returns null
            coEvery { groupRepo.getById(oldGroupId) } returns oldGroup
            val migration =
                GroupMigration(
                    newGroupId = "not-a-uuid",
                    encryptedKeys = mapOf(memberPubkey to "k"),
                    members = listOf(myPubkey, memberPubkey),
                    removedMember = removePubkey,
                )
            useCase.handleMigration(Json.encodeToString(GroupMigration.serializer(), migration), myPubkey, oldGroupId)
            coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        }

    @Test(expected = IllegalStateException::class)
    fun `invoke fails if old group key not found`() =
        runBlocking {
            coEvery { groupRepo.getGroupKey(oldGroupId) } returns null
            useCase(oldGroupId, removePubkey)
            Unit
        }

    @Test
    fun `handleMigration rejects members not in original group`() =
        runBlocking {
            val newId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            every { identity.getPublicKeyHex() } returns memberPubkey
            coEvery { groupRepo.getById(newId) } returns null
            coEvery { groupRepo.getById(oldGroupId) } returns oldGroup
            val migration =
                GroupMigration(
                    newGroupId = newId,
                    encryptedKeys = mapOf(memberPubkey to "k"),
                    members = listOf(myPubkey, memberPubkey, "dd".repeat(32)), // dd not in old group
                    removedMember = removePubkey,
                )
            useCase.handleMigration(Json.encodeToString(GroupMigration.serializer(), migration), myPubkey, oldGroupId)
            coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        }

    @Test
    fun `handleMigration rejects removing non-member`() =
        runBlocking {
            val newId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            every { identity.getPublicKeyHex() } returns memberPubkey
            coEvery { groupRepo.getById(newId) } returns null
            coEvery { groupRepo.getById(oldGroupId) } returns oldGroup
            val migration =
                GroupMigration(
                    newGroupId = newId,
                    encryptedKeys = mapOf(memberPubkey to "k"),
                    members = listOf(myPubkey, memberPubkey),
                    removedMember = "dd".repeat(32), // not in old group
                )
            useCase.handleMigration(Json.encodeToString(GroupMigration.serializer(), migration), myPubkey, oldGroupId)
            coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        }

    @Test
    fun `handleMigration rejects when no encrypted key for me`() =
        runBlocking {
            val newId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            every { identity.getPublicKeyHex() } returns memberPubkey
            coEvery { groupRepo.getById(newId) } returns null
            coEvery { groupRepo.getById(oldGroupId) } returns oldGroup
            val migration =
                GroupMigration(
                    newGroupId = newId,
                    encryptedKeys = mapOf(myPubkey to "k"), // no key for memberPubkey
                    members = listOf(myPubkey, memberPubkey),
                    removedMember = removePubkey,
                )
            useCase.handleMigration(Json.encodeToString(GroupMigration.serializer(), migration), myPubkey, oldGroupId)
            coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        }

    @Test
    fun `handleMigration handles decrypt failure`() =
        runBlocking {
            val newId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            every { identity.getPublicKeyHex() } returns memberPubkey
            coEvery { groupRepo.getById(newId) } returns null
            coEvery { groupRepo.getById(oldGroupId) } returns oldGroup
            every { Nip44.decrypt(any<String>(), any()) } throws RuntimeException("decrypt fail")
            val migration =
                GroupMigration(
                    newGroupId = newId,
                    encryptedKeys = mapOf(memberPubkey to "encKey"),
                    members = listOf(myPubkey, memberPubkey),
                    removedMember = removePubkey,
                )
            useCase.handleMigration(Json.encodeToString(GroupMigration.serializer(), migration), myPubkey, oldGroupId)
            coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        }

    @Test
    fun `handleMigration rejects when old group not found`() =
        runBlocking {
            val newId =
                java.util.UUID
                    .randomUUID()
                    .toString()
            every { identity.getPublicKeyHex() } returns memberPubkey
            coEvery { groupRepo.getById(newId) } returns null
            coEvery { groupRepo.getById(oldGroupId) } returns null
            val migration =
                GroupMigration(
                    newGroupId = newId,
                    encryptedKeys = mapOf(memberPubkey to "k"),
                    members = listOf(myPubkey, memberPubkey),
                    removedMember = removePubkey,
                )
            useCase.handleMigration(Json.encodeToString(GroupMigration.serializer(), migration), myPubkey, oldGroupId)
            coVerify(exactly = 0) { groupRepo.save(any(), any()) }
        }
}
