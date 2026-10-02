package com.vadymsidorov.yobot.core.cognition

import com.vadymsidorov.yobot.core.output.Expression
import com.vadymsidorov.yobot.core.skills.BuiltinSkills
import com.vadymsidorov.yobot.core.skills.SkillRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseParserTest {
    private val parser = ResponseParser(SkillRegistry(BuiltinSkills.all))

    @Test
    fun cleanJson() {
        val intent = parser.parse(
            """{"speech":"Hi!","expression":"happy","actions":[{"skill":"vibrate","args":{"pattern":"short"}}],"thought":"greet"}""",
        )
        assertEquals("Hi!", intent.speech)
        assertEquals(Expression.Happy, intent.expression)
        assertEquals("vibrate", intent.actions.single().name)
        assertEquals("greet", intent.thought)
    }

    @Test
    fun fencedJson() {
        val intent = parser.parse("```json\n{\"speech\": \"Hello\", \"expression\": \"Curious\"}\n```")
        assertEquals("Hello", intent.speech)
        assertEquals(Expression.Curious, intent.expression)
    }

    @Test
    fun plainTextBecomesSpeech() {
        val intent = parser.parse("Oh hello there!")
        assertEquals("Oh hello there!", intent.speech)
        assertEquals(Expression.Neutral, intent.expression)
    }

    @Test
    fun missingFieldsAndUnknownExpressionAreTolerated() {
        val intent = parser.parse("""{"speech": null, "expression": "ecstatic"}""")
        assertNull(intent.speech)
        assertEquals(Expression.Neutral, intent.expression)
        assertEquals(emptyList<Any>(), intent.actions)
    }

    @Test
    fun unknownSkillAndInvalidArgsAreDropped() {
        val intent = parser.parse(
            """{"actions":[
                {"skill":"walk","args":{"steps":3}},
                {"skill":"play_sound","args":{"name":"explosion"}},
                {"skill":"look"},
                {"skill":"look","args":{"direction":"left"}}
            ]}""",
        )
        assertEquals(listOf("look"), intent.actions.map { it.name })
        assertEquals("left", intent.actions.single().args["direction"].toString().trim('"'))
    }
}
