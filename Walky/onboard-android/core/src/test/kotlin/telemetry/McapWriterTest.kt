package com.vadymsidorov.walky.core.telemetry

import com.vadymsidorov.walky.core.telemetry.mcap.Mcap
import com.vadymsidorov.walky.core.telemetry.mcap.McapFileWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class McapWriterTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII).map { it.toInt() }.toIntArray()
    private fun crc(bytes: ByteArray, from: Int, to: Int) = CRC32().apply { update(bytes, from, to - from) }.value

    @Test
    fun magicIsTheSpecBytes() {
        assertArrayEquals(bytes(0x89, 'M'.code, 'C'.code, 'A'.code, 'P'.code, '0'.code, '\r'.code, '\n'.code), Mcap.MAGIC)
    }

    @Test
    fun messageRecordLayout() {
        val expected = bytes(
            0x05, 25, 0, 0, 0, 0, 0, 0, 0, // opcode, content length 2 + 4 + 8 + 8 + 3
            0x02, 0x01, // channel 0x0102
            7, 0, 0, 0, // sequence
            8, 7, 6, 5, 4, 3, 2, 1, // log time 0x0102030405060708
            1, 0, 0, 0, 0, 0, 0, 0, // publish time
            *ascii("abc"),
        )
        assertArrayEquals(expected, Mcap.message(0x0102, 7, 0x0102030405060708, 1, "abc".toByteArray()))
    }

    @Test
    fun channelRecordLayoutWithItsMetadataMap() {
        val expected = bytes(
            0x04, 32, 0, 0, 0, 0, 0, 0, 0,
            1, 0, // id
            2, 0, // schema id
            2, 0, 0, 0, *ascii("/t"),
            4, 0, 0, 0, *ascii("json"),
            10, 0, 0, 0, // map byte length: two strings of 4 + 1
            1, 0, 0, 0, *ascii("k"), 1, 0, 0, 0, *ascii("v"),
        )
        assertArrayEquals(expected, Mcap.channel(1, 2, "/t", "json", mapOf("k" to "v")))
    }

    @Test
    fun schemaRecordLayout() {
        val expected = bytes(
            0x03, 21, 0, 0, 0, 0, 0, 0, 0,
            3, 0,
            1, 0, 0, 0, *ascii("s"),
            4, 0, 0, 0, *ascii("json"),
            2, 0, 0, 0, *ascii("{}"),
        )
        assertArrayEquals(expected, Mcap.schema(3, "s", "json", "{}".toByteArray()))
    }

    /** Two channels and three messages in two chunks, then the summary. */
    private fun sampleFile(): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = McapFileWriter(out, library = "test", chunkBytes = 60)
        writer.schema(Mcap.schema(1, "s", "jsonschema", "{}".toByteArray()))
        writer.channel(Mcap.channel(1, 1, "/a", "json", emptyMap()))
        writer.channel(Mcap.channel(2, 1, "/b", "json", emptyMap()))
        writer.metadata("session", mapOf("clock" to "boottime"))
        // 41 bytes each, so the second message fills the first 60-byte chunk.
        writer.message(1, 200, Mcap.message(1, 1, 200, 200, "0123456789".toByteArray()))
        writer.message(2, 100, Mcap.message(2, 1, 100, 100, "0123456789".toByteArray()))
        writer.message(1, 300, Mcap.message(1, 2, 300, 300, "0123456789".toByteArray()))
        writer.finish()
        return out.toByteArray()
    }

    @Test
    fun fileStructureFollowsTheSpec() {
        val file = sampleFile()
        val records = records(file)
        val opcodes = records.map { it.second.opcode }
        assertEquals(
            listOf(0x01, 0x03, 0x04, 0x04, 0x0C, 0x06, 0x07, 0x07, 0x06, 0x07, 0x0F) +
                listOf(0x03, 0x04, 0x04, 0x0B, 0x08, 0x08, 0x0D) + List(5) { 0x0E } + 0x02,
            opcodes,
        )
        val header = records.first().second.reader()
        assertEquals("" to "test", header.string() to header.string())

        val (dataEndAt, dataEnd) = records.single { it.second.opcode == 0x0F }
        assertEquals("data section CRC: file start up to Data End", crc(file, 0, dataEndAt.toInt()), dataEnd.reader().u32())

        val (footerAt, footer) = records.last()
        val f = footer.reader()
        val summaryStart = f.u64()
        val summaryOffsetStart = f.u64()
        assertEquals(dataEndAt + 9 + 4, summaryStart)
        assertEquals(0x03, records.single { it.first == summaryStart }.second.opcode)
        assertEquals(0x0E, records.single { it.first == summaryOffsetStart }.second.opcode)
        assertEquals("summary CRC: summary start through summary_offset_start", crc(file, summaryStart.toInt(), footerAt.toInt() + 9 + 16), f.u32())

        val stats = records.single { it.second.opcode == 0x0B }.second.reader()
        assertEquals(3L, stats.u64()) // messages
        assertEquals(1, stats.u16()) // schemas
        assertEquals(2L, stats.u32()) // channels
        assertEquals(0L, stats.u32()) // attachments
        assertEquals(1L, stats.u32()) // metadata
        assertEquals(2L, stats.u32()) // chunks
        assertEquals(100L to 300L, stats.u64() to stats.u64())
        assertEquals(20L, stats.u32())
        assertEquals(mapOf(1 to 2L, 2 to 1L), mapOf(stats.u16() to stats.u64(), stats.u16() to stats.u64()))
    }

    @Test
    fun indexOffsetsPointAtTheRecordsTheyName() {
        val file = sampleFile()
        val records = records(file)
        val at = records.associate { it.first to it.second }

        val chunkIndexes = records.filter { it.second.opcode == 0x08 }.map { it.second.reader() }
        val seen = mutableListOf<Triple<Int, Long, String>>()
        for (index in chunkIndexes) {
            val start = index.u64()
            val end = index.u64()
            val chunkOffset = index.u64()
            val chunkLength = index.u64()
            val chunk = at.getValue(chunkOffset)
            assertEquals(0x06, chunk.opcode)
            assertEquals(9L + chunk.content.size, chunkLength)

            val c = chunk.reader()
            assertEquals(start to end, c.u64() to c.u64())
            val uncompressed = c.u64()
            val chunkCrc = c.u32()
            assertEquals("", c.string())
            val chunkRecords = c.bytes(c.u64().toInt())
            assertEquals(uncompressed, chunkRecords.size.toLong())
            assertEquals(crc(chunkRecords, 0, chunkRecords.size), chunkCrc)

            val indexBytes = index.u32().toInt() / 10
            var indexLength = 0L
            repeat(indexBytes) {
                val channel = index.u16()
                val messageIndex = at.getValue(index.u64())
                assertEquals(0x07, messageIndex.opcode)
                indexLength += 9 + messageIndex.content.size
                val m = messageIndex.reader()
                assertEquals(channel, m.u16())
                repeat(m.u32().toInt() / 16) {
                    val logTime = m.u64()
                    val offset = m.u64().toInt()
                    val inChunk = LittleEndian(chunkRecords.copyOfRange(offset, chunkRecords.size))
                    assertEquals(0x05, inChunk.u8())
                    val message = RawRecord(0x05, inChunk.bytes(inChunk.u64().toInt())).message()
                    assertEquals(channel to logTime, message.channelId to message.logTime)
                    seen += Triple(channel, logTime, message.text)
                }
            }
            assertEquals(indexLength, index.u64())
            assertEquals("", index.string())
            assertEquals(uncompressed to uncompressed, index.u64() to index.u64())
        }
        assertEquals(setOf(Triple(1, 200L, "0123456789"), Triple(2, 100L, "0123456789"), Triple(1, 300L, "0123456789")), seen.toSet())

        val metadataIndex = records.single { it.second.opcode == 0x0D }.second.reader()
        val metadata = at.getValue(metadataIndex.u64())
        assertEquals(0x0C, metadata.opcode)
        assertEquals(9L + metadata.content.size, metadataIndex.u64())
        assertEquals("session", metadataIndex.string())
        val m = metadata.reader()
        assertEquals("session" to mapOf("clock" to "boottime"), m.string() to m.stringMap())

        for (offset in records.filter { it.second.opcode == 0x0E }.map { it.second.reader() }) {
            val opcode = offset.u8()
            val start = offset.u64()
            val length = offset.u64()
            val group = records.filter { it.first >= start && it.first < start + length }
            assertEquals(length, group.sumOf { 9L + it.second.content.size })
            assertEquals(setOf(opcode), group.map { it.second.opcode }.toSet())
        }
    }
}
