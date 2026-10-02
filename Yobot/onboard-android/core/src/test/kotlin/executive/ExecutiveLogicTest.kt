package com.vadymsidorov.yobot.core.executive

import com.vadymsidorov.yobot.core.cognition.Intent
import com.vadymsidorov.yobot.core.events.ExecutiveEvent
import com.vadymsidorov.yobot.core.events.HeardUtterance
import com.vadymsidorov.yobot.core.events.Heartbeat
import com.vadymsidorov.yobot.core.events.Motion
import com.vadymsidorov.yobot.core.events.MotionKind
import com.vadymsidorov.yobot.core.events.SayText
import com.vadymsidorov.yobot.core.events.SpeechFailed
import com.vadymsidorov.yobot.core.events.SpeechFinished
import com.vadymsidorov.yobot.core.events.SpeechStarted
import com.vadymsidorov.yobot.core.events.ThoughtResult
import com.vadymsidorov.yobot.core.events.Touch
import com.vadymsidorov.yobot.core.events.TouchKind
import com.vadymsidorov.yobot.core.events.TouchRegion
import com.vadymsidorov.yobot.core.output.Expression
import com.vadymsidorov.yobot.core.output.HapticPattern
import com.vadymsidorov.yobot.core.skills.SkillCall
import com.vadymsidorov.yobot.core.state.RobotState
import com.vadymsidorov.yobot.core.state.ThinkTrigger
import com.vadymsidorov.yobot.core.state.TriggerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutiveLogicTest {
    private val config = ExecutiveConfig()
    private val logic = ExecutiveLogic(config)
    private val t0 = 10_000_000L
    private val idle = RobotState(lastThinkAtMs = t0)

    private fun RobotState.on(event: ExecutiveEvent, now: Long = t0) = logic.step(this, event, now)
    private fun Step.thinks() = effects.filterIsInstance<Effect.Think>()

    @Test
    fun finalUtteranceRecordsAndThinks() {
        val step = idle.on(HeardUtterance("hello robot", isFinal = true))
        assertEquals(listOf(Effect.Think(1, ThinkTrigger(TriggerKind.Heard, "hello robot"))), step.thinks())
        assertEquals(1L, step.state.thinking)
        assertEquals("hello robot", step.state.recentUtterances.single().text)
        assertEquals(Expression.Thinking, step.effects.filterIsInstance<Effect.SetFace>().single().face.expression)
    }

    @Test
    fun partialUtteranceDoesNotThink() {
        val step = idle.on(HeardUtterance("hel", isFinal = false))
        assertTrue(step.thinks().isEmpty())
        assertTrue(step.state.recentUtterances.isEmpty())
    }

    @Test
    fun thinkWhileThinkingCoalescesLatestTriggerWins() {
        val thinking = idle.on(HeardUtterance("one", isFinal = true)).state
        val a = thinking.on(HeardUtterance("two", isFinal = true), t0 + 10)
        val b = a.state.on(HeardUtterance("three", isFinal = true), t0 + 20)
        assertTrue(a.thinks().isEmpty() && b.thinks().isEmpty())
        assertEquals(ThinkTrigger(TriggerKind.Heard, "three"), b.state.pendingThink)

        val done = b.state.on(ThoughtResult(1, Intent(speech = "hi")), t0 + 30)
        assertEquals(listOf(Effect.Think(2, ThinkTrigger(TriggerKind.Heard, "three"))), done.thinks())
        assertNull(done.state.pendingThink)
        assertEquals(2L, done.state.thinking)
    }

    @Test
    fun staleResultIsIgnored() {
        val thinking = idle.on(HeardUtterance("one", isFinal = true)).state
        val step = thinking.on(ThoughtResult(99, Intent(speech = "stale")))
        assertEquals(thinking, step.state)
        assertTrue(step.effects.none { it is Effect.Say })
        assertEquals("thought.stale", (step.effects.single() as Effect.Log).kind)
    }

    @Test
    fun successfulResultSpeaksSetsExpressionRunsSkillsAndRemembers() {
        val thinking = idle.on(HeardUtterance("one", isFinal = true)).state
        val call = SkillCall("vibrate")
        val intent = Intent(speech = "hi there", expression = Expression.Happy, actions = listOf(call))
        val step = thinking.on(ThoughtResult(1, intent))
        assertTrue(Effect.Say("u1", "hi there") in step.effects)
        assertTrue(Effect.RunSkill(call) in step.effects)
        assertTrue(Effect.Remember(1, intent) in step.effects)
        assertEquals(Expression.Happy, step.state.expression)
        assertEquals(Expression.Happy, step.effects.filterIsInstance<Effect.SetFace>().single().face.expression)
        assertNull(step.state.thinking)
    }

    @Test
    fun failedResultLooksConfusedAndLogs() {
        val thinking = idle.on(HeardUtterance("one", isFinal = true)).state
        val step = thinking.on(ThoughtResult(1, error = "boom"))
        assertEquals(Expression.Confused, step.state.reaction)
        assertEquals("thought.failed", step.effects.filterIsInstance<Effect.Log>().single().kind)
        assertTrue(step.effects.none { it is Effect.Remember })
    }

    @Test
    fun hearingIsMutedWhileSpeaking() {
        val started = idle.on(SpeechStarted("u1"))
        assertTrue(Effect.SetHearing(false) in started.effects)
        assertFalse(started.state.hearingEnabled)
        assertTrue(started.effects.filterIsInstance<Effect.SetFace>().single().face.speaking)

        val other = started.state.on(SpeechFinished("u0"))
        assertTrue(other.effects.none { it is Effect.SetHearing })

        val finished = started.state.on(SpeechFinished("u1"))
        assertTrue(Effect.SetHearing(true) in finished.effects)
        assertTrue(finished.state.hearingEnabled)
        assertNull(finished.state.speaking)

        val failed = started.state.on(SpeechFailed("u1", "tts error"))
        assertTrue(Effect.SetHearing(true) in failed.effects)
    }

    @Test
    fun idleThinkTriggersOnlyAfterIntervalAndWhenQuiet() {
        val before = idle.on(Heartbeat(0), t0 + config.idleIntervalMs - 1)
        assertTrue(before.thinks().isEmpty())

        val due = idle.on(Heartbeat(0), t0 + config.idleIntervalMs)
        assertEquals(TriggerKind.Idle, due.thinks().single().trigger.kind)

        val speaking = idle.copy(speaking = "u1").on(Heartbeat(0), t0 + config.idleIntervalMs * 2)
        assertTrue(speaking.thinks().isEmpty())
    }

    @Test
    fun touchReactsImmediatelyAndThinksAtMostEveryFiveSeconds() {
        val tap = Touch(TouchKind.Tap, 0.3f, 0.4f, TouchRegion.LeftEye)
        val first = idle.on(tap)
        assertEquals(Expression.Surprised, first.state.reaction)
        assertTrue(Effect.Vibrate(HapticPattern.Short) in first.effects)
        assertEquals(TriggerKind.Touched, first.thinks().single().trigger.kind)

        val result = first.state.on(ThoughtResult(1, Intent()), t0 + 100).state
        val second = result.on(tap, t0 + config.touchThinkIntervalMs - 1)
        assertTrue(Effect.Vibrate(HapticPattern.Short) in second.effects)
        assertTrue(second.thinks().isEmpty())

        val third = second.state.on(tap, t0 + config.touchThinkIntervalMs)
        assertEquals(1, third.thinks().size)
    }

    @Test
    fun shakeIsDizzyAndThinksAtMostEveryFifteenSeconds() {
        val shake = Motion(MotionKind.Shaken, 0f, 0f, 20f)
        val first = idle.on(shake)
        assertEquals(Expression.Dizzy, first.state.reaction)
        assertEquals(TriggerKind.Moved, first.thinks().single().trigger.kind)

        val result = first.state.on(ThoughtResult(1, Intent()), t0 + 100).state
        assertTrue(result.on(shake, t0 + config.motionThinkIntervalMs - 1).thinks().isEmpty())
        assertEquals(1, result.on(shake, t0 + config.motionThinkIntervalMs).thinks().size)
    }

    @Test
    fun stillAndTiltedOnlyUpdateStateAndGaze() {
        val tilted = idle.on(Motion(MotionKind.Tilted, pitch = 20f, roll = -30f, magnitude = 0.1f))
        assertTrue(tilted.thinks().isEmpty())
        assertTrue(tilted.state.gaze.x > 0f && tilted.state.gaze.y > 0f)
        val still = tilted.state.on(Motion(MotionKind.Still, 0f, 0f, 0f))
        assertEquals(0f, still.state.gaze.x)
        assertNull(still.state.reaction)
    }

    @Test
    fun reactionExpiresOnLaterEvent() {
        val reacted = idle.on(Touch(TouchKind.Stroke, 0.5f, 0.2f, TouchRegion.Forehead)).state
        assertEquals(Expression.Happy, reacted.reaction)
        val later = reacted.on(Heartbeat(0), t0 + config.reactionMs)
        assertNull(later.state.reaction)
    }

    @Test
    fun lostThoughtIsAbandonedAfterTimeout() {
        val thinking = idle.on(HeardUtterance("one", isFinal = true)).state
        assertEquals(1L, thinking.on(Heartbeat(0), t0 + config.thinkTimeoutMs).state.thinking)
        val abandoned = thinking.on(Heartbeat(0), t0 + config.thinkTimeoutMs + 1)
        assertNull(abandoned.state.thinking)
        assertEquals("thought.abandoned", abandoned.effects.filterIsInstance<Effect.Log>().single().kind)
    }

    @Test
    fun sayCommandAssignsSequentialUtteranceIds() {
        val one = idle.on(SayText("a"))
        val two = one.state.on(SayText("b"))
        assertEquals(Effect.Say("u1", "a"), one.effects.single())
        assertEquals(Effect.Say("u2", "b"), two.effects.single())
    }
}
