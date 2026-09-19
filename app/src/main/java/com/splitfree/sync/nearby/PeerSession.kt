package com.splitfree.sync.nearby

import com.splitfree.domain.repository.IdentityContract
import com.splitfree.util.DebugLog as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Authenticates and reconciles one transport connection within a single group.
 *
 * - The owner serializes all entry points and [runSerialized] callbacks under the same lock.
 * - This session owns [scope] and cancels it on close, isolating its timers from replacement sessions.
 * - Each side provides local inventory and consumes peer inventory independently; only [ReconcileResult] acknowledges
 *   a snapshot, and subsequent snapshots include every unacknowledged item.
 * - [PeerPhase.UP_TO_DATE] describes the reported exchange: both directions finished without reported failures,
 *   pending work or missing [NearbyWire.KIND_HELD] ids.
 * - It does not compare group-state digests or prove identical ledgers.
 * - Unreadable local pending state prevents that phase.
 */
class PeerSession(
    val connection: NearbyConnection,
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
    val endpointId: String get() = connection.endpointId

    /** Owner callbacks; all are invoked while the owner's lock is held. */
    interface Listener {
        /** Publishes changed session progress while the session remains open. */
        fun onProgress(session: PeerSession)

        /** Local data or evidence changes during reconciliation require other group sessions to re-advertise. */
        suspend fun onDataChanged(session: PeerSession)

        /** Terminal callback after scope cancellation; [reason] is local protocol text or the peer's close reason. */
        fun onClosed(session: PeerSession, reason: String)
    }

    // ------------------------------------------- identity & authentication state
    private val myPubkey = identity.getPublicKeyHex()
    private val myNonce = NearbyAuth.newNonce()
    private var peerHello: Hello? = null
    private var peerAuth: Auth? = null
    private var isInitiator = false
    private var transcript: NearbyAuth.Transcript? = null

    /** The key the peer claims in its [Hello]; trusted only once [authenticated]. */
    private var peerPubkey: String? = null

    /** The peer's key once its [Auth] signature verified; exposed through [progress] and kept after close. */
    private var verifiedPeerPubkey: String? = null
    private var authenticated = false
    private var openSent = false
    private var groupOpen = false
    private var violations = 0

    /** Capabilities both sides advertised; fixed by [onHello] and bound into the authentication transcript. */
    private var negotiatedCaps: Set<String> = emptySet()

    /** Initiator only: the peer is not a known member yet and an [Introduce] may still admit it. */
    private var awaitingIntroduction = false

    /** One [Introduce] buffered before authentication; cleared before processing it after [Auth]. */
    private var peerIntroduce: Introduce? = null
    private var introduced = false

    // ---------------------------------- provider state (local inventory -> peer)
    private var outSnap = 0

    /**
     * Entries the peer has confirmed seeing, keyed by kind and id: the union of every snapshot it answered with a
     * [ReconcileResult].
     *
     * - Keyed by kind so a record that changes kind (a rumor upgraded to a signed event) is offered again under its
     *   new kind.
     */
    private var outAcked: Set<String> = emptySet()
    private var outBaselineAcked = false

    /** Entries in the snapshot currently in flight (acked ones plus the delta just sent). */
    private var outAdvertised: Set<String> = emptySet()
    private var outItemsById: Map<String, InventoryItem> = emptyMap()
    private var outPeerDone = true
    private var outDirty = false
    private var outStateDirty = false
    private var pendingReadable = true
    private var peerPending = 0
    private val awaitingResult = LinkedHashSet<String>()
    private var peerReport: ReconcileResult? = null

    // ---------------------------------- consumer state (peer inventory -> local)
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

    /**
     * Records awaiting a dependency, such as a key epoch or join.
     *
     * - Retained for the session's lifetime, not just the current snapshot.
     * - Requested again after a control record arrives or the local store changes.
     * - The provider still serves acknowledged ids on request.
     * - Malformed or unauthorized records are not kept; they stay rejected.
     */
    private val dependencyRetry = LinkedHashMap<String, InventoryItem>()
    private var retriedDependencies = false

    /** [NearbyWire.KIND_HELD] ids the peer has advertised this session; [TransferStats.held] counts those missing here. */
    private val inHeld = HashSet<String>()

    // ----------------------------------------------------------- lifecycle state
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

    /** Observer snapshot; [PeerProgress.peerPubkey] is the verified key, or null before authentication completes. */
    fun progress(): PeerProgress = PeerProgress(endpointId, verifiedPeerPubkey, phase, groupId, stats, closeReason)

    /**
     * Called once after the owner registers this session.
     *
     * - A synchronous exception while submitting [Hello] interrupts the session without arming a watchdog or sending
     *   [Close].
     * - Asynchronous transport outcomes arrive separately; a successful return from send is not a delivery receipt.
     */
    suspend fun start() {
        try {
            send(Hello(NearbyWire.PROTOCOL_VERSION, myPubkey, myNonce, incoming, CAPABILITIES.toList()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Hello could not be handed to the transport for $endpointId: ${e.message}")
            terminate(PeerPhase.INTERRUPTED, NearbyWire.CLOSE_TRANSPORT_ERROR, notifyPeer = false)
            return
        }
        setPhase(PeerPhase.AUTHENTICATING)
        watchdog =
            scope.launch {
                while (true) {
                    delay(WATCHDOG_INTERVAL_MS)
                    runSerialized { runSafely { checkTimeouts() } }
                }
            }
    }

    // -------------------------------------------------------------------- frames

    /** Route one raw transport payload. Frames arriving after close are ignored. */
    suspend fun onFrame(data: ByteArray) = runSafely {
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
            is Introduce -> onIntroduce(message)
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
        if (groupOpen) {
            if (identity.getPublicKeyHex() != myPubkey ||
                !store.isAuthorizedForGroup(groupId, myPubkey) ||
                !store.isAuthorizedForGroup(groupId, checkNotNull(peerPubkey))
            ) {
                terminate(PeerPhase.UNAUTHORIZED, NearbyWire.CLOSE_UNAUTHORIZED, notifyPeer = true)
                return false
            }
            return true
        }
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
        negotiatedCaps = caps
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
            // Buffer at most one Auth before Hello to tolerate transport reordering.
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
        verifiedPeerPubkey = peer
        setPhase(PeerPhase.OPENING_GROUP)
        if (isInitiator) {
            openGroup()
            // An Introduce that overtook the peer's Auth is applied now that the peer is verified.
            peerIntroduce?.let { buffered ->
                peerIntroduce = null
                onIntroduce(buffered)
            }
        } else if (NearbyWire.CAP_INTRODUCE in negotiatedCaps) {
            introduce(peer)
        }
    }

    // --------------------------------------------------------------- group scope

    private suspend fun openGroup() {
        val peer = checkNotNull(peerPubkey)
        if (!store.isAuthorizedForGroup(groupId, peer)) {
            if (NearbyWire.CAP_INTRODUCE in negotiatedCaps && !introduced) {
                // The peer may be a member whose join has not reached this phone yet; it can still prove it.
                awaitingIntroduction = true
                return
            }
            // The group id is never sent to a peer the local side cannot authorize for it.
            terminate(PeerPhase.UNAUTHORIZED, NearbyWire.CLOSE_UNAUTHORIZED, notifyPeer = true)
            return
        }
        awaitingIntroduction = false
        openSent = true
        send(OpenGroup(groupId, joinEvent = store.ownJoinEvent(groupId)))
    }

    /**
     * Offer a join proof only to an authorized peer: the proof reveals the group id.
     * Otherwise send an empty [Introduce] so an awaiting initiator can refuse without a timeout.
     */
    private suspend fun introduce(peer: String) {
        val proof = if (store.isAuthorizedForGroup(groupId, peer)) store.ownJoinEvent(groupId) else null
        send(Introduce(proof))
    }

    /**
     * Process one responder introduction after authentication. Only an initiator still awaiting
     * membership proof applies it; an already-authorized peer needs no introduction.
     */
    private suspend fun onIntroduce(msg: Introduce) {
        if (!authenticated) {
            // Buffer at most one Introduce before Auth to tolerate transport reordering.
            if (peerIntroduce == null) peerIntroduce = msg else violation("Introduce before authentication")
            return
        }
        if (!isInitiator) {
            violation("unexpected Introduce")
            return
        }
        if (introduced) {
            violation("conflicting Introduce")
            return
        }
        introduced = true
        if (!awaitingIntroduction) return
        awaitingIntroduction = false
        val peer = checkNotNull(peerPubkey)
        val admitted = msg.joinEvent != null && store.admitJoin(groupId, peer, msg.joinEvent)
        if (!admitted) {
            terminate(PeerPhase.UNAUTHORIZED, NearbyWire.CLOSE_UNAUTHORIZED, notifyPeer = true)
            return
        }
        Log.i(TAG, "Admitted $endpointId into ${groupId.take(8)} from its introduction")
        openGroup()
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
        // Retry durable pending work before advertising; its dependencies may be available locally.
        try {
            store.retryDeferred(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "retryDeferred at open failed for $endpointId: ${e.message}")
        }
        refreshPending()
        advertise(force = true)
    }

    /** Failed reads retain the last count and advertise an unresolved sentinel instead of claiming convergence. */
    private suspend fun refreshPending() {
        val wasReadable = pendingReadable
        val pending =
            try {
                store.pendingCount(groupId).also { pendingReadable = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingReadable = false
                Log.w(TAG, "pendingCount failed for $endpointId: ${e.message}")
                stats.deferred
            }
        if (pending != stats.deferred || wasReadable != pendingReadable) {
            stats = stats.copy(deferred = pending)
            outStateDirty = true
        }
    }

    // ------------------------------------------------------------- provider half

    /**
     * Refreshes pending state and re-advertises changed data when the current provider snapshot completes.
     *
     * - A local store change may also be the dependency a rejected record was waiting for (a key that came over a
     *   relay, say), so a finished consumer with retained records asks for them again.
     */
    suspend fun markDirty() = runSafely {
        if (!groupOpen || !requireGroupOpen()) return@runSafely
        refreshPending()
        val heldChanged = refreshHeld()
        if (inDone && (outStateDirty || heldChanged)) sendReconcileResult()
        outDirty = true
        if (outPeerDone) advertise(force = false)
        if (inDone && dependencyRetry.isNotEmpty()) {
            inDone = false
            requeueDependencies()
        }
    }

    private suspend fun advertise(force: Boolean) {
        refreshPending()
        val items = store.inventory(groupId)
        // The delta is against what the peer has acknowledged, so a page lost in transit is
        // resent by the next snapshot.
        val toSend = items.filter { it.key() !in outAcked }
        if (!force && !outStateDirty && outBaselineAcked && toSend.isEmpty()) {
            outDirty = false
            updatePhase()
            return
        }
        outSnap++
        outAdvertised = outAcked + items.mapTo(HashSet()) { it.key() }
        outItemsById = items.associateBy { it.id }
        outPeerDone = false
        outDirty = false
        outStateDirty = false
        peerReport = null
        awaitingResult.clear()
        NearbyWire.paginate(
            outSnap,
            delta = outBaselineAcked,
            toSend,
            pending = if (pendingReadable) stats.deferred else stats.deferred.coerceAtLeast(1)
        )
            .forEach { send(it) }
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
                // Only advertised, still-available records are served; a zero-part Record means unavailable.
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
        peerPending = report.deferred.coerceAtLeast(0)
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
            // Only contiguous pages are consumed; the provider's watchdog retries unacknowledged inventory.
            Log.w(TAG, "Inventory page ${page.page} from $endpointId out of order (expected $inNextPage); waiting")
            return
        }
        inNextPage++
        if (inItems.size + page.items.size > NearbyWire.MAX_INVENTORY_ITEMS) {
            violation("inventory too large")
            return
        }
        // A full snapshot resets held-id accounting; deltas retain previously advertised held ids.
        if (page.page == 0 && !page.delta) inHeld.clear()
        val wellFormed = page.items.filter { it.isWellFormed() }
        inItems += wellFormed
        wellFormed.filter { it.t == NearbyWire.KIND_HELD }.mapTo(inHeld) { it.id }
        if (page.last) {
            peerPending = page.pending.coerceAtLeast(0)
            inComplete = true
            val wanted = store.selectWanted(groupId, inItems, checkNotNull(peerPubkey))
            refreshHeld()
            inItems.clear()
            inItems.trimToSize()
            wantQueue.addAll(wanted)
            pumpWants()
        } else {
            updatePhase()
        }
    }

    private fun beginSnapshot(snap: Int) {
        // In-flight requests belong to their snapshot and remain unresolved when a newer one replaces it.
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
        retriedDependencies = false
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
        if (appliedThisSnapshot > 0) {
            // Anything applied may be what a durable pending row was waiting for: a key or join for a
            // control record, an original for a correction that arrived ahead of it.
            val retried = store.retryDeferred(groupId)
            if (retried > 0) {
                stats = stats.copy(applied = stats.applied + retried)
                appliedThisSnapshot += retried
            }
            refreshPending()
        }
        if (controlApplied && !retriedDependencies && dependencyRetry.isNotEmpty()) {
            // A key or membership record landed; records refused for lack of one are asked for again,
            // once per snapshot, whichever snapshot first offered them.
            retriedDependencies = true
            controlApplied = false
            requeueDependencies()
            return
        }
        refreshPending()
        inDone = true
        sendReconcileResult()
        if (appliedThisSnapshot > 0) listener.onDataChanged(this)
    }

    /**
     * Recounts advertised rumor-only ids absent from local storage.
     *
     * - Any gap prevents local convergence and is reported to the peer.
     * - This tests row presence, not equal apply state; another sync path may supply a missing record without this
     *   session transferring it.
     *
     * @return true when the count changed
     */
    private suspend fun refreshHeld(): Boolean {
        val held = if (inHeld.isEmpty()) 0 else store.countMissing(groupId, inHeld)
        if (held == stats.held) return false
        stats = stats.copy(held = held)
        return true
    }

    /** Asks for every retained record again; their rejections are uncounted until they resolve. */
    private suspend fun requeueDependencies() {
        val again = dependencyRetry.values.toList()
        stats = stats.copy(rejected = (stats.rejected - again.size).coerceAtLeast(0))
        wantQueue.addAll(again)
        pumpWants()
    }

    private fun sendReconcileResult() {
        send(
            ReconcileResult(
                snap = inSnap,
                applied = stats.applied,
                alreadyApplied = stats.alreadyApplied,
                deferred = stats.deferred,
                rejected = stats.rejected,
                carried = stats.carried,
                busy = stats.busy,
                // An unreadable pending count fails closed on the peer too. The sentinel is per
                // report; the local counter is not incremented.
                unresolved = if (pendingReadable) stats.unresolved else stats.unresolved.coerceAtLeast(1),
                held = stats.held
            )
        )
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Ingest threw for ${record.id.take(8)}: ${e.message}")
                IngestReport(RecordOutcome.REJECTED)
            }
        stats = stats.copy(received = stats.received + 1)
        if (report.upgraded) stats = stats.copy(upgraded = stats.upgraded + 1)
        if (report.controlApplied) controlApplied = true
        if (report.outcome == RecordOutcome.DEFERRED || report.controlApplied) refreshPending()
        if (report.outcome == RecordOutcome.REJECTED && report.retryable) {
            if (dependencyRetry.size < MAX_RETAINED_DEPENDENCIES) dependencyRetry[item.id] = item
        } else {
            dependencyRetry.remove(item.id)
        }
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
                // Each snapshot permits one retry of its in-flight requests.
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
        if (!outPeerDone) {
            // The consumer gets its own retry window before the snapshot it is requesting from is
            // replaced; provider and consumer watchdogs may fire at the same instant.
            if (awaitingResult.isNotEmpty() && idle < TRANSFER_TIMEOUT_MS * 2) return
            // Retry unacknowledged inventory up to MAX_READVERTISE times to recover missing pages or receipts.
            if (outReadvertised < MAX_READVERTISE) {
                outReadvertised++
                advertise(force = true)
                lastActivity = clock()
            } else {
                stats = stats.copy(unresolved = stats.unresolved + awaitingResult.size.coerceAtLeast(1))
                terminate(PeerPhase.INTERRUPTED, NearbyWire.CLOSE_TIMEOUT, notifyPeer = true)
            }
        }
    }

    // ----------------------------------------------------------------- lifecycle

    /** Unexpected frame, dirty-work and watchdog failures end only this session; run cancellation is not a failure. */
    private suspend fun runSafely(block: suspend () -> Unit) {
        if (closed) return
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Session work failed for $endpointId: ${e.message}")
            terminate(PeerPhase.INTERRUPTED, NearbyWire.CLOSE_SESSION_ERROR, notifyPeer = true)
        }
    }

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

    /** The transport dropped the endpoint. Terminal; no [Close] is sent. */
    suspend fun onTransportDisconnected() {
        terminate(PeerPhase.INTERRUPTED, NearbyWire.CLOSE_PEER_DISCONNECTED, notifyPeer = false)
    }

    /** Closes the session once, attempts to send [reason], and cancels its scope; use a [NearbyWire] close reason. */
    suspend fun close(reason: String) {
        terminate(PeerPhase.CLOSED, reason, notifyPeer = true)
    }

    /**
     * Retires a superseded session without a redundant [Close] or transport disconnect.
     *
     * - Connection handles already fence replacement links; the owner must also remove this session before its
     *   terminal callback so obsolete progress cannot overwrite the replacement's state.
     */
    suspend fun retire() {
        terminate(PeerPhase.CLOSED, NearbyWire.CLOSE_STOPPED, notifyPeer = false)
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
                transport.sendPayload(connection, NearbyWire.encode(Close(reason)))
            } catch (_: Exception) {
                // Local cleanup must complete even if the close frame cannot be sent.
            }
        }
        if (inflight.isNotEmpty()) stats = stats.copy(unresolved = stats.unresolved + inflight.size)
        watchdog?.cancel()
        scope.cancel()
        // Release transfer buffers and disable authenticated processing before notifying the owner.
        authenticated = false
        groupOpen = false
        transcript = null
        peerAuth = null
        inflight.clear()
        partials.clear()
        wantQueue.clear()
        inItems.clear()
        inHeld.clear()
        dependencyRetry.clear()
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
        val peerFailed = peerReport?.let { it.rejected > 0 || it.busy > 0 || it.unresolved > 0 || it.held > 0 } ?: false
        // Pending work on either side means the pair is not converged. The peer's count is its
        // durable pending state, carried on its last inventory page and its ReconcileResult.
        val peerWaiting = peerPending > 0
        val next =
            when {
                transferring -> PeerPhase.TRANSFERRING
                !finished -> PeerPhase.COMPARING
                !pendingReadable || stats.hasFailures || peerFailed -> PeerPhase.INCOMPLETE
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
        transport.sendPayload(connection, NearbyWire.encode(message))
    }

    /** Acknowledgement key: an entry is the pair (kind, id), so a kind change is a new entry to offer. */
    private fun InventoryItem.key(): String = "$t:$id"

    private fun InventoryItem.isWellFormed(): Boolean = NearbyAuth.isHex32(id) &&
        (
            t == NearbyWire.KIND_EVENT ||
                t == NearbyWire.KIND_HELD ||
                (
                    t == NearbyWire.KIND_DELIVERY &&
                        r != null &&
                        NearbyAuth.isHex32(r) &&
                        (e == null || NearbyAuth.isHex32(e))
                    )
            )

    companion object {
        private const val TAG = "PeerSession"

        /** Idle limit while authenticating or opening the group. */
        const val HANDSHAKE_TIMEOUT_MS = 10_000L

        /** Session receive-idle threshold for transfer recovery; checked at watchdog ticks, not per record. */
        const val TRANSFER_TIMEOUT_MS = 30_000L

        /** Period of the timeout check. */
        const val WATCHDOG_INTERVAL_MS = 2_500L
        private const val MAX_VIOLATIONS = 3

        /** Snapshot re-advertisements to a silent peer before the session is interrupted. */
        private const val MAX_READVERTISE = 2

        /** Records kept for re-request after a dependency-shaped rejection; beyond this they stay rejected. */
        private const val MAX_RETAINED_DEPENDENCIES = 4_096

        /** Capabilities offered in [Hello]; the intersection with the peer's is bound into the auth transcript. */
        val CAPABILITIES: Set<String> =
            setOf(NearbyWire.CAP_RECONCILE_V2, NearbyWire.CAP_DELIVERIES, NearbyWire.CAP_INTRODUCE)
    }
}
