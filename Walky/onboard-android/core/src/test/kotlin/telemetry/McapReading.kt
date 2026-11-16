package com.vadymsidorov.walky.core.telemetry

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Test-side MCAP parsing, written from the spec (https://mcap.dev/spec), not from the writer. */
val MCAP_MAGIC = byteArrayOf(0x89.toByte(), 0x4D, 0x43, 0x41, 0x50, 0x30, 0x0D, 0x0A)

class RawRecord(val opcode: Int, val content: ByteArray) {
    fun reader() = LittleEndian(content)
}

class LittleEndian(bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val remaining get() = buffer.remaining()
    fun u8() = buffer.get().toInt() and 0xFF
    fun u16() = buffer.short.toInt() and 0xFFFF
    fun u32() = buffer.int.toLong() and 0xFFFF_FFFFL
    fun u64() = buffer.long
    fun bytes(n: Int) = ByteArray(n).also { buffer.get(it) }
    fun string() = String(bytes(u32().toInt()), Charsets.UTF_8)
    fun rest() = bytes(buffer.remaining())
    fun stringMap(): Map<String, String> {
        val end = buffer.position() + u32().toInt()
        val map = LinkedHashMap<String, String>()
        while (buffer.position() < end) map[string()] = string()
        return map
    }
}

data class Message(val channelId: Int, val sequence: Long, val logTime: Long, val publishTime: Long, val data: ByteArray) {
    val text get() = String(data, Charsets.UTF_8)
}

fun RawRecord.message(): Message {
    check(opcode == 0x05) { "opcode $opcode is not a message" }
    val r = reader()
    return Message(r.u16(), r.u32(), r.u64(), r.u64(), r.rest())
}

/** Reads records from a stream that starts with the magic. */
class McapStream(input: InputStream) {
    private val input = DataInputStream(input)

    init {
        val magic = ByteArray(8)
        this.input.readFully(magic)
        check(magic.contentEquals(MCAP_MAGIC)) { "no MCAP magic: ${magic.toList()}" }
    }

    fun next(): RawRecord {
        val opcode = input.readUnsignedByte()
        val header = ByteArray(8)
        input.readFully(header)
        val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).long
        val content = ByteArray(length.toInt())
        input.readFully(content)
        return RawRecord(opcode, content)
    }

    fun nextOrNull(): RawRecord? = try {
        next()
    } catch (_: EOFException) {
        null
    }
}

/** Splits a whole file into its records; the leading and trailing magic are checked. */
fun records(file: ByteArray): List<Pair<Long, RawRecord>> {
    check(file.copyOfRange(0, 8).contentEquals(MCAP_MAGIC))
    check(file.copyOfRange(file.size - 8, file.size).contentEquals(MCAP_MAGIC))
    val r = LittleEndian(file.copyOfRange(8, file.size - 8))
    val out = ArrayList<Pair<Long, RawRecord>>()
    var offset = 8L
    while (r.remaining > 0) {
        val opcode = r.u8()
        val length = r.u64()
        out += offset to RawRecord(opcode, r.bytes(length.toInt()))
        offset += 9 + length
    }
    return out
}
