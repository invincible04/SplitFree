package com.splitfree.domain.usecase.group

import com.splitfree.di.ApplicationScope
import com.splitfree.domain.repository.ControlOperationJournalContract
import com.splitfree.domain.repository.DisplayNameIntent
import com.splitfree.domain.repository.DisplayNamePublishResult
import com.splitfree.domain.repository.GroupRepositoryContract
import com.splitfree.domain.repository.IdentityContract
import com.splitfree.domain.repository.SettingsContract
import com.splitfree.sync.worker.DisplayNameScheduler
import com.splitfree.util.DebugLog as Log
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(FlowPreview::class)
@Singleton
class DisplayNamePublisher @Inject constructor(
    private val identity: IdentityContract,
    private val settings: SettingsContract,
    private val updateDisplayName: UpdateDisplayNameUseCase,
    private val groups: GroupRepositoryContract,
    private val scheduler: DisplayNameScheduler,
    private val controlLock: ControlOperationLock,
    private val journal: ControlOperationJournalContract,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val started = AtomicBoolean()
    private val wakes = Channel<Unit>(Channel.CONFLATED)
    private val publicationLock = Mutex()
    private val _displayName = MutableStateFlow(settings.displayName)
    val displayName: StateFlow<String> = _displayName
    private val _result = MutableStateFlow(DisplayNamePublishResult())
    val result: StateFlow<DisplayNamePublishResult> = _result

    fun submit(name: String) {
        synchronized(settings) {
            val pubkey = identity.getPublicKeyHex()
            val intent = settings.saveDisplayNameIntent(pubkey, name)
            _displayName.value = intent.name
        }
        start()
        wakes.trySend(Unit)
        requestDrain()
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            scheduler.scheduleRecovery()
        } catch (e: Exception) {
            Log.w(TAG, "Name recovery scheduling deferred: ${e.javaClass.simpleName}")
        }
        requestDrain()
        appScope.launch {
            wakes.receiveAsFlow().debounce(DEBOUNCE_MS).collect {
                try {
                    if (drain().needsRetry) requestDrain()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Name publication deferred: ${e.javaClass.simpleName}")
                    requestDrain()
                }
            }
        }
        appScope.launch {
            try {
                merge(
                    groups.observeAll()
                        .map { rows -> rows.map { Triple(it.id, it.keyEpoch, it.members) } }
                        .distinctUntilChanged().map { Unit },
                    identity.observeActivePublicKey().map { Unit }
                ).collect { wakes.trySend(Unit) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Name group observation deferred: ${e.javaClass.simpleName}")
                requestDrain()
            }
        }
        wakes.trySend(Unit)
    }

    suspend fun drain(): DisplayNamePublishResult = publicationLock.withLock {
        var intent = currentIntent()
        var outcome = DisplayNamePublishResult()
        while (intent != null) {
            outcome = updateDisplayName(intent)
            _result.value = outcome
            val latest = currentIntent()
            if (latest?.identityPubkey == intent.identityPubkey && latest.revision == intent.revision) break
            intent = latest
        }
        outcome
    }

    private suspend fun currentIntent(): DisplayNameIntent? = controlLock.withLock {
        check(identity.stagedIdentitySwitch() == null && journal.get(IdentitySwitchCoordinator.SWITCH_ID) == null) {
            "Finish the pending identity switch before publishing a name"
        }
        if (!identity.hasIdentity()) return@withLock null
        synchronized(settings) {
            val intent = settings.initializeDisplayNameIntent(identity.getPublicKeyHex())
            _displayName.value = intent.name
            intent
        }
    }

    private fun requestDrain() {
        try {
            scheduler.requestDrain()
        } catch (e: Exception) {
            Log.w(TAG, "Name drain scheduling deferred: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "DisplayNamePublisher"
        const val DEBOUNCE_MS = 800L
    }
}
