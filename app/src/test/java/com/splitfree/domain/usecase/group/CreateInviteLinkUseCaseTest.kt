package com.splitfree.domain.usecase.group

import com.splitfree.domain.invite.InviteLinkCodec
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.model.group.GroupIdentity
import com.splitfree.domain.repository.GroupRepositoryContract
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CreateInviteLinkUseCaseTest {
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val useCase = CreateInviteLinkUseCase(groupRepo)

    private val creator = "cc".repeat(32)
    private val member = "dd".repeat(32)
    private val createdAt = 1_700_000_000L
    private val groupId = GroupIdentity.derive(creator, createdAt)
    private val epoch0Key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val epoch2Key = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })

    private fun group(keyEpoch: Int = 0, createdBy: String = creator) = Group(
        groupId,
        "Trip",
        "",
        createdBy,
        createdAt,
        listOf(creator, member),
        listOf("wss://r"),
        keyEpoch = keyEpoch
    )

    @Test
    fun `any member can generate invite link naming the real creator`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group()
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns epoch0Key

        val link = useCase(groupId)

        assertTrue(link.startsWith("splitfree://join?d="))
        val invite = InviteLinkCodec.decode(link)
        assertEquals(creator, invite.creatorPubkey)
        assertEquals(createdAt, invite.createdAt)
        assertEquals(groupId, invite.groupId)
        assertEquals(epoch0Key, invite.groupKey)
    }

    @Test
    fun `uses the key for the group's current epoch`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group(keyEpoch = 2)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 2) } returns epoch2Key

        val invite = InviteLinkCodec.decode(useCase(groupId))

        assertEquals(2, invite.keyEpoch)
        assertEquals(epoch2Key, invite.groupKey)
        coVerify(exactly = 0) { groupRepo.getGroupKey(any()) }
        coVerify(exactly = 0) { groupRepo.getGroupKeyForEpoch(groupId, 0) }
    }

    @Test
    fun `throws when group has unknown creator`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group(createdBy = "")

        try {
            useCase(groupId)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Cannot invite to a group with unknown creator", e.message)
        }
        coVerify(exactly = 0) { groupRepo.getGroupKeyForEpoch(any(), any()) }
    }

    @Test
    fun `observed snapshot does not mix in a newer repository epoch or endpoints`() = runBlocking {
        val observed = group()
        coEvery { groupRepo.getById(groupId) } returns group(keyEpoch = 2).copy(relays = listOf("wss://new.test"))
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 0) } returns epoch0Key

        val invite = InviteLinkCodec.decode(useCase(observed))

        assertEquals(0, invite.keyEpoch)
        assertEquals(epoch0Key, invite.groupKey)
        assertEquals(observed.relays, invite.relays)
        coVerify(exactly = 0) { groupRepo.getById(any()) }
    }

    @Test(expected = IllegalStateException::class)
    fun `throws when group not found`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns null
        useCase(groupId)
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `throws when current epoch key is missing`() = runBlocking {
        coEvery { groupRepo.getById(groupId) } returns group(keyEpoch = 1)
        coEvery { groupRepo.getGroupKeyForEpoch(groupId, 1) } returns null
        useCase(groupId)
        Unit
    }
}
