package moe.gensoukyo.agentpulse.protocol

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun nativeGoldenMessagesDecodeAndEncode() {
        val hello = NativeCodec.decode(SERVER_HELLO)
        assertTrue(hello is NativeServerMessage.Hello)
        hello as NativeServerMessage.Hello
        assertEquals(CONNECTION_ID, hello.connectionId)
        assertEquals(1, hello.protocolVersion)
        assertEquals("Native Local", DomainCodec.providerLikeDisplayName(hello.channel))

        val encoded = NativeCodec.encode(
            NativeClientMessage.Hello(
                clientId = CLIENT_ID,
                displayName = "Fixture Native Client",
                version = "1.0.0",
            ),
        )
        assertTrue(encoded.contains("\"type\":\"client_hello\""))
        assertTrue(encoded.contains("\"client_id\":\"$CLIENT_ID\""))

        assertThrows(ProtocolException::class.java) {
            NativeCodec.decode(SERVER_HELLO.replace("\"server_hello\"", "\"server_hello\",\"extra\":true"))
        }
    }

    @Test
    fun pairingBundleAndServerSuccessAreStrict() {
        val json = """{"pairing_version":1,"pairing_id":"$PAIRING_ID","host_id":"$HOST_ID","host_name":"Studio Host","server_name":"$HOST_ID.agentpulse.local","address":"127.0.0.1","port":49321,"leaf_sha256":"${"ab".repeat(32)}","bootstrap_token":"bootstrap-secret","relay_endpoint":"relay.example.com:2333","expires_at_unix_seconds":4102444800}"""
        val uri = "agentpulse://pair/v1/" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToByteArray())
        val bundle = PairingCodec.decodeUri(uri, nowUnixSeconds = 1_800_000_000)
        assertEquals(PAIRING_ID, bundle.pairingId)
        assertEquals(49_321, bundle.port)
        assertEquals("relay.example.com:2333", bundle.relayEndpoint)

        val success = PairingCodec.decodeServer(PAIRING_SUCCEEDED)
        assertTrue(success is PairingServerMessage.Succeeded)
        success as PairingServerMessage.Succeeded
        assertEquals(HOST_ID, success.hostId)
        assertEquals(listOf(1), success.domainProtocolVersions)

        val unknown = json.dropLast(1) + ",\"extra\":true}"
        val invalidUri = "agentpulse://pair/v1/" + Base64.getUrlEncoder().withoutPadding().encodeToString(unknown.encodeToByteArray())
        assertThrows(ProtocolException::class.java) {
            PairingCodec.decodeUri(invalidUri, nowUnixSeconds = 1_800_000_000)
        }
        assertThrows(ProtocolException::class.java) {
            PairingCodec.decodeUri(uri, nowUnixSeconds = 4_102_444_800)
        }
        val invalidRelay = json.replace("relay.example.com:2333", "https://relay.example.com:2333")
        val invalidRelayUri = "agentpulse://pair/v1/" + Base64.getUrlEncoder().withoutPadding().encodeToString(invalidRelay.encodeToByteArray())
        assertThrows(ProtocolException::class.java) {
            PairingCodec.decodeUri(invalidRelayUri, nowUnixSeconds = 1_800_000_000)
        }
    }

    @Test
    fun reducerCompletesDiscoverySubscriptionAndLiveDelivery() {
        val ids = ArrayDeque(listOf(DISCOVER_ID, SUBSCRIBE_ID))
        val reducer = NativeSessionReducer(maxEventsPerSession = 2) { ids.removeFirst() }

        assertEquals(listOf(NativeClientMessage.Discover(DISCOVER_ID)), reducer.accept(NativeCodec.decode(SERVER_HELLO)))
        assertTrue(reducer.accept(NativeServerMessage.SyncStarted(DISCOVER_ID, 0, 1)).isEmpty())
        assertTrue(
            reducer.accept(
                NativeServerMessage.Domain(
                    NativeDeliveryContext.DiscoverySession(DISCOVER_ID, 7UL),
                    DomainCodec.decode(SESSION_DOMAIN),
                ),
            ).isEmpty(),
        )
        assertEquals(
            listOf(NativeClientMessage.Subscribe(SUBSCRIBE_ID, SESSION_ID)),
            reducer.accept(NativeServerMessage.SyncCompleted(DISCOVER_ID)),
        )
        assertTrue(
            reducer.accept(
                NativeServerMessage.SubscriptionResult(SUBSCRIBE_ID, SESSION_ID, "subscribed", 7UL),
            ).isEmpty(),
        )
        assertTrue(
            reducer.accept(
                NativeServerMessage.Domain(
                    NativeDeliveryContext.SubscriptionSession(SUBSCRIBE_ID),
                    DomainCodec.decode(SESSION_DOMAIN),
                ),
            ).isEmpty(),
        )
        assertEquals(NativeState.Phase.LIVE, reducer.state.phase)

        reducer.accept(
            NativeServerMessage.Domain(
                NativeDeliveryContext.LiveEvent("observe_only"),
                DomainCodec.decode(EVENT_DOMAIN),
            ),
        )
        val session = reducer.state.sessions.getValue(SESSION_ID)
        assertEquals(8UL, session.cursor)
        assertEquals("Message · warning", session.events.single().title)
        assertEquals(EventImportance.WARNING, session.events.single().importance)
    }

    @Test
    fun reducerFailsClosedOnSequenceGap() {
        val ids = ArrayDeque(listOf(DISCOVER_ID, SUBSCRIBE_ID))
        val reducer = NativeSessionReducer { ids.removeFirst() }
        reducer.accept(NativeCodec.decode(SERVER_HELLO))
        reducer.accept(NativeServerMessage.SyncStarted(DISCOVER_ID, 0, 1))
        reducer.accept(NativeServerMessage.Domain(NativeDeliveryContext.DiscoverySession(DISCOVER_ID, 7UL), DomainCodec.decode(SESSION_DOMAIN)))
        reducer.accept(NativeServerMessage.SyncCompleted(DISCOVER_ID))
        reducer.accept(NativeServerMessage.SubscriptionResult(SUBSCRIBE_ID, SESSION_ID, "subscribed", 7UL))
        reducer.accept(NativeServerMessage.Domain(NativeDeliveryContext.SubscriptionSession(SUBSCRIBE_ID), DomainCodec.decode(SESSION_DOMAIN)))

        val gap = EVENT_DOMAIN.replace("\"sequence\":\"8\"", "\"sequence\":\"9\"")
        assertThrows(ProtocolException::class.java) {
            reducer.accept(NativeServerMessage.Domain(NativeDeliveryContext.LiveEvent("observe_only"), DomainCodec.decode(gap)))
        }
        assertEquals(NativeState.Phase.FAILED, reducer.state.phase)
    }

    private companion object {
        const val CLIENT_ID = "01890f47-7c00-7000-8000-000000000004"
        const val CONNECTION_ID = "01890f47-7c00-7000-8000-000000000006"
        const val DISCOVER_ID = "01890f47-7c00-7000-8000-000000000005"
        const val SUBSCRIBE_ID = "01890f47-7c00-7000-8000-000000000007"
        const val SESSION_ID = "01890f47-7c00-7000-8000-000000000003"
        const val PAIRING_ID = "0198f142-5a00-7000-8000-000000000001"
        const val HOST_ID = "0198f142-5a00-7000-8000-000000000002"

        val SERVER_HELLO = """
            {"native_transport_version":1,"message":{"type":"server_hello","connection_id":"$CONNECTION_ID","channel":{"protocol_version":1,"message":{"type":"channel_descriptor","payload":{"id":"01890f47-7c00-7000-8000-000000000002","kind":"native","display_name":"Native Local","version":"0.1.0","capabilities":["notification","session_view","realtime_sync"]}}},"protocol_version":1,"max_frame_bytes":1048576,"ping_interval_seconds":15,"idle_timeout_seconds":45}}
        """.trimIndent()

        val SESSION_DOMAIN = """
            {"protocol_version":1,"message":{"type":"agent_session","payload":{"id":"$SESSION_ID","provider_id":"01890f47-7c00-7000-8000-000000000001","external_id":"codex-session-42","title":"Implement JSON protocol","workspace":{"path":"/workspace/agentpulse","display_name":"AgentPulse"},"state":"running","connection_state":"connected","revision":"3","created_at":"2026-08-29T00:00:00Z","updated_at":"2026-08-29T00:02:00Z"}}}
        """.trimIndent()

        val EVENT_DOMAIN = """
            {"protocol_version":1,"message":{"type":"agent_event","payload":{"id":"01890f47-7c00-7000-8000-000000000008","session_id":"$SESSION_ID","sequence":"8","occurred_at":"2026-08-29T00:03:00Z","payload":{"type":"message","message":{"level":"warning","content":"Approval is still pending"}}}}}
        """.trimIndent()

        val PAIRING_SUCCEEDED = """
            {"pairing_version":1,"message":{"type":"pairing_succeeded","host_id":"$HOST_ID","host_name":"Studio Host","ca_certificate_der":"base64-ca","server_name":"$HOST_ID.agentpulse.local","native_address":"192.168.50.4","native_port":49320,"access_token":"device-secret","native_transport_version":1,"domain_protocol_versions":[1]}}
        """.trimIndent()
    }
}

private fun DomainCodec.providerLikeDisplayName(channel: DomainEnvelope): String =
    channel.payload["display_name"]?.toString()?.trim('"') ?: error("display name missing")
