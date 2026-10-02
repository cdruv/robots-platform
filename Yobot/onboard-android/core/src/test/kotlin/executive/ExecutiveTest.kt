package com.vadymsidorov.yobot.core.executive

import com.vadymsidorov.yobot.core.FakeClock
import com.vadymsidorov.yobot.core.RecordingOutput
import com.vadymsidorov.yobot.core.cognition.Character
import com.vadymsidorov.yobot.core.cognition.Cognition
import com.vadymsidorov.yobot.core.cognition.ConversationMemory
import com.vadymsidorov.yobot.core.events.HeardUtterance
import com.vadymsidorov.yobot.core.events.SpeechFinished
import com.vadymsidorov.yobot.core.events.SpeechStarted
import com.vadymsidorov.yobot.core.inference.Inference
import com.vadymsidorov.yobot.core.inference.InferenceBackend
import com.vadymsidorov.yobot.core.inference.InferenceRequest
import com.vadymsidorov.yobot.core.inference.InferenceResponse
import com.vadymsidorov.yobot.core.output.Expression
import com.vadymsidorov.yobot.core.skills.BuiltinSkills
import com.vadymsidorov.yobot.core.skills.SkillRegistry
import com.vadymsidorov.yobot.core.telemetry.Telemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end through the actor with a scripted backend and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutiveTest {
    private class ScriptedBackend(private val reply: String, private val delayMs: Long = 0) : InferenceBackend {
        override val name = "scripted"
        val requests = mutableListOf<InferenceRequest>()
        override suspend fun complete(request: InferenceRequest): InferenceResponse {
            requests += request
            delay(delayMs)
            return InferenceResponse(reply)
        }
    }

    private class Rig(scope: TestScope, val backend: ScriptedBackend) {
        val out = RecordingOutput()
        val hearing = mutableListOf<Boolean>()
        val memory = ConversationMemory()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val skills = SkillRegistry(BuiltinSkills.all)
        val executive = Executive(
            scope = scope.backgroundScope,
            cognition = Cognition(Character.Yobot, skills, memory),
            inference = Inference(backend, "test/model"),
            output = out.output,
            skills = skills,
            telemetry = Telemetry(emptyList(), scope.backgroundScope, dispatcher = dispatcher),
            hearing = { hearing += it },
            clock = FakeClock(),
            loopDispatcher = dispatcher,
            inferenceDispatcher = dispatcher,
        ).also { it.start() }
    }

    @Test
    fun heardUtteranceRoundTripsToSpeechAndSkills() = runTest {
        val rig = Rig(this, ScriptedBackend("""{"speech":"Hello!","expression":"Happy","actions":[{"skill":"vibrate","args":{"pattern":"double"}}]}"""))
        runCurrent()
        rig.executive.post(HeardUtterance("hi yobot", isFinal = true))
        runCurrent()

        assertEquals("test/model", rig.backend.requests.single().model)
        assertTrue(rig.out.calls.containsAll(listOf("face:Thinking", "say:u1:Hello!", "vibrate:Double", "face:Happy")))
        assertEquals(1, rig.memory.size)

        rig.executive.post(SpeechStarted("u1"))
        rig.executive.post(SpeechFinished("u1"))
        runCurrent()
        assertEquals(listOf(true, false, true), rig.hearing)
        assertEquals(Expression.Happy, rig.executive.state.value.expression)
    }

    @Test
    fun slowInferenceTimesOutAsConfused() = runTest {
        val rig = Rig(this, ScriptedBackend("{}", delayMs = 60_000))
        runCurrent()
        rig.executive.post(HeardUtterance("hi", isFinal = true))
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(null, rig.executive.state.value.thinking)
        assertEquals(Expression.Confused, rig.out.face?.expression)
        assertEquals(0, rig.memory.size)
    }
}
