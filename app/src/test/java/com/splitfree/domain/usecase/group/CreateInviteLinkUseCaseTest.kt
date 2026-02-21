package com.splitfree.domain.usecase.group

import com.splitfree.domain.crypto.NostrEvent
import com.splitfree.domain.model.group.Group
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateInviteLinkUseCaseTest {
    private val groupRepo = mockk<GroupRepositoryContract>()
    private val identity = mockk<IdentityContract>()
    private val useCase = CreateInviteLinkUseCase(groupRepo, identity)

    private fun validPrivateKey(): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        while (!fr.acinq.secp256k1.Secp256k1.secKeyVerify(key)) {
            SecureRandom().nextBytes(key)
        }
        return key
    }

    @Test
    fun `creator can generate invite link`() = runBlocking {
        val privKey = validPrivateKey()
        val pubKey = NostrEvent.pubkeyFromPrivkey(privKey)
        val group =
            Group("550e8400-e29b-41d4-a716-446655440000", "Trip", "", pubKey, 1000, listOf(pubKey), listOf("wss://r"))
        coEvery { groupRepo.getById(group.id) } returns group
        coEvery { groupRepo.getGroupKey(group.id) } returns "group-key"
        every { identity.getPublicKeyHex() } returns pubKey
        every { identity.getPrivateKeyBytes() } returns privKey.copyOf()

        val link = useCase(group.id)
        assertTrue(link.startsWith("splitfree://join?d="))
    }

    @Test(expected = IllegalStateException::class)
    fun `non creator cannot generate invite link`() = runBlocking {
        val group =
            Group(
                "550e8400-e29b-41d4-a716-446655440000",
                "Trip",
                "",
                "creator",
                1000,
                listOf("creator"),
                listOf("wss://r")
            )
        coEvery { groupRepo.getById(group.id) } returns group
        every { identity.getPublicKeyHex() } returns "member"

        useCase(group.id)
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun `cannot generate invite when creator is unknown`() = runBlocking {
        val group =
            Group("550e8400-e29b-41d4-a716-446655440000", "Trip", "", "", 1000, listOf("member"), listOf("wss://r"))
        coEvery { groupRepo.getById(group.id) } returns group
        every { identity.getPublicKeyHex() } returns "member"

        useCase(group.id)
        Unit
    }
}
