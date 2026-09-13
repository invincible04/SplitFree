package com.splitfree.sync.nearby

import com.splitfree.domain.repository.IdentityContract
import com.splitfree.util.DebugLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One authenticated nearby connection, from transport `Connected` to a terminal close.
 *
 * The session is a serialized state machine: every entry point ([start], [onFrame],
 * [onTransportDisconnected], [markDirty], [close]) is invoked by [NearbySessionCoordinator] under one
 * lock, and every timer re-enters through [runSerialized]. Timers are children of [scope], which is
 * cancelled on close, so a stale timeout can never touch a replacement session for the same endpoint.
 *
 * After the group is open the protocol is symmetric. Each side is a *provider* of its own inventory
 * snapshots (`outSnap`) and a *consumer* of the peer's (`inSnap`); a snapshot is finished when the
 * consumer has a terminal [RecordOutcome] for every record it wanted and has sent [ReconcileResult].
 *
 * The provider only treats an inventory item as known to the peer once a [ReconcileResult] for the
 * snapshot that carried it has arrived (`outAcked`); a later snapshot resends everything not yet
 * acknowledged, so a lost page can never turn into an empty delta that both sides mistake for done.
 *
 * "Up to date" means both directions are finished with nothing rejected, busy or unresolved, and
 * nothing pending in durable storage on either side (`stats.deferred` mirrors the store, so work left
 * over from an earlier session or a restart counts too).
 */
class PeerSession(
    val endpointId: String,
    val generation: Long,
    private val incoming: Boolean,
    private val channelToken: ByteArray?,
    private val groupId: String,
    private val identity: IdentityContract,
    private val store: ReconciliationStore,
    private val transport: NearbyTransport,
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val runSerialized: suspend (suspend () -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    interface Listener {
        fun onProgress(session: PeerSession)

        /** New records were applied or carried from this peer; other sessions on the group should re-advertise. */
        suspend fun onDataChanged(session: PeerSession)

        fun onClosed(session: PeerSession, reason: String)
    }

    // ---- identity & authentication ----
    private val myPubkey = identity.getPublicKeyHex()
    private val myNonce = NearbyAuth.newNonce()
    private var peerHello: Hello? = null
    private var peerAuth: Auth? = null
    private var isInitiator = false
    private var transcript: NearbyAuth.Transcript? = null
    var peerPubkey: String? = null
        private set
    private var authenticated = false
    private var openSent = false
    private var groupOpen = false
    private var violations = 0

    // ---- provider half (my inventory -> peer) ----
    private var outSnap = 0

    /** Ids the peer has confirmed seeing: the union of every snapshot it answered with a ReconcileResult. */
    private var outAcked: Set<String> = emptySet()
    private var outBaselineAcked = false

    /** Ids in the snapshot currently in flight (acked ones plus the delta just sent). */
    private var outAdvertised: Set<String> = emptySet()
    private var outItemsById: Map<String, InventoryItem> = emptyMap()
    private var outPeerDone = true
    private var outDirty = false
    private val awaitingResult = LinkedHashSet<String>()
    private var peerReport: ReconcileResult? = null

    // ---- consumer half (peer inventory -> me) ----
    private var inSnap = -1
    private val inItems = ArrayList<InventoryItem>()
    private var inNextPage = 0
    private var inComplete = false
    private var inDone = false
    private val wantQueue = ArrayDeque<InventoryItem>()
    private val inflight = LinkedHashMap<String, InventoryItem>()
    private val partials = HashMap<String, Array<String?>>()
    private var controlApplied = false
    private var appliedThisSnapshot = 0
    private var retriedInflight = false
    private var outReadvertised = 0
    private val rejectedThisSnapshot = ArrayList<InventoryItem>()
    private var retriedRejected = false

    // ---- lifecycle ----
    var phase: PeerPhase = PeerPhase.AUTHENTICATING
        private set
    var closeReason: String? = null
        private set
    var stats: TransferStats = TransferStats()
        private set
    var closed = false
        private set
    private var lastActivity = clock()
    private var watchdog: Job? = null

    val isUpToDate: Boolean get() = phase == PeerPhase.UP_TO_DATE

    fun progress(): PeerProgress = PeerProgress(endpointId, peerPubkey, phase, groupId, stats, closeReason)

    fun start() {
        send(Hello(NearbyWire.PROTOCOL_VERSION, myPubkey, myNonce, incoming, CAPABILITIES.toList()))
        setPhase(PeerPhase.AUTHENTICATING)
        watchdog =
            scope.launch {
                while (true) {
                    delay(WATCHDOG_INTERVAL_MS)
                    runSerialized { if (!closed) checkTimeouts() }
                }
            }
    }

    // ------------------------------------------------------------------ frames

    suspend fun onFrame(data: ByteArray) {
        if (closed) return
        lastActivity = clock()
        when (val decoded = NearbyWire.decode(data)) {
            is NearbyWire.Decoded.LegacyPeer -> {
                Log.w(TAG, "Peer $endpointId speaks the legacy protocol; closing")
                terminate(PeerPhase.UNSUPPORTED_PEER, NearbyWire.CLOSE_UNSUPPORTED_VERSION, notifyPeer = true)
            }

            is NearbyWire.Decoded.Invalid -> violation("undecodable frame")
            is NearbyWire.Decoded.Message -> dispatch(decoded.message)
        }
    }

    private suspend fun dispatch(message: NearbyMessage) {
        when (message) {
            is Hello -> onHello(message)
            is Auth -> onAuth(message)
            is Close -> onPeerClose(message)
            is OpenGroup -> if (requireAuthenticated()) onOpenGroup(message)
            is OpenGroupResult -> if (requireAuthenticated()) onOpenGroupResult(message)
            is InventoryPage -> if (requireGroupOpen()) onInventoryPage(message)
            is Want -> if (requireGroupOpen()) onWant(message)
            is Record -> if (requireGroupOpen()) onRecord(message)
            is Result -> if (requireGroupOpen()) onResult(message)
            is ReconcileResult -> if (requireGroupOpen()) onReconcileResult(message)
        }
    }

    private suspend fun requireAuthenticated(): Boolean {
        if (authenticated) return true
        violation("message before authentication")
        return false
    }

    private suspend fun requireGroupOpen(): Boolean {
        if (groupOpen) return true
        violation("message before group open")
        return false
    }

    // ------------------------------------------------------------ authentication

    private suspend fun onHello(hello: Hello) {
        if (hello.v != NearbyWire.PROTOCOL_VERSION) {
            terminate(PeerPhase.UNSUPPORTED_PEER, NearbyWire.CLOSE_UNSUPPORTED_VERSION, notifyPeer = true)
            return
        }
        val existing = peerHello
        if (existing != null) {
            if (existing == hello) return // idempotent duplicate
            violation("conflicting Hello")
            return
        }
        if (!NearbyAuth.isHex32(hello.pubkey) || !NearbyAuth.isHex32(hello.nonce) || hello.pubkey == myPubkey) {
            terminate(PeerPhase.AUTH_FAILED, NearbyWire.CLOSE_AUTH_FAILED, notifyPeer = true)
            return
        }
        peerHello = hello
        peerPubkey = hello.pubkey
        isInitiator = NearbyAuth.localIsInitiator(myPubkey, incoming, hello.pubkey, hello.incoming)
        val caps = (CAPABILITIES intersect hello.caps.toSet())
        val t =
            if (isInitiator) {
                NearbyAuth.Transcript(myPubkey, hello.pubkey, myNonce, hello.nonce, channelToken, caps)
            } else {
                NearbyAuth.Transcript(hello.pubkey, myPubkey, hello.nonce, myNonce, channelToken, caps)
            }
        transcript = t
        val priv = identity.getPrivateKeyBytes()
        val sig =
            try {
                NearbyAuth.sign(t, isInitiator, priv)
            } finally {
                priv.fill(0)
            }
        send(Auth(sig))
        peerAuth?.let { buffered ->
            peerAuth = null
            onAuth(buffered)
        }
        listener.onProgress(this)
    }

    private suspend fun onAuth(auth: Auth) {
        val t = transcript
        if (t == null) {
            // Same-type payload order is guaranteed on one Nearby connection, so a peer's Auth cannot
            // overtake its Hello; a lone Auth is a violation. Buffer exactly one anyway so an
            // unusual transport cannot make a valid peer fail.
            if (peerAuth == null) peerAuth = auth else violation("Auth before Hello")
            return
        }
        if (authenticated) {
            if (peerAuth == auth) return
            violation("conflicting Auth")
            return
        }
        val peer = checkNotNull(peerPubkey)
        if (!NearbyAuth.verify(t, signerIsInitiator = !isInitiator, signerPubkey = peer, sigHex = auth.sig)) {
            Log.w(TAG, "Authentication failed for $endpointId")
            terminate(PeerPhase.AUTH_FAILED, NearbyWire.CLOSE_AUTH_FAILED, notifyPeer = true)
            return
        }
        peerAuth = auth
        authenticated = true
        setPhase(PeerPhase.OPENING_GROUP)
        if (isInitiator) openGroup()
    }

    // --------------------------------------------------------------- group scope

    private suspend fun openGroup() {
        val peer = checkNotNull(peerPubkey)
        if (!store.isAuthorizedForGroup(groupId, peer)) {
            // Never reveal the group id to a peer we cannot place in it.
            terminate(PeerPhase.UNAUTHORIZED, NearbyWire.CLOSE_UNAUTHORIZED, notifyPeer = true)
            return
        }
        openSent = true
        send(OpenGroup(groupId, joinEvent = store.ownJoinEvent(groupId)))
    }

    private suspend fun onOpenGroup(msg: OpenGroup) {
        if (isInitiator || groupOpen) {
            violation("unexpected OpenGroup")
            return
        }
        val peer = checkNotNull(peerPubkey)
        var authorized = msg.groupId == groupId && store.isAuthorizedForGroup(groupId, peer)
        if (!authorized && msg.groupId == groupId && msg.joinEvent != null) {
            authorized = store.admitJoin(groupId, peer, msg.joinEvent)
        }
        if (!authorized) {
            send(OpenGroupResult(msg.groupId, ok = false, reason = NearbyWire.OPEN_REFUSED))
            terminate(PeerPhase.UNAUTHORIZED, NearbyWire.CLOSE_UNAUTHORIZED, notifyPeer = false)
            return
        }
        send(OpenGroupResult(groupId, ok = true))
        onGroupOpened()
    }

    private suspend fun onOpenGroupResult(msg: OpenGroupResult) {
        if (!isInitiator || !openSent || groupOpen) {
            violation("unexpected OpenGroupResult")
            return
        }
        if (!msg.ok || msg.groupId != groupId) {
            terminate(PeerPhase.UNAUTHORIZED, NearbyWire.CLOSE_UNAUTHORIZED, notifyPeer = false)
            return
        }
        onGroupOpened()
    }

    private suspend fun onGroupOpened() {
        groupOpen = true
        setPhase(PeerPhase.COMPARING)
        // Work left pending by an earlier session or a restart is this session's business too: try
        // it now (a dependency may have arrived meanwhile) and carry whatever is still pending into
        // the completion check.
        try {
            store.retryDeferred(groupId)
        } catch (e: Exception) {
            Log.w(TAG, "retryDeferred at open failed for $endpointId: ${e.message}")
        }
        refreshPending()
        advertise(force = true)
    }

    /** Mirror the store's durable pending count into [stats]; the phase derives from it. */
    private suspend fun refreshPending() {
        val pending =
            try {
                store.pendingCount(groupId)
            } catch (e: Exception) {
                Log.w(TAG, "pendingCount failed for $endpointId: ${e.message}")
                stats.deferred
            }
        if (pending != stats.deferred) stats = stats.copy(deferred = pending)
    }

    // ------------------------------------------------------------- provider half

    /** Local data changed; re-advertise now or as soon as the peer finishes the current snapshot. */
    suspend fun markDirty() {
        if (closed || !groupOpen) return
        outDirty = true
        if (outPeerDone) advertise(force = false)
    }

    private suspend fun advertise(force: Boolean) {
        val items = store.inventory(groupId)
        // Delta against what the peer has ACKNOWLEDGED, not what we once sent: a page lost in
        // transit is simply sent again in the next snapshot.
        val toSend = items.filter { it.id !in outAcked }
        if (!force && outBaselineAcked && toSend.isEmpty()) {
            outDirty = false
            updatePhase()
            return
        }
        outSnap++
        outAdvertised = outAcked + items.mapTo(HashSet()) { it.id }
        outItemsById = items.associateBy { it.id }
        outPeerDone = false
        outDirty = false
        peerReport = null
        awaitingResult.clear()
        NearbyWire.paginate(outSnap, delta = outBaselineAcked, toSend).forEach { send(it) }
        updatePhase()
    }

    private suspend fun onWant(want: Want) {
        if (want.snap != outSnap) return
        if (want.ids.size > NearbyWire.MAX_INFLIGHT_RECORDS) {
            violation("oversized Want")
            return
        }
        for (id in want.ids) {
            val item = outItemsById[id]
            val record = item?.let { store.loadRecord(groupId, it) }
            if (record == null) {
                // Only advertised, still-available records are served; anything else is "unavailable".
                send(Record(outSnap, id, item?.t ?: NearbyWire.KIND_EVENT, 0, 0, ""))
                continue
            }
            val chunks =
                try {
                    NearbyWire.chunk(outSnap, id, record.kind, record.json)
                } catch (_: IllegalArgumentException) {
                    send(Record(outSnap, id, record.kind, 0, 0, ""))
                    continue
                }
            chunks.forEach { send(it) }
            awaitingResult.add(id)
            stats = stats.copy(sent = stats.sent + 1)
        }
        updatePhase()
    }

    private fun onResult(result: Result) {
        if (result.snap != outSnap) return
        awaitingResult.remove(result.id)
        updatePhase()
    }

    private suspend fun onReconcileResult(report: ReconcileResult) {
        if (report.snap != outSnap) return
        outPeerDone = true
        outReadvertised = 0
        peerReport = report
        outAcked = outAdvertised
        outBaselineAcked = true
        awaitingResult.clear()
        if (outDirty) advertise(force = false) else updatePhase()
    }

    // ------------------------------------------------------------- consumer half

    private suspend fun onInventoryPage(page: InventoryPage) {
        if (page.snap < inSnap) return
        if (page.snap > inSnap) beginSnapshot(page.snap)
        if (page.page != inNextPage) {
            // A page went missing (failed send). Not a violation: the provider notices the silence
            // and re-advertises everything we have not acknowledged.
            Log.w(TAG, "Inventory page ${page.page} from $endpointId out of order (expected $inNextPage); waiting")
            return
        }
        inNextPage++
        if (inItems.size + page.items.size > NearbyWire.MAX_INVENTORY_ITEMS) {
            violation("inventory too large")
            return
        }
        inItems += page.items.filter { it.isWellFormed() }
        if (page.last) {
            inComplete = true
            val wanted = store.selectWanted(groupId, inItems, checkNotNull(peerPubkey))
            inItems.clear()
            inItems.trimToSize()
            wantQueue.addAll(wanted)
            pumpWants()
        } else {
            updatePhase()
        }
    }

    private fun beginSnapshot(snap: Int) {
        // A peer only advertises a new snapshot after we reported the previous one done, so
        // anything still in flight here is lost; count it rather than pretend it applied.
        if (inflight.isNotEmpty()) stats = stats.copy(unresolved = stats.unresolved + inflight.size)
        inSnap = snap
        inItems.clear()
        inNextPage = 0
        inComplete = false
        inDone = false
        wantQueue.clear()
        inflight.clear()
        partials.clear()
        controlApplied = false
        appliedThisSnapshot = 0
        retriedInflight = false
        rejectedThisSnapshot.clear()
        retriedRejected = false
    }

    private suspend fun pumpWants() {
        while (inflight.size < NearbyWire.MAX_INFLIGHT_RECORDS && wantQueue.isNotEmpty()) {
            val batch = ArrayList<InventoryItem>()
            while (batch.size + inflight.size < NearbyWire.MAX_INFLIGHT_RECORDS && wantQueue.isNotEmpty()) {
                batch.add(wantQueue.removeFirst())
            }
            batch.forEach { inflight[it.id] = it }
            send(Want(inSnap, batch.map { it.id }))
        }
        if (inComplete && inflight.isEmpty() && wantQueue.isEmpty() && !inDone) finishConsuming()
        updatePhase()
    }

    private suspend fun finishConsuming() {
        if (controlApplied) {
            val retried = store.retryDeferred(groupId)
            if (retried > 0) {
                stats = stats.copy(applied = stats.applied + retried)
                appliedThisSnapshot += retried
            }
            refreshPending()
            if (!retriedRejected && rejectedThisSnapshot.isNotEmpty()) {
                // Records that arrived before the key they need were rejected as undecryptable.
                // Now that a control record landed, ask for them once more within this snapshot.
                retriedRejected = true
                controlApplied = false
                val again = rejectedThisSnapshot.toList()
                rejectedThisSnapshot.clear()
                stats = stats.copy(rejected = (stats.rejected - again.size).coerceAtLeast(0))
                wantQueue.addAll(again)
                pumpWants()
                return
            }
        }
        inDone = true
        send(
            ReconcileResult(
                snap = inSnap,
                applied = stats.applied,
                alreadyApplied = stats.alreadyApplied,
                deferred = stats.deferred,
                rejected = stats.rejected,
                carried = stats.carried,
                busy = stats.busy,
                unresolved = stats.unresolved
            )
        )
        if (appliedThisSnapshot > 0) listener.onDataChanged(this)
    }

    private suspend fun onRecord(record: Record) {
        if (record.snap != inSnap) return
        val item = inflight[record.id]
        if (item == null) {
            violation("unsolicited record ${record.id.take(8)}")
            return
        }
        if (record.parts == 0) {
            inflight.remove(record.id)
            stats = stats.copy(unresolved = stats.unresolved + 1)
            pumpWants()
            return
        }
        val malformed =
            record.parts > NearbyWire.MAX_RECORD_PARTS ||
                record.part !in 0 until record.parts ||
                record.data.length > NearbyWire.MAX_RECORD_CHUNK_CHARS ||
                record.kind != item.t
        if (malformed) {
            resolve(record.id, RecordOutcome.REJECTED)
            return
        }
        val buffer =
            partials[record.id] ?: run {
                if (partials.size >= NearbyWire.MAX_PARTIAL_RECORDS) {
                    resolve(record.id, RecordOutcome.BUSY)
                    return
                }
                arrayOfNulls<String>(record.parts).also { partials[record.id] = it }
            }
        if (buffer.size != record.parts) {
            resolve(record.id, RecordOutcome.REJECTED)
            return
        }
        buffer[record.part] = record.data
        if (buffer.any { it == null }) {
            updatePhase()
            return
        }
        partials.remove(record.id)
        val json = buffer.joinToString("")
        val report =
            try {
                store.ingest(groupId, item, json, checkNotNull(peerPubkey))
            } catch (e: Exception) {
                Log.w(TAG, "Ingest threw for ${record.id.take(8)}: ${e.message}")
                IngestReport(RecordOutcome.REJECTED)
            }
        stats = stats.copy(received = stats.received + 1)
        if (report.upgraded) stats = stats.copy(upgraded = stats.upgraded + 1)
        if (report.controlApplied) controlApplied = true
        if (report.outcome == RecordOutcome.DEFERRED || report.controlApplied) refreshPending()
        if (report.outcome == RecordOutcome.REJECTED && !retriedRejected) rejectedThisSnapshot.add(item)
        if (report.outcome == RecordOutcome.APPLIED || report.outcome == RecordOutcome.CARRIED || report.upgraded) {
            appliedThisSnapshot++
        }
        resolve(record.id, report.outcome)
    }

    private suspend fun resolve(id: String, outcome: RecordOutcome) {
        inflight.remove(id)
        partials.remove(id)
        stats =
            when (outcome) {
                RecordOutcome.APPLIED -> stats.copy(applied = stats.applied + 1)
                RecordOutcome.ALREADY_APPLIED -> stats.copy(alreadyApplied = stats.alreadyApplied + 1)
                // deferred mirrors the store's pending count (refreshed by the caller), not a tally.
                RecordOutcome.DEFERRED -> stats
                RecordOutcome.REJECTED -> stats.copy(rejected = stats.rejected + 1)
                RecordOutcome.BUSY -> stats.copy(busy = stats.busy + 1)
                RecordOutcome.CARRIED -> stats.copy(carried = stats.carried + 1)
            }
        send(Result(inSnap, id, outcome))
        pumpWants()
    }

    // ------------------------------------------------------------------ timeouts

    private suspend fun checkTimeouts() {
        val idle = clock() - lastActivity
        if (!authenticated) {
            if (idle >= HANDSHAKE_TIMEOUT_MS) {
                Log.w(TAG, "Handshake timed out for $endpointId")
                terminate(PeerPhase.INTERRUPTED, NearbyWire.CLOSE_TIMEOUT, notifyPeer = true)
            }
            return
        }
        if (!groupOpen) {
            if (idle >=
                HANDSHAKE_TIMEOUT_MS
            ) {
                terminate(PeerPhase.INTERRUPTED, NearbyWire.CLOSE_TIMEOUT, notifyPeer = true)
            }
            return
        }
        if (idle < TRANSFER_TIMEOUT_MS) return
        if (inflight.isNotEmpty()) {
            if (!retriedInflight) {
                // One bounded retry reusing the same record identities; duplicates are harmless.
                retriedInflight = true
                partials.clear()
                send(Want(inSnap, inflight.keys.toList()))
                lastActivity = clock()
            } else {
                stats = stats.copy(unresolved = stats.unresolved + inflight.size)
                inflight.clear()
                partials.clear()
                pumpWants()
            }
            return
        }
        if (!outPeerDone && awaitingResult.isEmpty()) {
            // The peer's ReconcileResult, or one of our pages, was lost. Re-advertise everything the
            // peer has not acknowledged (empty if only the result went missing); give up after a
            // bounded number of attempts.
            if (outReadvertised < MAX_READVERTISE) {
                outReadvertised++
                advertise(force = true)
                lastActivity = clock()
            } else {
                stats = stats.copy(unresolved = stats.unresolved + 1)
                outPeerDone = true
                updatePhase()
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private suspend fun onPeerClose(close: Close) {
        val phase =
            when (close.reason) {
                NearbyWire.CLOSE_UNSUPPORTED_VERSION -> PeerPhase.UNSUPPORTED_PEER
                NearbyWire.CLOSE_AUTH_FAILED -> PeerPhase.AUTH_FAILED
                NearbyWire.CLOSE_UNAUTHORIZED -> PeerPhase.UNAUTHORIZED
                else -> PeerPhase.CLOSED
            }
        terminate(phase, close.reason, notifyPeer = false)
    }

    suspend fun onTransportDisconnected() {
        terminate(PeerPhase.INTERRUPTED, NearbyWire.CLOSE_PEER_DISCONNECTED, notifyPeer = false)
    }

    /** Owner-initiated close (stop, replacement, permission loss). Idempotent. */
    suspend fun close(reason: String) {
        terminate(PeerPhase.CLOSED, reason, notifyPeer = true)
    }

    private suspend fun violation(what: String) {
        violations++
        Log.w(TAG, "Protocol violation from $endpointId: $what ($violations)")
        if (violations >= MAX_VIOLATIONS || what.startsWith("unexpected") || what.startsWith("conflicting")) {
            terminate(PeerPhase.CLOSED, NearbyWire.CLOSE_PROTOCOL_VIOLATION, notifyPeer = true)
        }
    }

    private suspend fun terminate(finalPhase: PeerPhase, reason: String, notifyPeer: Boolean) {
        if (closed) return
        closed = true
        closeReason = reason
        if (notifyPeer) {
            try {
                transport.sendPayload(endpointId, NearbyWire.encode(Close(reason)))
            } catch (_: Exception) {
                // Transport already gone; nothing to do.
            }
        }
        if (inflight.isNotEmpty()) stats = stats.copy(unresolved = stats.unresolved + inflight.size)
        watchdog?.cancel()
        scope.cancel()
        // Terminal cleanup: nothing authenticated or half-received survives this session.
        authenticated = false
        groupOpen = false
        transcript = null
        peerAuth = null
        inflight.clear()
        partials.clear()
        wantQueue.clear()
        inItems.clear()
        awaitingResult.clear()
        outItemsById = emptyMap()
        outAdvertised = emptySet()
        outAcked = emptySet()
        phase = finalPhase
        listener.onClosed(this, reason)
    }

    private fun updatePhase() {
        if (closed || !groupOpen) return
        val transferring = inflight.isNotEmpty() || awaitingResult.isNotEmpty() || wantQueue.isNotEmpty()
        val finished = inDone && outPeerDone && !outDirty && !transferring
        val peerFailed = peerReport?.let { it.rejected + it.busy + it.unresolved > 0 } ?: false
        // Either side still holding pending work means the pair is not converged: the peer's
        // deferred count is its durable pending state, reported in its ReconcileResult.
        val peerWaiting = (peerReport?.deferred ?: 0) > 0
        val next =
            when {
                transferring -> PeerPhase.TRANSFERRING
                !finished -> PeerPhase.COMPARING
                stats.hasFailures || peerFailed -> PeerPhase.INCOMPLETE
                stats.deferred > 0 || peerWaiting -> PeerPhase.WAITING_DEPENDENCY
                else -> PeerPhase.UP_TO_DATE
            }
        setPhase(next)
    }

    private fun setPhase(next: PeerPhase) {
        phase = next
        listener.onProgress(this)
    }

    private fun send(message: NearbyMessage) {
        transport.sendPayload(endpointId, NearbyWire.encode(message))
    }

    private fun InventoryItem.isWellFormed(): Boolean = NearbyAuth.isHex32(id) &&
        (
            t == NearbyWire.KIND_EVENT ||
                (
                    t == NearbyWire.KIND_DELIVERY &&
                        r != null &&
                        NearbyAuth.isHex32(r) &&
                        (e == null || NearbyAuth.isHex32(e))
                    )
            )

    companion object {
        private const val TAG = "PeerSession"
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val TRANSFER_TIMEOUT_MS = 30_000L
        const val WATCHDOG_INTERVAL_MS = 2_500L
        private const val MAX_VIOLATIONS = 3
        private const val MAX_READVERTISE = 2
        val CAPABILITIES: Set<String> = setOf(NearbyWire.CAP_RECONCILE_V2, NearbyWire.CAP_DELIVERIES)
    }
}
