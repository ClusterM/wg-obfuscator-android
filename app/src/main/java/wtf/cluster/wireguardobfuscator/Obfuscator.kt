package wtf.cluster.wireguardobfuscator

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.xor
import kotlin.random.Random

class Obfuscator(
    var key: ByteArray
) {
    val version = 1

    companion object {
        const val TAG = "WGObfuscator"

        const val WG_TYPE_HANDSHAKE      = 0x01
        const val WG_TYPE_HANDSHAKE_RESP = 0x02
        const val WG_TYPE_COOKIE         = 0x03
        const val WG_TYPE_DATA           = 0x04

        // TODO: make it configurable?
        const val MAX_DUMMY_LENGTH_HANDSHAKE = 512
        const val MAX_DUMMY_LENGTH_DATA = 4
        const val MAX_DUMMY_LENGTH_TOTAL = 1024

        fun wgType(data: ByteArray): Int {
            // Little-endian! Для совместимости с твоим Си-кодом (скорее всего)
            return ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }

        fun isObfuscated(data: ByteArray): Boolean {
            val packetType = wgType(data)
            return packetType !in WG_TYPE_HANDSHAKE..WG_TYPE_DATA
        }
    }

    fun xorData(buffer: ByteArray, length: Int, key: ByteArray) {
        val keyLength = key.size

        if (keyLength <= 0) throw Exception("Key length is $keyLength")

        var crc: Byte = 0
        for (i in 0 until length) {
            var inbyte: Byte = (key[i % keyLength] + length + keyLength).toByte()
            for (j in 0 until 8) {
                val mix = ((crc.toInt() and 0xFF) xor (inbyte.toInt() and 0xFF)) and 0x01
                crc = ((crc.toInt() and 0xFF) shr 1).toByte()
                if (mix != 0) {
                    crc = ((crc.toInt() and 0xFF) xor 0x8C).toByte()
                }
                inbyte = ((inbyte.toInt() and 0xFF) shr 1).toByte()
            }
            buffer[i] = (buffer[i].toInt() xor crc.toInt()).toByte()
        }
    }

    fun encode(
        buffer: ByteArray, length: Int
    ): Int {
        var dummyLength: Int = 0;
        val packetType = wgType(buffer)

        val rnd = (1 + Random.nextInt(255)).toByte()
        buffer[0] = buffer[0] xor rnd;
        buffer[1] = rnd;
        if (length < MAX_DUMMY_LENGTH_TOTAL) {
            val maxDummyLength = MAX_DUMMY_LENGTH_TOTAL - length;
            dummyLength = when (packetType) {
                WG_TYPE_HANDSHAKE, WG_TYPE_HANDSHAKE_RESP ->
                    Random.nextInt(minOf(maxDummyLength, MAX_DUMMY_LENGTH_HANDSHAKE))

                WG_TYPE_COOKIE, WG_TYPE_DATA ->
                    if (MAX_DUMMY_LENGTH_DATA > 0) Random.nextInt(
                        minOf(
                            maxDummyLength,
                            MAX_DUMMY_LENGTH_DATA
                        )
                    ) else 0

                else -> 0
            }
        }
        buffer[2] = (dummyLength and 0xFF).toByte()
        buffer[3] = ((dummyLength shr 8) and 0xFF).toByte()
        for (i in length until length + dummyLength) {
            buffer[i] = 0xFF.toByte()
        }

        xorData(buffer, length + dummyLength, key)
        return length + dummyLength
    }

    fun decode(
        buffer: ByteArray, length: Int
    ): Int {
        xorData(buffer, length, key)

        buffer[0] = buffer[0].xor(buffer[1])
        val dummyLength = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
        val newLength = length - dummyLength
        buffer[1] = 0
        buffer[2] = 0
        buffer[3] = 0
        return newLength
    }
}