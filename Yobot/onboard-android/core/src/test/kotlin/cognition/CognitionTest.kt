package com.vadymsidorov.yobot.core.cognition

import com.vadymsidorov.yobot.core.skills.BuiltinSkills
import com.vadymsidorov.yobot.core.skills.SkillRegistry
import com.vadymsidorov.yobot.core.state.RobotState
import com.vadymsidorov.yobot.core.state.ThinkTrigger
import com.vadymsidorov.yobot.core.state.TimedUtterance
import com.vadymsidorov.yobot.core.state.TriggerKind
import com.vadymsidorov.yobot.core.telemetry.YobotJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class CognitionTest {
    private val skills = SkillRegistry(BuiltinSkills.all)

    @Test
    fun systemPromptContainsPersonaSkillCatalogueAndFormat() {
        val prompt = Cognition(Character.Yobot, skills).systemPrompt
        assertTrue(prompt.contains("You are Yobot"))
        listOf("vibrate", "play_sound", "look", "\"enum\"", "\"expression\"").forEach {
            assertTrue("prompt should contain $it", prompt.contains(it))
        }
    }

    @Test
    fun situationIsTheLastUserMessage() {
        val now = 1_000_000L
        val state = RobotState(recentUtterances = listOf(TimedUtterance("hi yobot", now - 3_000)))
        val request = Cognition(Character.Yobot, skills, zone = ZoneOffset.UTC)
            .compose(state, ThinkTrigger(TriggerKind.Heard, "hi yobot"), now)

        assertEquals(listOf("system", "user"), request.messages.map { it.role })
        val situation = YobotJson.decodeFromString(Situation.serializer(), request.messages.last().content)
        assertEquals("Heard", situation.trigger)
        assertEquals(Situation.Heard("hi yobot", 3), situation.heard.single())
    }

    @Test
    fun memoryIsBounded() {
        val memory = ConversationMemory(maxTurns = 3)
        val cognition = Cognition(Character.Yobot, skills, memory)
        repeat(5) { cognition.remember("situation $it", Intent(speech = "reply $it")) }

        val request = cognition.compose(RobotState(), ThinkTrigger(TriggerKind.Idle), 0)
        assertEquals(1 + 3 * 2 + 1, request.messages.size)
        assertTrue(request.messages[1].content == "situation 2")
        assertTrue(request.messages[2].content.contains("reply 2"))
    }
}
