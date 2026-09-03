package moe.gensoukyo.agentpulse.connection

import moe.gensoukyo.agentpulse.protocol.NativeServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandSubmissionTrackerTest {
    @Test
    fun acceptsOnlyTheCorrelatedHostConfirmation() {
        val tracker = CommandSubmissionTracker()
        assertEquals(
            CommandSubmission(COMMAND_ID, SESSION_ID, CommandSubmissionPhase.SENDING),
            tracker.begin(REQUEST_ID, COMMAND_ID, SESSION_ID),
        )
        assertThrows(IllegalStateException::class.java) {
            tracker.complete(NativeServerMessage.CommandResult(REQUEST_ID, SESSION_ID, OTHER_ID))
        }
        assertEquals(
            CommandSubmission(COMMAND_ID, SESSION_ID, CommandSubmissionPhase.ACCEPTED),
            tracker.complete(NativeServerMessage.CommandResult(REQUEST_ID, SESSION_ID, COMMAND_ID)),
        )
        assertNull(tracker.complete(NativeServerMessage.CommandResult(REQUEST_ID, SESSION_ID, COMMAND_ID)))
    }

    @Test
    fun recoverableErrorAndDisconnectFailWithoutLosingTheCommandIdentity() {
        val tracker = CommandSubmissionTracker()
        tracker.begin(REQUEST_ID, COMMAND_ID, SESSION_ID)
        assertEquals(
            CommandSubmission(COMMAND_ID, SESSION_ID, CommandSubmissionPhase.FAILED, "rejected"),
            tracker.complete(NativeServerMessage.Error(REQUEST_ID, "provider_rejected", "rejected", true)),
        )

        tracker.begin(OTHER_ID, OTHER_ID, SESSION_ID)
        assertEquals(
            listOf(CommandSubmission(OTHER_ID, SESSION_ID, CommandSubmissionPhase.FAILED, "disconnected")),
            tracker.failAll("disconnected"),
        )
    }

    private companion object {
        const val REQUEST_ID = "01890f47-7c00-7000-8000-000000000001"
        const val COMMAND_ID = "01890f47-7c00-7000-8000-000000000002"
        const val SESSION_ID = "01890f47-7c00-7000-8000-000000000003"
        const val OTHER_ID = "01890f47-7c00-7000-8000-000000000004"
    }
}
