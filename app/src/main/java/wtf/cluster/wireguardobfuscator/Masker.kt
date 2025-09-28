package wtf.cluster.wireguardobfuscator

import wtf.cluster.wireguardobfuscator.ObfuscatorService.PacketDirection
import java.net.InetAddress
import kotlin.time.Duration

interface Masker {
    val timerInterval: Duration?

    fun onHandshakeRequest(direction: PacketDirection,
                           srcAddr: InetAddress, srcPort: Int, dstAddr: InetAddress, dstPort: Int,
                           sendBackCallback: (ByteArray, Int) -> Int, sendForwardCallback: (ByteArray, Int) -> Int
    ): Int

    fun onDataUnwrap(data: ByteArray, length: Int,
                     srcAddr: InetAddress, srcPort: Int, dstAddr: InetAddress, dstPort: Int,
                     sendBackCallback: (ByteArray, Int) -> Int, sendForwardCallback: (ByteArray, Int) -> Int
    ): Int

    fun onDataWrap(data: ByteArray, length: Int,
                   srcAddr: InetAddress, srcPort: Int, dstAddr: InetAddress, dstPort: Int,
                   sendBackCallback: (ByteArray, Int) -> Int, sendForwardCallback: (ByteArray, Int) -> Int
    ): Int

    fun onTimer(
        clientAddr: InetAddress, clientPort: Int, serverAddr: InetAddress, serverPort: Int,
        sendToClientCallback: (ByteArray, Int) -> Int, sendToServerCallback: (ByteArray, Int) -> Int
    )
}