package moe.gensoukyo.agentpulse.ui

import kotlinx.serialization.json.buildJsonObject
import moe.gensoukyo.agentpulse.data.formatHexColor
import moe.gensoukyo.agentpulse.data.parseHexColor
import moe.gensoukyo.agentpulse.protocol.DomainEnvelope
import moe.gensoukyo.agentpulse.protocol.AgentCommandPayload
import moe.gensoukyo.agentpulse.protocol.EventImportance
import moe.gensoukyo.agentpulse.protocol.EventRecord
import moe.gensoukyo.agentpulse.protocol.SessionSnapshot
import moe.gensoukyo.agentpulse.protocol.SessionView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresentationTest {
    @Test
    fun eventsAreDisplayedNewestFirstWithoutChangingInput() {
        val input = listOf(event(1UL), event(3UL), event(2UL))

        assertEquals(listOf(3UL, 2UL, 1UL), eventsNewestFirst(input).map { it.sequence })
        assertEquals(listOf(1UL, 3UL, 2UL), input.map { it.sequence })
    }

    @Test
    fun sessionsAreFilteredAndOrderedByLatestUpdate() {
        val oldRunning = session("old", "running", "2026-09-03T10:00:00Z")
        val latestRunning = session("latest", "running", "2026-09-03T12:00:00Z")
        val waiting = session("waiting", "waiting_for_interaction", "2026-09-03T11:00:00Z")

        assertEquals(
            listOf("latest", "old"),
            filterSessions(listOf(oldRunning, waiting, latestRunning), "", SessionFilter.RUNNING)
                .map { it.session.title },
        )
        assertEquals(
            listOf("waiting"),
            filterSessions(listOf(oldRunning, waiting, latestRunning), "wait", SessionFilter.ALL)
                .map { it.session.title },
        )
    }

    @Test
    fun customHexColorRequiresSixDigitsAndRoundTrips() {
        assertEquals(0xFF5B7BE7.toInt(), parseHexColor("#5b7be7"))
        assertEquals("#5B7BE7", formatHexColor(0xFF5B7BE7.toInt()))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("#ZZZZZZ"))
    }

    @Test
    fun composerParsesMessagesAndTheBoundedSlashCommandSet() {
        assertEquals(AgentCommandPayload.SubmitPrompt("hello"), parseComposerCommand(" hello ", "/workspace"))
        assertEquals(AgentCommandPayload.ListModels, parseComposerCommand("/model", "/workspace"))
        assertEquals(AgentCommandPayload.SelectModel("gpt-5.6", "high"), parseComposerCommand("/model gpt-5.6 high", "/workspace"))
        assertEquals(AgentCommandPayload.SelectModel("gpt-5.6", "high"), parseComposerCommand("/model gpt-5.6 HIGH", "/workspace"))
        assertEquals(AgentCommandPayload.SelectModel("gpt-5.6", "high"), parseComposerCommand("/model\tgpt-5.6\thigh", "/workspace"))
        assertNull(parseComposerCommand("/model gpt-5.6 high unexpected", "/workspace"))
        assertEquals(AgentCommandPayload.ListThreads(), parseComposerCommand("/resume", "/workspace"))
        assertEquals(AgentCommandPayload.StartThread("/workspace"), parseComposerCommand("/clear", "/workspace"))
        assertEquals(AgentCommandPayload.SetPlanMode(false), parseComposerCommand("/plan off", "/workspace"))
        assertEquals(AgentCommandPayload.Cancel, parseComposerCommand("/stop", "/workspace"))
        assertNull(parseComposerCommand("/unknown", "/workspace"))
    }

    private fun session(title: String, state: String, updatedAt: String) = SessionView(
        session = SessionSnapshot(
            id = "0198f142-5a00-7000-8000-${title.hashCode().toUInt().toString().padStart(12, '0').takeLast(12)}",
            providerId = "provider",
            externalId = null,
            title = title,
            workspacePath = "/workspace/$title",
            workspaceName = title,
            state = state,
            connectionState = "connected",
            revision = 1UL,
            createdAt = "2026-09-03T09:00:00Z",
            updatedAt = updatedAt,
            raw = envelope("session"),
        ),
        cursor = 1UL,
        events = emptyList(),
    )

    private fun event(sequence: ULong) = EventRecord(
        id = "0198f142-5a00-7000-8000-${sequence.toString().padStart(12, '0')}",
        sessionId = "0198f142-5a00-7000-8000-000000000001",
        sequence = sequence,
        occurredAt = "2026-09-03T10:00:00Z",
        type = "message",
        title = "Message",
        detail = null,
        importance = EventImportance.NORMAL,
        raw = envelope("event"),
    )

    private fun envelope(type: String) = DomainEnvelope(type, buildJsonObject {})
}
