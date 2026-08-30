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
    private var pendingSubscription: PendingSubscription? = null

    fun accept(message: NativeServerMessage): List<NativeClientMessage> = try {
        when (message) {
            is NativeServerMessage.Hello -> hello(message)
            is NativeServerMessage.SyncStarted -> syncStarted(message)
            is NativeServerMessage.Domain -> domain(message)
            is NativeServerMessage.SyncCompleted -> syncCompleted(message)
            is NativeServerMessage.SubscriptionResult -> subscriptionResult(message)
            is NativeServerMessage.UnsubscriptionResult -> emptyList()
            is NativeServerMessage.Error -> throw ProtocolException("${message.code}: ${message.message}")
        }
    } catch (error: ProtocolException) {
        state = state.copy(phase = NativeState.Phase.FAILED, lastError = error.message)
        throw error
    }

    private fun hello(message: NativeServerMessage.Hello): List<NativeClientMessage> {
        if (state.phase != NativeState.Phase.AWAITING_HELLO) throw ProtocolException("unexpected server hello")
        if (message.protocolVersion != DOMAIN_PROTOCOL_VERSION) throw ProtocolException("unsupported selected protocol")
        val requestId = idFactory()
        state = state.copy(phase = NativeState.Phase.DISCOVERING, lastError = null)
        discovery = Discovery(requestId)
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
        is NativeDeliveryContext.SubscriptionSession -> baseline(context, message.domain)
        is NativeDeliveryContext.LiveEvent -> liveEvent(message.domain)
        NativeDeliveryContext.LiveSession -> liveSession(message.domain)
    }

    private fun syncCompleted(message: NativeServerMessage.SyncCompleted): List<NativeClientMessage> {
        val current = requireDiscovery(message.requestId)
        if (current.providers.size != current.expectedProviders || current.sessions.size != current.expectedSessions) throw ProtocolException("incomplete discovery batch")
        state = state.copy(providers = current.providers, sessions = current.sessions, phase = if (current.sessions.isEmpty()) NativeState.Phase.LIVE else NativeState.Phase.SUBSCRIBING)
        discovery = null
        subscriptionQueue.clear()
        subscriptionQueue.addAll(state.sessions.keys.sorted())
        return nextSubscription()
    }

    private fun subscriptionResult(message: NativeServerMessage.SubscriptionResult): List<NativeClientMessage> {
        val pending = pendingSubscription ?: throw ProtocolException("subscription result without request")
        if (pending.requestId != message.requestId || pending.sessionId != message.sessionId) throw ProtocolException("mismatched subscription result")
        val discovered = state.sessions[message.sessionId] ?: throw ProtocolException("subscription targets unknown session")
        if (discovered.cursor != message.baselineSequence) throw ProtocolException("baseline cursor changed after discovery")
        pendingSubscription = pending.copy(resultReceived = true, baselineSequence = message.baselineSequence)
        return emptyList()
    }

    private fun baseline(context: NativeDeliveryContext.SubscriptionSession, domain: DomainEnvelope): List<NativeClientMessage> {
        val pending = pendingSubscription ?: throw ProtocolException("baseline without subscription")
        if (!pending.resultReceived || pending.requestId != context.requestId) throw ProtocolException("baseline arrived before matching result")
        val session = DomainCodec.session(domain)
        if (session.id != pending.sessionId) throw ProtocolException("baseline session mismatch")
        state = state.copy(sessions = state.sessions + (session.id to SessionView(session, pending.baselineSequence, emptyList())))
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

    private fun liveEvent(domain: DomainEnvelope): List<NativeClientMessage> {
        val event = DomainCodec.event(domain)
        val current = state.sessions[event.sessionId] ?: throw ProtocolException("event for unknown session")
        if (event.sequence != current.cursor + 1UL) throw ProtocolException("event sequence gap for ${event.sessionId}")
        val events = (current.events + event).takeLast(maxEventsPerSession)
        state = state.copy(sessions = state.sessions + (event.sessionId to current.copy(cursor = event.sequence, events = events)))
        return emptyList()
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
    )
}
