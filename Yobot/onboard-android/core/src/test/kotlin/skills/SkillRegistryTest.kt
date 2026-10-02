package com.vadymsidorov.yobot.core.skills

import com.vadymsidorov.yobot.core.RecordingOutput
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRegistryTest {
    private val registry = SkillRegistry(BuiltinSkills.all)
    private fun call(name: String, vararg args: Pair<String, String>) =
        SkillCall(name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

    @Test
    fun validatesNamesRequiredParamsAndEnums() {
        assertNull(registry.validate(call("vibrate", "pattern" to "double")))
        assertNotNull(registry.validate(call("dance")))
        assertNotNull(registry.validate(call("vibrate")))
        assertNotNull(registry.validate(call("vibrate", "pattern" to "forever")))
    }

    @Test
    fun executesAgainstOutput() {
        val out = RecordingOutput()
        assertTrue(registry.execute(call("vibrate", "pattern" to "long"), out.output).isSuccess)
        assertTrue(registry.execute(call("play_sound", "name" to "purr"), out.output).isSuccess)
        assertTrue(registry.execute(call("look", "direction" to "up"), out.output).isSuccess)
        assertTrue(registry.execute(call("look", "direction" to "sideways"), out.output).isFailure)
        assertEquals(listOf("vibrate:Long", "sound:Purr", "glance:0.0,-1.0"), out.calls)
    }

    @Test
    fun catalogueListsEverySkill() {
        val names = registry.catalogue().map { (it as kotlinx.serialization.json.JsonObject)["name"] }
        assertEquals(BuiltinSkills.all.map { JsonPrimitive(it.name) }, names)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateNamesRejected() {
        SkillRegistry(listOf(BuiltinSkills.look, BuiltinSkills.look))
    }
}
