package moe.gensoukyo.agentpulse.protocol

class NativeSessionReducer(
    private val maxEventsPerSession: Int = 256,
    private val idFactory: () -> String = UuidV7::generate,
) {
    init {
        require(maxEventsPerSession > 0)
    }

    var state: NativeState = NativeState()
        private set

    private var discovery: Discovery? = null
    private val subscriptionQueue = ArrayDeque<String>()
    private val subscriptions = mutableSetOf<String>()
    private var pendingSubscription: PendingSubscription? = null
    private val approvalSubmissions = mutableMapOf<String, ApprovalSubmission>()

    @Synchronized
    fun accept(message: NativeServerMessage): List<NativeClientMessage> = try {
        when (message) {
            is NativeServerMessage.Hello -> hello(message)
            is NativeServerMessage.SyncStarted -> syncStarted(message)
            is NativeServerMessage.Domain -> domain(message)
            is NativeServerMessage.SyncCompleted -> syncCompleted(message)
            is NativeServerMessage.SubscriptionResult -> subscriptionResult(message)
            is NativeServerMessage.UnsubscriptionResult -> unsubscriptionResult(message)
            is NativeServerMessage.InteractionResponseResult -> interactionResponseResult(message)
            is NativeServerMessage.Error -> nativeError(message)
        }
    } catch (error: ProtocolException) {
        state = state.copy(phase = NativeState.Phase.FAILED, lastError = error.message)
        throw error
    }

    @Synchronized
    fun submitApproval(
        sessionId: String,
        interactionId: String,
        optionId: String,
    ): NativeClientMessage.SubmitInteractionResponse {
        if (state.phase != NativeState.Phase.LIVE) throw ProtocolException("Native connection is not live")
        val channelId = state.channelId ?: throw ProtocolException("Native channel identity is unavailable")
        val view = state.sessions[sessionId] ?: throw ProtocolException("approval session is unavailable")
        val approval = view.pendingApprovals[interactionId]
            ?: throw ProtocolException("approval is no longer pending")
        if (!approval.interactive || approval.options.isEmpty()) {
            throw ProtocolException("approval is read-only")
        }
        if (approval.submissionState == ApprovalSubmissionState.SUBMITTING) {
            throw ProtocolException("approval response is already being submitted")
        }
        if (approval.options.none { it.id == optionId }) {
            throw ProtocolException("approval option is unavailable")
        }
        val requestId = idFactory()
        val updated = approval.copy(
            submissionState = ApprovalSubmissionState.SUBMITTING,
            submissionError = null,
        )
        state = state.copy(
            sessions = state.sessions + (
                sessionId to view.copy(
                    pendingApprovals = view.pendingApprovals + (interactionId to updated),
                )
            ),
        )
        approvalSubmissions[requestId] = ApprovalSubmission(sessionId, interactionId)
        return NativeClientMessage.SubmitInteractionResponse(
            requestId = requestId,
            response = DomainCodec.approvalResponse(
                requestId = interactionId,
                sessionId = sessionId,
                channelId = channelId,
                optionId = optionId,
            ),
        )
    }

    @Synchronized
    fun failApprovalSubmission(requestId: String, message: String) {
        val submission = approvalSubmissions.remove(requestId)
            ?: throw ProtocolException("approval submission is no longer pending")
        markApprovalFailed(submission, message)
    }

    @Synchronized
    fun refreshSessions(): NativeClientMessage.Discover? {
        if (
            state.phase != NativeState.Phase.LIVE ||
            discovery != null ||
            pendingSubscription != null
        ) return null
        val requestId = idFactory()
        discovery = Discovery(requestId = requestId, refresh = true)
        return NativeClientMessage.Discover(requestId)
    }

    private fun hello(message: NativeServerMessage.Hello): List<NativeClientMessage> {
        if (state.phase != NativeState.Phase.AWAITING_HELLO) throw ProtocolException("unexpected server hello")
        if (message.protocolVersion != DOMAIN_PROTOCOL_VERSION) throw ProtocolException("unsupported selected protocol")
        val requestId = idFactory()
        state = state.copy(
            phase = NativeState.Phase.DISCOVERING,
            channelId = DomainCodec.channelId(message.channel),
            lastError = null,
        )
        discovery = Discovery(requestId = requestId)
        return listOf(NativeClientMessage.Discover(requestId))
    }

    private fun syncStarted(message: NativeServerMessage.SyncStarted): List<NativeClientMessage> {
        val current = discovery ?: throw ProtocolException("sync started without discovery")
        if (current.started || current.requestId != message.requestId) throw ProtocolException("mismatched sync start")
        discovery = current.copy(started = true, expectedProviders = message.providerCount, expectedSessions = message.sessionCount)
        return emptyList()
    }

    private fun domain(message: NativeServerMessage.Domain): List<NativeClientMessage> = when (val context = message.context) {
        is NativeDeliveryContext.DiscoveryProvider -> {
            val current = requireDiscovery(context.requestId)
            if (message.domain.type != "provider_descriptor") throw ProtocolException("invalid discovery provider domain")
            val provider = DomainCodec.provider(message.domain)
            discovery = current.copy(providers = current.providers + (provider.id to provider))
            emptyList()
        }
        is NativeDeliveryContext.DiscoverySession -> {
            val current = requireDiscovery(context.requestId)
            if (message.domain.type != "agent_session") throw ProtocolException("invalid discovery session domain")
            val session = DomainCodec.session(message.domain)
            discovery = current.copy(sessions = current.sessions + (session.id to SessionView(session, context.lastSequence, emptyList())))
            emptyList()
        }
        is NativeDeliveryContext.SubscriptionSession -> baselineSession(context, message.domain)
        is NativeDeliveryContext.SubscriptionInteraction -> baselineInteraction(context, message.domain)
        is NativeDeliveryContext.LiveEvent -> liveEvent(message.domain, context.route)
        NativeDeliveryContext.LiveSession -> liveSession(message.domain)
    }

    private fun syncCompleted(message: NativeServerMessage.SyncCompleted): List<NativeClientMessage> {
        val current = requireDiscovery(message.requestId)
        if (current.providers.size != current.expectedProviders || current.sessions.size != current.expectedSessions) throw ProtocolException("incomplete discovery batch")
        val sessions = if (current.refresh) {
            current.sessions + state.sessions
        } else {
            current.sessions
        }
        val newSessions = current.sessions.keys.filterNot(subscriptions::contains).sorted()
        state = state.copy(
            providers = current.providers,
            sessions = sessions,
            phase = if (newSessions.isEmpty()) NativeState.Phase.LIVE else NativeState.Phase.SUBSCRIBING,
        )
        discovery = null
        subscriptionQueue.clear()
        subscriptionQueue.addAll(newSessions)
        return nextSubscription()
    }

    private fun subscriptionResult(message: NativeServerMessage.SubscriptionResult): List<NativeClientMessage> {
        val pending = pendingSubscription ?: throw ProtocolException("subscription result without request")
        if (pending.requestId != message.requestId || pending.sessionId != message.sessionId) throw ProtocolException("mismatched subscription result")
        val discovered = state.sessions[message.sessionId] ?: throw ProtocolException("subscription targets unknown session")
        if (message.baselineSequence < discovered.cursor) {
            throw ProtocolException("baseline cursor regressed after discovery")
        }
        if (message.status == "already_subscribed") {
            if (message.pendingInteractionCount != 0) throw ProtocolException("repeated subscription cannot carry a baseline")
            subscriptions += message.sessionId
            pendingSubscription = null
            return nextSubscription()
        }
        subscriptions += message.sessionId
        pendingSubscription = pending.copy(
            resultReceived = true,
            baselineSequence = message.baselineSequence,
            expectedInteractions = message.pendingInteractionCount,
        )
        return emptyList()
    }

    private fun baselineSession(context: NativeDeliveryContext.SubscriptionSession, domain: DomainEnvelope): List<NativeClientMessage> {
        val pending = pendingSubscription ?: throw ProtocolException("baseline without subscription")
        if (!pending.resultReceived || pending.requestId != context.requestId) throw ProtocolException("baseline arrived before matching result")
        if (pending.sessionReceived) throw ProtocolException("duplicate subscription session baseline")
        val session = DomainCodec.session(domain)
        if (session.id != pending.sessionId) throw ProtocolException("baseline session mismatch")
        state = state.copy(
            sessions = state.sessions + (
                session.id to SessionView(
                    session = session,
                    cursor = pending.baselineSequence,
                    events = emptyList(),
                    pendingApprovals = emptyMap(),
                )
            ),
        )
        pendingSubscription = pending.copy(sessionReceived = true)
        return finishBaselineIfComplete()
    }

    private fun baselineInteraction(
        context: NativeDeliveryContext.SubscriptionInteraction,
        domain: DomainEnvelope,
    ): List<NativeClientMessage> {
        val pending = pendingSubscription ?: throw ProtocolException("interaction baseline without subscription")
        if (!pending.resultReceived || !pending.sessionReceived || pending.requestId != context.requestId) {
            throw ProtocolException("interaction baseline arrived before its session")
        }
        if (pending.receivedInteractions >= pending.expectedInteractions) {
            throw ProtocolException("too many interaction baseline frames")
        }
        val approval = DomainCodec.approvalRequest(domain).copy(
            interactive = context.route == "interaction_interactive",
        )
        if (approval.sessionId != pending.sessionId) throw ProtocolException("interaction baseline session mismatch")
        val view = state.sessions.getValue(pending.sessionId)
        if (approval.id in view.pendingApprovals) throw ProtocolException("duplicate baseline interaction")
        state = state.copy(
            sessions = state.sessions + (
                pending.sessionId to view.copy(
                    pendingApprovals = view.pendingApprovals + (approval.id to approval),
                )
            ),
        )
        pendingSubscription = pending.copy(receivedInteractions = pending.receivedInteractions + 1)
        return finishBaselineIfComplete()
    }

    private fun finishBaselineIfComplete(): List<NativeClientMessage> {
        val pending = pendingSubscription ?: throw ProtocolException("subscription baseline disappeared")
        if (!pending.sessionReceived || pending.receivedInteractions != pending.expectedInteractions) {
            return emptyList()
        }
        pendingSubscription = null
        return nextSubscription()
    }

    private fun nextSubscription(): List<NativeClientMessage> {
        val sessionId = subscriptionQueue.removeFirstOrNull()
        if (sessionId == null) {
            state = state.copy(phase = NativeState.Phase.LIVE)
            return emptyList()
        }
        val requestId = idFactory()
        pendingSubscription = PendingSubscription(requestId, sessionId)
        return listOf(NativeClientMessage.Subscribe(requestId, sessionId))
    }

    private fun liveEvent(domain: DomainEnvelope, route: String): List<NativeClientMessage> {
        val event = DomainCodec.event(domain)
        val current = state.sessions[event.sessionId] ?: throw ProtocolException("event for unknown session")
        if (event.sequence != current.cursor + 1UL) throw ProtocolException("event sequence gap for ${event.sessionId}")
        val events = (current.events + event).takeLast(maxEventsPerSession)
        var approvals = current.pendingApprovals
        event.approval?.let { approval ->
            if (approval.id in approvals) throw ProtocolException("interaction is already pending")
            approvals = approvals + (
                approval.id to approval.copy(interactive = route == "interaction_interactive")
            )
        }
        event.terminalInteractionId?.let { interactionId ->
            approvals = approvals - interactionId
            approvalSubmissions.entries.removeAll { it.value.interactionId == interactionId }
        }
        if (event.type == "session_ended") {
            approvals = emptyMap()
            approvalSubmissions.entries.removeAll { it.value.sessionId == event.sessionId }
        }
        state = state.copy(
            sessions = state.sessions + (
                event.sessionId to current.copy(
                    cursor = event.sequence,
                    events = events,
                    pendingApprovals = approvals,
                )
            ),
        )
        return emptyList()
    }

    private fun interactionResponseResult(message: NativeServerMessage.InteractionResponseResult): List<NativeClientMessage> {
        val submission = approvalSubmissions.remove(message.requestId)
            ?: throw ProtocolException("interaction response result has no pending request")
        if (submission.sessionId != message.sessionId || submission.interactionId != message.interactionId) {
            throw ProtocolException("interaction response result correlation mismatch")
        }
        return emptyList()
    }

    private fun unsubscriptionResult(message: NativeServerMessage.UnsubscriptionResult): List<NativeClientMessage> {
        if (message.status == "unsubscribed") subscriptions -= message.sessionId
        return emptyList()
    }

    private fun nativeError(message: NativeServerMessage.Error): List<NativeClientMessage> {
        val requestId = message.requestId
        val submission = requestId?.let(approvalSubmissions::remove)
        if (submission != null && message.recoverable) {
            markApprovalFailed(submission, message.message)
            return emptyList()
        }
        throw ProtocolException("${message.code}: ${message.message}")
    }

    private fun markApprovalFailed(submission: ApprovalSubmission, message: String) {
        val view = state.sessions[submission.sessionId]
            ?: throw ProtocolException("approval error targets unknown session")
        val approval = view.pendingApprovals[submission.interactionId] ?: return
        state = state.copy(
            sessions = state.sessions + (
                submission.sessionId to view.copy(
                    pendingApprovals = view.pendingApprovals + (
                        submission.interactionId to approval.copy(
                            submissionState = ApprovalSubmissionState.FAILED,
                            submissionError = message,
                        )
                    ),
                )
            ),
            lastError = message,
        )
    }

    private fun liveSession(domain: DomainEnvelope): List<NativeClientMessage> {
        val session = DomainCodec.session(domain)
        val current = state.sessions[session.id] ?: throw ProtocolException("session update for unknown session")
        state = state.copy(sessions = state.sessions + (session.id to current.copy(session = session)))
        return emptyList()
    }

    private fun requireDiscovery(requestId: String): Discovery {
        val current = discovery ?: throw ProtocolException("discovery frame outside a batch")
        if (!current.started || current.requestId != requestId) throw ProtocolException("mismatched discovery request")
        return current
    }

    private data class Discovery(
        val requestId: String,
        val refresh: Boolean = false,
        val started: Boolean = false,
        val expectedProviders: Int = 0,
        val expectedSessions: Int = 0,
        val providers: Map<String, ProviderSummary> = emptyMap(),
        val sessions: Map<String, SessionView> = emptyMap(),
    )

    private data class PendingSubscription(
        val requestId: String,
        val sessionId: String,
        val resultReceived: Boolean = false,
        val baselineSequence: ULong = 0UL,
        val expectedInteractions: Int = 0,
        val receivedInteractions: Int = 0,
        val sessionReceived: Boolean = false,
    )

    private data class ApprovalSubmission(val sessionId: String, val interactionId: String)
}
