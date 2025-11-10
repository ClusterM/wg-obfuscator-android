package wtf.cluster.wireguardobfuscator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ObfuscatorAlgorithmTest {

    private lateinit var application: Application

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testObfuscatorEncodeDecodeRoundtrip() {
        val key = "Ipy:SMOQnfxK6>;Ks<?njL#0ta|W:To-e)Vb;+h?O&(|E!7nA73F&;x&uGi_X*Ja".toByteArray(Charsets.UTF_8)
        val obfuscator = Obfuscator(key, application)

        val original = ByteArray(148)
        original[0] = 0x01
        original[1] = 0x00
        original[2] = 0x00
        original[3] = 0x00
        for (i in 4 until 148) original[i] = (i and 0xFF).toByte()

        val buffer = original.copyOf(1024)
        val encodedLength = obfuscator.encode(buffer, 148)
        assertTrue("Encoded length should be >= original", encodedLength >= 148)

        val decodedLength = obfuscator.decode(buffer, encodedLength)
        assertEquals("Decoded length should match original", 148, decodedLength)

        assertEquals(0x01, buffer[0].toInt() and 0xFF)
        assertEquals(0x00, buffer[1].toInt() and 0xFF)
        assertEquals(0x00, buffer[2].toInt() and 0xFF)
        assertEquals(0x00, buffer[3].toInt() and 0xFF)
        for (i in 4 until 148) assertEquals("Byte $i should match", original[i], buffer[i])
    }

    @Test
    fun testObfuscatorWithSpecialCharacterKey() {
        val key = "Ipy:SMOQnfxK6>;Ks<?njL#0ta|W:To-e)Vb;+h?O&(|E!7nA73F&;x&uGi_X*Ja".toByteArray(Charsets.UTF_8)
        assertEquals(64, key.size)
        val obfuscator = Obfuscator(key, application)

        val packetTypes = listOf(
            Obfuscator.WG_TYPE_HANDSHAKE to 148,
            Obfuscator.WG_TYPE_HANDSHAKE_RESP to 92,
            Obfuscator.WG_TYPE_COOKIE to 64,
            Obfuscator.WG_TYPE_DATA to 32
        )
        for ((packetType, packetSize) in packetTypes) {
            val original = ByteArray(packetSize)
            original[0] = (packetType and 0xFF).toByte()
            original[1] = ((packetType shr 8) and 0xFF).toByte()
            original[2] = ((packetType shr 16) and 0xFF).toByte()
            original[3] = ((packetType shr 24) and 0xFF).toByte()
            for (i in 4 until packetSize) original[i] = ((i * 7) and 0xFF).toByte()

            val buffer = original.copyOf(1024)
            val encodedLength = obfuscator.encode(buffer, packetSize)
            assertTrue(Obfuscator.isObfuscated(buffer))
            val decodedLength = obfuscator.decode(buffer, encodedLength)
            assertEquals(packetSize, decodedLength)
            val restoredType = Obfuscator.wgType(buffer)
            assertEquals(packetType, restoredType)
            for (i in 0 until packetSize) assertEquals("Byte $i in packet type $packetType should match", original[i], buffer[i])
        }
    }

    @Test
    fun testKeyByteHandlingWithHighValues() {
        val keyWithHighBytes = ByteArray(64) { ((it * 4) and 0xFF).toByte() }
        val obfuscator = Obfuscator(keyWithHighBytes, application)
        val original = ByteArray(148)
        original[0] = 0x01
        for (i in 4 until 148) original[i] = (i and 0xFF).toByte()

        val buffer = original.copyOf(1024)
        val encodedLength = obfuscator.encode(buffer, 148)
        val decodedLength = obfuscator.decode(buffer, encodedLength)
        assertEquals(148, decodedLength)
        for (i in 0 until 148) assertEquals("Byte $i should match after encode/decode", original[i], buffer[i])
    }

    @Test
    fun testDummyLengthExtraction() {
        val key = "test".toByteArray(Charsets.UTF_8)
        val obfuscator = Obfuscator(key, application)
        val original = ByteArray(32)
        original[0] = 0x04
        val buffer = original.copyOf(1024)
        val encodedLength = obfuscator.encode(buffer, 32)
        assertTrue(encodedLength >= 32)
        val decodedLength = obfuscator.decode(buffer, encodedLength)
        assertEquals(32, decodedLength)
        assertEquals(0x04, buffer[0].toInt() and 0xFF)
    }
}
