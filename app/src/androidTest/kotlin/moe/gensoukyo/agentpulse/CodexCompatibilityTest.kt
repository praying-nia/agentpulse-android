package moe.gensoukyo.agentpulse

import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.gensoukyo.agentpulse.connection.CommandSubmissionPhase
import moe.gensoukyo.agentpulse.connection.ConnectionPhase
import moe.gensoukyo.agentpulse.protocol.AgentCommandPayload
import moe.gensoukyo.agentpulse.protocol.ApprovalSubject
import moe.gensoukyo.agentpulse.protocol.FormAnswer
import moe.gensoukyo.agentpulse.protocol.FormPrompt
import moe.gensoukyo.agentpulse.protocol.SessionView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: real Host/Codex calls through the production foreground service. */
@RunWith(AndroidJUnit4::class)
class CodexCompatibilityTest {
    @Test(timeout = 360_000)
    fun liveCommandApprovalAndStructuredInput(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("agentpulseHostId")
        val session = args.getString("agentpulseSessionId")
        val target = args.getString("agentpulseApprovalTarget")
        assumeTrue("Requires an isolated live approval target", host != null && session != null && target != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var model: MainViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
            suspend fun waitFor(label: String, predicate: () -> Boolean) {
                Log.i("APCodexE2E", "waiting: $label")
                withTimeout(120_000) {
                    while (!predicate()) {
                        check(model.connection.value.error == null) { model.connection.value.error.orEmpty() }
                        delay(50)
                    }
                }
                Log.i("APCodexE2E", "passed: $label")
            }
            fun view(): SessionView = model.connection.value.native.sessions.getValue(requireNotNull(session))
            suspend fun command(payload: AgentCommandPayload) {
                var id: String? = null
                instrumentation.runOnMainSync { id = model.submitCommand(requireNotNull(session), payload) }
                val sent = requireNotNull(id)
                waitFor("command accepted") {
                    check(model.connection.value.commandSubmissions[sent]?.phase != CommandSubmissionPhase.FAILED)
                    model.connection.value.commandSubmissions[sent]?.phase == CommandSubmissionPhase.ACCEPTED
                }
            }
            instrumentation.runOnMainSync { model.connect(requireNotNull(host)) }
            waitFor("approval session connected") { model.connection.value.connection == ConnectionPhase.CONNECTED && session in model.connection.value.native.sessions }
            val before = view().cursor
            command(AgentCommandPayload.SubmitPrompt(
                "Approval acceptance test: call exec_command to execute printf AP_APPROVAL_OK > '$target'. " +
                    "The target is outside the writable workspace. Set sandbox_permissions=require_escalated and " +
                    "ask for approval before running this exact command. Do not use apply_patch or any alternative. " +
                    "After success reply AP_APPROVAL_REPLY_OK.",
            ))
            waitFor("real command approval") { view().pendingApprovals.values.any { it.interactive } }
            val approval = view().pendingApprovals.values.first { it.interactive }
            val subject = approval.subject as ApprovalSubject.Command
            assertTrue("Approval must name only our test target", subject.command.orEmpty().contains(requireNotNull(target)))
            val approveOnce = approval.options.first { it.disposition == "approve" }
            instrumentation.runOnMainSync { model.submitApproval(requireNotNull(session), approval.id, approveOnce.id) }
            waitFor("approved command finished") {
                view().events.any { it.sequence > before && it.messageRole == "assistant" && it.detail.orEmpty().contains("AP_APPROVAL_REPLY_OK") }
            }
            waitFor("approved turn complete") { view().events.any { it.sequence > before && it.type == "session_ended" } }
            command(AgentCommandPayload.SetPlanMode(true))
            val formCursor = view().cursor
            command(AgentCommandPayload.SubmitPrompt(
                "Call request_user_input now with exactly one question, id=ap_choice, header=APChoice, " +
                    "question='Choose the acceptance marker', options AChoice, BChoice, CChoice with brief descriptions. " +
                    "Wait for my answer. If I select CChoice, reply AP_FORM_C_OK. Do not use other tools.",
            ))
            waitFor("real structured input") { view().pendingForms.values.any { f -> f.fields.any { it.header == "APChoice" } } }
            val form = view().pendingForms.values.first { f -> f.fields.any { it.header == "APChoice" } }
            val field = form.fields.single()
            val choice = field.options.first { it.label == "CChoice" }
            instrumentation.runOnMainSync {
                model.submitForm(requireNotNull(session), form.id, mapOf(field.id to FormAnswer.Choice(choice.id)))
            }
            waitFor("structured answer returned to Codex") {
                view().events.any { it.sequence > formCursor && it.messageRole == "assistant" && it.detail.orEmpty().contains("AP_FORM_C_OK") }
            }
            Log.i("APCodexE2E", "approval and user input scenario complete")
        } finally {
            scenario.close()
        }
    }

    @Test(timeout = 600_000)
    fun messagesModelsPlansAndReconnect(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("agentpulseHostId")
        val session = args.getString("agentpulseSessionId")
        assumeTrue("Requires an isolated live Host and Codex thread", host != null && session != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var model: MainViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MainViewModel::class.java] }
            suspend fun waitFor(label: String, timeout: Long = 30_000, predicate: () -> Boolean) {
                Log.i("APCodexE2E", "waiting: $label")
                withTimeout(timeout) {
                    while (!predicate()) {
                        check(model.connection.value.error == null) { model.connection.value.error.orEmpty() }
                        delay(50)
                    }
                }
                Log.i("APCodexE2E", "passed: $label")
            }
            fun view(): SessionView = model.connection.value.native.sessions.getValue(requireNotNull(session))
            suspend fun command(payload: AgentCommandPayload) {
                var id: String? = null
                instrumentation.runOnMainSync { id = model.submitCommand(requireNotNull(session), payload) }
                val sent = requireNotNull(id)
                waitFor("Host accepts ${payload::class.simpleName}") {
                    check(model.connection.value.commandSubmissions[sent]?.phase != CommandSubmissionPhase.FAILED)
                    model.connection.value.commandSubmissions[sent]?.phase == CommandSubmissionPhase.ACCEPTED
                }
            }
            suspend fun choose(form: FormPrompt, label: String) {
                val field = form.fields.single()
                val option = field.options.first { it.label.contains(label) }
                instrumentation.runOnMainSync {
                    model.submitForm(requireNotNull(session), form.id, mapOf(field.id to FormAnswer.Choice(option.id)))
                }
                waitFor("form response resolved") {
                    val current = view().pendingForms[form.id]
                    check(current?.submissionError == null) { current?.submissionError.orEmpty() }
                    current == null
                }
            }
            suspend fun message(marker: String) {
                val before = view().cursor
                command(AgentCommandPayload.SubmitPrompt("Reply with exactly $marker. Do not use tools."))
                waitFor("new assistant reply $marker", 120_000) {
                    view().events.any { it.sequence > before && it.messageRole == "assistant" && it.detail.orEmpty().contains(marker) }
                }
                waitFor("turn complete") { view().events.any { it.sequence > before && it.type == "session_ended" } }
            }
            instrumentation.runOnMainSync { model.connect(requireNotNull(host)) }
            waitFor("connected and session discovered") {
                model.connection.value.connection == ConnectionPhase.CONNECTED && session in model.connection.value.native.sessions
            }
            message("AP_EMULATOR_REPLY_OK")
            command(AgentCommandPayload.ListModels)
            waitFor("model choices") { view().pendingForms.values.any { it.prompt.contains("选择模型") } }
            val models = view().pendingForms.values.first { it.prompt.contains("选择模型") }
            val current = models.fields.single().options.first { it.label.contains("当前") }.label
            choose(models, current)
            waitFor("reasoning choices") { view().pendingForms.values.any { it.id != models.id } }
            val efforts = view().pendingForms.values.first { it.id != models.id }
            val selectionCursor = view().cursor
            choose(efforts, "默认")
            waitFor("model selection applied") { view().events.any { it.sequence > selectionCursor && it.detail.orEmpty().contains("Model set to") } }
            message("AP_MODEL_SELECTION_OK")
            val cursor = view().cursor
            command(AgentCommandPayload.SelectModel("agentpulse-invalid-model"))
            waitFor("invalid model rejected") { view().events.any { it.sequence > cursor && it.detail.orEmpty().contains("unknown model") } }
            message("AP_INVALID_MODEL_RECOVERY_OK")
            command(AgentCommandPayload.SetPlanMode(true))
            val planCursor = view().cursor
            command(AgentCommandPayload.SubmitPrompt(
                "Create a short plan to write acceptance.txt containing AP_PLAN_IMPLEMENTED in this thread's workspace. " +
                    "Do not implement yet. No questions are needed. Return the plan inside <proposed_plan> tags.",
            ))
            waitFor("interactive proposed plan", 120_000) { view().pendingForms.values.any { it.blocking && it.fields.any { f -> f.options.any { it.label.contains("实施") } } } }
            assertTrue(view().events.any { it.sequence > planCursor && it.messageRole == "assistant" })
            val plan = view().pendingForms.values.first { it.blocking }
            choose(plan, "修改")
            command(AgentCommandPayload.SubmitPrompt(
                "Revise the plan: acceptance.txt must contain exactly AP_PLAN_REVISED_OK. Do not implement yet. " +
                    "Return the revised plan inside <proposed_plan> tags.",
            ))
            waitFor("revised plan", 120_000) { view().pendingForms.values.any { it.blocking } }
            val revised = view().pendingForms.values.first { it.blocking }
            val implementationCursor = view().cursor
            choose(revised, "实施")
            waitFor("implementation finished", 120_000) {
                view().events.any { it.sequence > implementationCursor && it.type == "session_ended" }
            }
            instrumentation.runOnMainSync { model.disconnect() }
            waitFor("disconnected") { model.connection.value.connection == ConnectionPhase.DISCONNECTED }
            instrumentation.runOnMainSync { model.connect(requireNotNull(host)) }
            waitFor("reconnected") { model.connection.value.connection == ConnectionPhase.CONNECTED && session in model.connection.value.native.sessions }
            assertEquals(null, model.connection.value.error)
            Log.i("APCodexE2E", "compatibility scenario complete")
        } finally {
            scenario.close()
        }
    }
}
