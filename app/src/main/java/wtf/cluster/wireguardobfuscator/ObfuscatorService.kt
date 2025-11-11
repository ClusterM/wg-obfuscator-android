package wtf.cluster.wireguardobfuscator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.coroutines.cancellation.CancellationException

class ObfuscatorService : Service() {
    private val channelId = "wg_obfuscator_channel"
    private val notificationId = 1
    private var proxyJob: Job? = null
    private var started = false
    private var listenSocket: DatagramSocket? = null
    private var remoteSocket: DatagramSocket? = null

    enum class PacketDirection
    {
        ClientToServer,
        ServerToClient
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(Obfuscator.TAG, getString(R.string.service_on_create))
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(Obfuscator.TAG, getString(R.string.service_on_start_command))

        if (started) {
            Log.d(Obfuscator.TAG, getString(R.string.already_running_exit))
            return START_STICKY
        }

        try {
            Log.d(Obfuscator.TAG, getString(R.string.service_calling_start_foreground))
            val notification = createNotification(getString(R.string.status_starting))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(notificationId, notification)
            }
            Log.d(Obfuscator.TAG, getString(R.string.service_start_foreground_called))

            started = true
            setError(null)

            val listenPort = intent?.getStringExtra(SettingsKeys.LISTEN_PORT.toString())?.toIntOrNull()
                ?: throw Exception(getString(R.string.port_to_listen_not_specified))
            val remoteHost = intent.getStringExtra(SettingsKeys.REMOTE_HOST.toString())
                ?: throw Exception(getString(R.string.target_hostname_not_specified))
            val remotePort = intent.getStringExtra(SettingsKeys.REMOTE_PORT.toString())?.toIntOrNull()
                ?: throw Exception(getString(R.string.target_port_not_specified))
            val keyStr = intent.getStringExtra(SettingsKeys.OBFUSCATION_KEY.toString())
                ?: throw Exception(getString(R.string.obfuscation_key_not_specified))
            val key = keyStr.toByteArray(Charsets.UTF_8)
            val maskerTypeId = intent.getStringExtra(SettingsKeys.MASKING_TYPE.toString()) ?: Masking.all()[0].id

            stop() // in case if already running
            proxyJob = CoroutineScope(Dispatchers.IO).launch {
                Log.d(Obfuscator.TAG, getString(R.string.service_starting_run_proxy))
                runProxy(listenPort, remoteHost, remotePort, key, maskerTypeId)
            }
        } catch (e: Exception) {
            error(e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(Obfuscator.TAG, getString(R.string.service_on_destroy))
        started = false
        stop()
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.STARTED] = false
            }
        }
        setStatus(getString(R.string.status_obfuscator_stopped))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(Obfuscator.TAG, getString(R.string.create_notification_channel))
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String, subStatus: String? = null): Notification {
        val notifyIntent = Intent(this, MainActivity::class.java)
        val notifyPendingIntent = PendingIntent.getActivity(
            this, 0, notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle(status)
            .setContentText(subStatus)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(notifyPendingIntent)
            .build()
        return notification
    }

    private suspend fun runProxy(listenPort: Int, remoteHost: String, remotePort: Int, key: ByteArray, maskerTypeId: String) = coroutineScope {
        Log.d(Obfuscator.TAG, getString(R.string.service_run_proxy))
        try {
            listenSocket = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort))
            remoteSocket = DatagramSocket()
            val remoteAddress = InetAddress.getByName(remoteHost)
            var clientAddress: InetAddress? = null
            var clientPort: Int? = null
            val obfuscator = Obfuscator(key, this@ObfuscatorService)
            var handshakeSent = false
            var handshakeResponded = false
            var tx: Long = 0
            var rx: Long = 0
            var masker: Masker? = null

            masker = Masking.createMasker(maskerTypeId, this@ObfuscatorService)

            fun updateStatusWithStats() {
                if (!handshakeSent) {
                    setStatus(getString(R.string.status_waiting_for_handshake))
                }
                else if (!handshakeResponded) {
                    setStatus(getString(R.string.status_handshake_sent_waiting_for_response))
                } else {
                    @SuppressLint("DefaultLocale")
                    fun formatBytes(bytes: Long): String {
                        val units = arrayOf(getString(R.string.byte_unit_b), getString(R.string.byte_unit_kib), getString(R.string.byte_unit_mib), getString(R.string.byte_unit_gib), getString(R.string.byte_unit_tib))
                        var value = bytes.toDouble()
                        var unitIndex = 0
                        while (value >= 1024 && unitIndex < units.size - 1) {
                            value /= 1024
                            unitIndex++
                        }
                        return String.format(getString(R.string.byte_format), value, units[unitIndex])
                    }
                    val txStr = formatBytes(tx)
                    val rxStr = formatBytes(rx)
                    setStatus(getString(R.string.status_handshake_completed), getString(R.string.tx_rx_format, txStr, rxStr))
                }
            }

            updateStatusWithStats()

            fun sendToClient(data: ByteArray, length: Int): Int {
                val backPacket = DatagramPacket(
                    data,
                    length,
                    clientAddress,
                    clientPort!!
                )
                listenSocket!!.send(backPacket)
                return length
            }

            fun sendToServer(data: ByteArray, length: Int): Int {
                val outPacket =
                    DatagramPacket(data, length, remoteAddress, remotePort)
                remoteSocket!!.send(outPacket)
                return length
            }

            // Listen for client
            launch {
                Log.d(Obfuscator.TAG, getString(R.string.client_thread_started))
                val buffer = ByteArray(65535)

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        listenSocket!!.receive(packet)
                        //Log.d(Obfuscator.TAG, "Received " + packet.length + " from client ${packet.address}:${packet.port}")
                        if (packet.length < 4) {
                            Log.w(Obfuscator.TAG, getString(R.string.packet_from_too_short, packet.address, packet.port, packet.length))
                            continue
                        }
                        val packetType = Obfuscator.wgType(packet.data)
                        if (packetType !in Obfuscator.WG_TYPE_HANDSHAKE..Obfuscator.WG_TYPE_DATA) {
                            Log.w(Obfuscator.TAG, getString(R.string.unknown_packet_from, packet.address, packet.port, packetType))
                            continue
                        }
                        // Obfuscation
                        packet.length = obfuscator.encode(packet.data, packet.length)
                        // Update client's port if need
                        if (clientAddress != packet.address || clientPort != packet.port) {
                            Log.i(Obfuscator.TAG, getString(R.string.client_address_updated, packet.address, packet.port))
                            clientAddress = packet.address
                            clientPort = packet.port
                            handshakeSent = false
                            handshakeResponded = false
                            updateStatusWithStats()
                        }
                        // Masking
                        if (masker != null) {
                            if (packetType == Obfuscator.WG_TYPE_HANDSHAKE) {
                                masker.onHandshakeRequest(
                                    PacketDirection.ClientToServer,
                                    clientAddress!!,
                                    clientPort!!,
                                    remoteAddress,
                                    remotePort,
                                    ::sendToClient,
                                    ::sendToServer
                                )
                            }

                            packet.length = masker.onDataWrap(packet.data, packet.length, clientAddress!!, clientPort!!, remoteAddress, remotePort, ::sendToClient, ::sendToServer)
                        }

                        // Send to server
                        tx += sendToServer(packet.data, packet.length)
                        //Log.d(Obfuscator.TAG, "Sent " + packet.length + " to server ${remoteAddress}:${remotePort}")
                        if (packetType == Obfuscator.WG_TYPE_HANDSHAKE && !handshakeSent) {
                            handshakeSent = true
                            Log.d(Obfuscator.TAG, getString(R.string.sent_handshake_to_server, remoteAddress, remotePort))
                            updateStatusWithStats()
                        }
                    } catch (e: java.net.SocketException) {
                        if (!isActive) {
                            Log.d(Obfuscator.TAG, getString(R.string.stopping_client_thread))
                        } else {
                            error(e)
                        }
                    } catch (e: Exception) {
                        error(e)
                    }
                }
                Log.d(Obfuscator.TAG, getString(R.string.client_thread_stopped))
            }

            // Listen for server
            launch {
                Log.d(Obfuscator.TAG, getString(R.string.server_thread_started))
                val buffer = ByteArray(65535)

                while (isActive) {
                    try {
                        val respPacket = DatagramPacket(buffer, buffer.size)
                        remoteSocket!!.receive(respPacket)
                        //Log.d(Obfuscator.TAG, "Received ${respPacket.length} from server ${respPacket.address}:${respPacket.port}")
                        if (respPacket.address != remoteAddress || respPacket.port != remotePort) {
                            Log.w(Obfuscator.TAG, getString(R.string.unexpected_server_packet, respPacket.address, respPacket.port))
                            continue
                        }
                        if (clientAddress == null || clientPort == null) {
                            // Nearly impossible case, but who knows
                            Log.w(Obfuscator.TAG, getString(R.string.client_address_not_known))
                            continue
                        }
                        rx += respPacket.length
                        // Masking
                        if (masker != null) {
                            respPacket.length = masker.onDataUnwrap(respPacket.data, respPacket.length, remoteAddress, remotePort, clientAddress!!, clientPort!!, ::sendToServer, ::sendToClient)
                            if (respPacket.length <= 0) {
                                // Nothing to do
                                continue
                            }
                        }
                        if (respPacket.length < 4) {
                            Log.w(Obfuscator.TAG, getString(R.string.packet_from_server_too_short, respPacket.address, respPacket.port, respPacket.length))
                            continue
                        }
                        // Deobfuscation
                        val newLength = obfuscator.decode(respPacket.data, respPacket.length)
                        if (newLength < 4) {
                            Log.w(Obfuscator.TAG, getString(R.string.failed_to_decode_packet, respPacket.address, respPacket.port, respPacket.length, newLength))
                            continue
                        }
                        respPacket.length = newLength
                        val packetType = Obfuscator.wgType(respPacket.data)
                        if (packetType !in Obfuscator.WG_TYPE_HANDSHAKE..Obfuscator.WG_TYPE_DATA) {
                            Log.w(Obfuscator.TAG, getString(R.string.decoded_unknown_packet, respPacket.address, respPacket.port, packetType))
                            continue
                        }
                        if (packetType == Obfuscator.WG_TYPE_HANDSHAKE_RESP && !handshakeResponded && handshakeSent) {
                            handshakeResponded = true
                            Log.d(Obfuscator.TAG, getString(R.string.received_handshake_from_server, respPacket.address, respPacket.port))
                            updateStatusWithStats()
                        }
                        // Sending back
                        sendToClient(respPacket.data, respPacket.length)
                        //Log.d(Obfuscator.TAG, "Sent " + respPacket.length + " to client ${clientAddress}:${clientPort}")
                    } catch (e: java.net.SocketException) {
                        if (!isActive) {
                            Log.d(Obfuscator.TAG, getString(R.string.stopping_server_thread))
                        } else {
                            error(e)
                        }
                    } catch (e: Exception) {
                        error(e)
                    }
                }
                Log.d(Obfuscator.TAG, getString(R.string.server_thread_stopped))
            }

            // Update status
            launch {
                while (isActive) {
                    try {
                        delay(5000)
                        updateStatusWithStats()
                    } catch (_: CancellationException) {
                        break
                    }
                }
            }

            // Masking timer
            if (masker?.timerInterval != null) {
                launch {
                    while (isActive) {
                        delay(masker.timerInterval!!)
                        if (clientAddress != null && clientPort != null) {
                            try {
                                masker.onTimer(
                                    clientAddress!!,
                                    clientPort!!,
                                    remoteAddress,
                                    remotePort,
                                    ::sendToClient,
                                    ::sendToServer
                                )
                            } catch (_: CancellationException) {
                                break
                            } catch (e: Exception) {
                                Log.e(Obfuscator.TAG, getString(R.string.masking_timer_error), e)
                            }
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            Log.d(Obfuscator.TAG, getString(R.string.run_proxy_cancelled))
        } catch (e: Exception) {
            error(e)
        }
    }

    private fun stop() {
        Log.d(Obfuscator.TAG, getString(R.string.service_stop))
        proxyJob?.cancel()
        proxyJob = null
        listenSocket?.close()
        listenSocket = null
    }

    private fun error(e: Exception) {
        setError(e.message)
        Log.e(Obfuscator.TAG, getString(R.string.error_format), e)
        stop()
        stopSelf()
    }

    private fun setStatus(status: String, subStatus: String? = null) {
        Log.d(Obfuscator.TAG, getString(R.string.service_set_status, status, subStatus ?: ""))
        if (started) {
            val notification = createNotification(status, subStatus)
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notification)
        }
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.STATUS] = if (subStatus == null) status else "$status\n$subStatus"
            }
        }
    }

    private fun setError(error: String?) {
        Log.d(Obfuscator.TAG, getString(R.string.service_set_error, error ?: ""))
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.ERROR] = error ?: ""
            }
        }
    }
}
