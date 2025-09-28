package wtf.cluster.wireguardobfuscator

import android.util.Log
import wtf.cluster.wireguardobfuscator.ObfuscatorService.PacketDirection
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MaskerStun : Masker {
    companion object {
        /* =======================
         * STUN constants
         * ======================= */
        // Magic cookie 0x2112A442 (network byte order)
        private val COOKIE_BE = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)

        // Message types
        private const val STUN_BINDING_REQ = 0x0001
        private const val STUN_BINDING_RESP = 0x0101
        private const val STUN_TYPE_DATA_IND = 0x0115

        // Attribute types
        private const val STUN_ATTR_XORMAPPED = 0x0020
        private const val STUN_ATTR_SOFTWARE = 0x8022
        private const val STUN_ATTR_FINGERPR = 0x8028
        private const val STUN_ATTR_DATA = 0x0013

        // Optional global buffer limit (can be ignored if you work with variable buffers)
        private const val BUFFER_SIZE = 65535
    }

    override val timerInterval = 10.seconds

    /* =======================
     * Utils
     * ======================= */
    // Fill p[0..n) with random bytes
    // (SecureRandom for stronger randomness; replace with kotlin.random if you want reproducible tests)
    private val RNG = SecureRandom()
    fun rand_bytes(p: ByteArray, n: Int) {
        require(n <= p.size)
        val tmp = ByteArray(n)
        RNG.nextBytes(tmp)
        System.arraycopy(tmp, 0, p, 0, n)
    }

    // Write unsigned 16-bit int in big-endian
    private fun u16be(value: Int, dst: ByteArray, off: Int) {
        dst[off]   = ((value ushr 8) and 0xFF).toByte()
        dst[off+1] = (value and 0xFF).toByte()
    }

    // Read unsigned 16-bit int in big-endian
    private fun ru16be(src: ByteArray, off: Int): Int {
        val b0 = src[off].toInt() and 0xFF
        val b1 = src[off+1].toInt() and 0xFF
        return (b0 shl 8) or b1
    }

    // Write unsigned 32-bit int in big-endian
    private fun u32be(value: Int, dst: ByteArray, off: Int) {
        dst[off]   = ((value ushr 24) and 0xFF).toByte()
        dst[off+1] = ((value ushr 16) and 0xFF).toByte()
        dst[off+2] = ((value ushr 8) and 0xFF).toByte()
        dst[off+3] = (value and 0xFF).toByte()
    }

    // memcpy equivalent
    private fun memcpy(dst: ByteArray, dstOff: Int, src: ByteArray, srcOff: Int, len: Int) {
        System.arraycopy(src, srcOff, dst, dstOff, len)
    }

    // memmove equivalent (System.arraycopy is safe for overlaps)
    private fun memmove(dst: ByteArray, dstOff: Int, src: ByteArray, srcOff: Int, len: Int) {
        System.arraycopy(src, srcOff, dst, dstOff, len)
    }

    /* =======================
     * STUN helpers
     * ======================= */

    // Check COOKIE at buf[4..7]
    fun stun_check_magic(buf: ByteArray?, len: Int): Boolean {
        if (buf == null || len < 8) return false
        return buf[4] == COOKIE_BE[0] &&
            buf[5] == COOKIE_BE[1] &&
            buf[6] == COOKIE_BE[2] &&
            buf[7] == COOKIE_BE[3]
    }

    // Peek message type (16-bit big-endian at buf[0..1])
    fun stun_peek_type(buf: ByteArray): Int {
        return ru16be(buf, 0)
    }

    /**
     * Write STUN header (type, mlen, cookie, txid) into b.
     * Returns header size (20).
     */
    fun stun_write_header(b: ByteArray, type: Int, mlen: Int, txid: ByteArray): Int {
        require(txid.size >= 12)
        u16be(type, b, 0)
        u16be(mlen, b, 2)
        memcpy(b, 4, COOKIE_BE, 0, 4)
        memcpy(b, 8, txid, 0, 12)
        return 20
    }

    // Write XOR-MAPPED-ADDRESS (IPv4) attribute at b[off..)
// Returns attribute total length (12 bytes).
    fun stun_attr_xor_mapped_addr(b: ByteArray, off: Int, address: InetAddress, port: Int): Int {
        // type,len
        u16be(STUN_ATTR_XORMAPPED, b, off + 0)
        u16be(8,                     b, off + 2) // value = family(2) + port(2) + addr(4)
        b[off + 4] = 0
        b[off + 5] = 0x01 // IPv4

        // Port (network order) XOR with cookie's first 2 bytes
        val p0 = ((port ushr 8) and 0xFF)
        val p1 = (port and 0xFF)
        b[off + 6] = (p0 xor (COOKIE_BE[0].toInt() and 0xFF)).toByte()
        b[off + 7] = (p1 xor (COOKIE_BE[1].toInt() and 0xFF)).toByte()

        // IPv4 address bytes XOR with cookie
        val ip = address.address
        require(ip.size == 4) { "IPv4 required" }
        b[off + 8]  = (ip[0].toInt() xor (COOKIE_BE[0].toInt() and 0xFF)).toByte()
        b[off + 9]  = (ip[1].toInt() xor (COOKIE_BE[1].toInt() and 0xFF)).toByte()
        b[off + 10] = (ip[2].toInt() xor (COOKIE_BE[2].toInt() and 0xFF)).toByte()
        b[off + 11] = (ip[3].toInt() xor (COOKIE_BE[3].toInt() and 0xFF)).toByte()

        return 12
    }

    /**
     * SOFTWARE attribute writer.
     * Returns total length (4 + n + pad).
     */
    fun stun_attr_software(b: ByteArray?, s: String): Int {
        val n = s.toByteArray().size
        val pad = (4 - (n and 3)) and 3
        if (b != null) {
            u16be(STUN_ATTR_SOFTWARE, b, 0)
            u16be(n, b, 2)
            val bytes = s.toByteArray()
            memcpy(b, 4, bytes, 0, n)
            if (pad > 0) {
                for (i in 0 until pad) b[4 + n + i] = 0
            }
        }
        return 4 + n + pad
    }

    /**
     * Bitwise CRC32 identical to the C version provided.
     * (You could use java.util.zip.CRC32, but here we mirror the exact loop.)
     */
    fun crc32(p: ByteArray, n: Int): Int {
        var crc = (-1).toInt() // ~0u
        for (i in 0 until n) {
            crc = crc xor (p[i].toInt() and 0xFF)
            repeat(8) {
                val mask = -(crc and 1)
                crc = (crc ushr 1) xor (0xEDB88320.toInt() and mask)
            }
        }
        return crc.inv()
    }

    /**
     * FINGERPRINT attribute = CRC32(pkt[0..cur_len)) XOR 0x5354554E, then network order.
     * Returns 8 (4 hdr + 4 value).
     */
    fun stun_attr_fingerprint(pkt: ByteArray, cur_len: Int): Int {
        val bOff = cur_len
        u16be(STUN_ATTR_FINGERPR, pkt, bOff + 0)
        u16be(4, pkt, bOff + 2)
        val fp = crc32(pkt, cur_len) xor 0x5354554E.toInt()
        u32be(fp, pkt, bOff + 4)
        return 8
    }

    /**
     * Build BINDING request into 'out'.
     * Returns total length.
     */
    fun stun_build_binding_request(out: ByteArray): Int {
        val txid = ByteArray(12)
        rand_bytes(txid, 12)
        stun_write_header(out, STUN_BINDING_REQ, 0, txid)
        var mlen = 0

        // Optional SOFTWARE:
        // mlen += stun_attr_software(out.copyOfRange(20 + mlen, out.size), "wgo/1.0")
        // (If you really want to write in-place, allocate a view, or write directly with proper offsets.)

        mlen += stun_attr_fingerprint(out, 20 + mlen)

        u16be(mlen, out, 2)
        return 20 + mlen
    }

    /**
     * Build BINDING success (response) with XOR-MAPPED-ADDRESS and FINGERPRINT.
     * Returns total length or -1 on error.
     */
    fun stun_build_binding_success(out: ByteArray, txid: ByteArray, address: InetAddress, port: Int): Int {
        if (out.size < 40) return -1 // header + XOR-MAPPED-ADDRESS + fingerprint
        stun_write_header(out, STUN_BINDING_RESP, 0, txid)
        var mlen = 0

        // ✅ write attribute directly into 'out' with offset
        mlen += stun_attr_xor_mapped_addr(out, 20 + mlen, address, port)

        // Optional SOFTWARE:
        // mlen += stun_attr_software_at(out, 20 + mlen, "wgo/1.0")

        mlen += stun_attr_fingerprint(out, 20 + mlen)
        u16be(mlen, out, 2)
        return 20 + mlen
    }

    /**
     * Wrap existing payload (buf[0..data_len)) into STUN DATA-INDICATION.
     * Returns new total length or -ENOMEM(-12) if too big.
     */
    fun stun_wrap(buf: ByteArray, data_len: Int): Int {
        val headerSize = 20
        val attrHeader = 4
        val totalAdd = headerSize + attrHeader
        if (data_len + totalAdd > min(buf.size, BUFFER_SIZE)) {
            // mimic -ENOMEM
            return -12
        }

        // shift data right
        memmove(buf, totalAdd, buf, 0, data_len)

        val txid = ByteArray(12)
        rand_bytes(txid, 12)
        // write header
        stun_write_header(buf, STUN_TYPE_DATA_IND, 0, txid)

        // write DATA attr header
        val mlen = headerSize
        u16be(STUN_ATTR_DATA, buf, mlen + 0)
        u16be(data_len,       buf, mlen + 2)

        return headerSize + attrHeader + data_len
    }

    /**
     * Unwrap STUN DATA-INDICATION in-place: move payload to buf[0..data_len).
     * Returns data_len or -1 on error.
     */
    fun stun_unwrap(buf: ByteArray, len: Int): Int {
        if (len < 24) return -1 // header(20) + attr(4)
        val msgType = ru16be(buf, 0)
        if (msgType != STUN_TYPE_DATA_IND) return -1

        val msgLen = ru16be(buf, 2)
        if (msgLen + 20 > len) return -1

        val attrType = ru16be(buf, 20)
        if (attrType != STUN_ATTR_DATA) return -1

        val dataLen = ru16be(buf, 22)
        if (dataLen + 24 > len) return -1

        // move payload down
        memmove(buf, 0, buf, 24, dataLen)
        return dataLen
    }

    override fun onHandshakeRequest(direction: PacketDirection,
                           srcAddr: InetAddress, srcPort: Int, dstAddr: InetAddress, dstPort: Int,
                           sendBackCallback: (ByteArray, Int) -> Int, sendForwardCallback: (ByteArray, Int) -> Int
    ): Int {
        val buffer = ByteArray(128)
        val len = stun_build_binding_request(buffer)

        val sent = sendForwardCallback(buffer, len)

        if (sent < 0) {
            Log.e(Obfuscator.TAG, "Can't send STUN binding request to ${dstAddr.hostAddress}:$dstPort")
        } else if (sent != len) {
            Log.w(Obfuscator.TAG, "Partial send of STUN binding request to ${dstAddr.hostAddress}:$dstPort} ($sent of $len bytes)")
        } else {
            Log.d(Obfuscator.TAG, "Sent STUN binding request ($len bytes) to ${dstAddr.hostAddress}:$dstPort")
        }

        return 0
    }

    override fun onDataUnwrap(data: ByteArray, length: Int,
                     srcAddr: InetAddress, srcPort: Int, dstAddr: InetAddress, dstPort: Int,
                     sendBackCallback: (ByteArray, Int) -> Int, sendForwardCallback: (ByteArray, Int) -> Int
    ): Int {
        if (!this.stun_check_magic(data, length)) return -1

        val stunType = stun_peek_type(data)
        when (stunType) {
            STUN_BINDING_REQ -> {
                // Received STUN Binding Request from client, send Binding Success Response
                Log.d(Obfuscator.TAG, "Received STUN Binding Request from ${srcAddr.hostAddress}:$srcPort")

                // extract txid (12 bytes at offset 8..19)
                val txid = ByteArray(12)
                if (data.size >= 20) {
                    System.arraycopy(data, 8, txid, 0, 12)
                } else {
                    Log.e(Obfuscator.TAG, "Packet too small to contain TXID")
                    return -1
                }

                // Build response in the same buffer (overwrite)
                val respLen = stun_build_binding_success(data, txid, srcAddr, srcPort)
                if (respLen > 0) {
                    val sent = try {
                        sendBackCallback(data, respLen)
                    } catch (e: Exception) {
                        Log.e(Obfuscator.TAG, "sendBackCallback threw: ${e.message}", e)
                        return -1
                    }

                    if (sent < 0) {
                        Log.e(Obfuscator.TAG, "sendto STUN response to ${srcAddr.hostAddress}:$srcPort failed")
                    } else if (sent != respLen) {
                        Log.w(
                            Obfuscator.TAG,
                            "Partial send of STUN Binding Success Response to ${srcAddr.hostAddress}:$srcPort ($sent of $respLen bytes)"
                        )
                    } else {
                        Log.d(Obfuscator.TAG, "Sent STUN Binding Success Response ($respLen bytes) to ${srcAddr.hostAddress}:$srcPort")
                    }
                } else {
                    Log.e(Obfuscator.TAG, "Failed to build STUN Binding Success Response")
                }
                return 0
            }

            STUN_BINDING_RESP -> {
                // Received a Binding Success Response — ignore
                Log.d(Obfuscator.TAG, "Received STUN Binding Success Response from ${srcAddr.hostAddress}:$srcPort, ignoring")
                return 0
            }

            STUN_TYPE_DATA_IND -> {
                // Unwrap the STUN Data Indication and return inner payload length (or negative on error)
                val unwrappedLen = stun_unwrap(data, length)
                if (unwrappedLen < 0) {
                    Log.d(Obfuscator.TAG, "Failed to unwrap STUN Data Indication from ${srcAddr.hostAddress}:$srcPort")
                    return unwrappedLen
                }
                Log.d(Obfuscator.TAG, "Unwrapped STUN Data Indication from ${srcAddr.hostAddress}:$srcPort ($unwrappedLen bytes)")
                return unwrappedLen
            }

            else -> {
                Log.d(Obfuscator.TAG, "Received unknown STUN type %04X from ${srcAddr.hostAddress}:$srcPort, ignoring".format(stunType))
                return 0
            }
        }
    }

    override fun onDataWrap(data: ByteArray, length: Int,
                     srcAddr: InetAddress, srcPort: Int, dstAddr: InetAddress, dstPort: Int,
                     sendBackCallback: (ByteArray, Int) -> Int, sendForwardCallback: (ByteArray, Int) -> Int
    ): Int {
        Log.d(Obfuscator.TAG, "Masking $length bytes");
        return stun_wrap(data, length)
    }

    override fun onTimer(
        clientAddr: InetAddress, clientPort: Int, serverAddr: InetAddress, serverPort: Int,
                   sendToClientCallback: (ByteArray, Int) -> Int, sendToServerCallback: (ByteArray, Int) -> Int
    ) {
        val buffer = ByteArray(128)
        val len = stun_build_binding_request(buffer)
        if (len < 0) return

        Log.d(Obfuscator.TAG, "Masking timer");
        sendToServerCallback(buffer, len)
    }
}