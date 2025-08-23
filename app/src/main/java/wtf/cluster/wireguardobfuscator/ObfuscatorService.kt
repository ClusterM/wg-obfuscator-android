package wtf.cluster.wireguardobfuscator

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

    override fun onCreate() {
        super.onCreate()
        Log.d(Obfuscator.TAG, "service, onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(Obfuscator.TAG, "service, onStartCommand")

        if (started) {
            Log.d(Obfuscator.TAG, "already running, exit")
            return START_STICKY
        }

        try {
            Log.d(Obfuscator.TAG, "service, calling startForeground...")
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
            Log.d(Obfuscator.TAG, "service, startForeground called")

            started = true
            setError(null)

            val listenPort = intent?.getStringExtra(SettingsKeys.LISTEN_PORT.toString())?.toIntOrNull()
                ?: throw Exception("Port to listen not specified")
            val remoteHost = intent.getStringExtra(SettingsKeys.REMOTE_HOST.toString())
                ?: throw Exception("Target hostname not specified")
            val remotePort = intent.getStringExtra(SettingsKeys.REMOTE_PORT.toString())?.toIntOrNull()
                ?: throw Exception("Target port not specified")
            val keyStr = intent.getStringExtra(SettingsKeys.OBFUSCATION_KEY.toString())
                ?: throw Exception("Obfuscation key not specified")
            val key = keyStr.toByteArray(Charsets.UTF_8)

            stop() // in case if already running
            proxyJob = CoroutineScope(Dispatchers.IO).launch {
                Log.d(Obfuscator.TAG, "service, starting runProxy")
                runProxy(listenPort, remoteHost, remotePort, key)
            }
        } catch (e: Exception) {
            error(e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(Obfuscator.TAG, "service, onDestroy")
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
            Log.d(Obfuscator.TAG, "createNotificationChannel")
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val notifyIntent = Intent(this, MainActivity::class.java)
        val notifyPendingIntent = PendingIntent.getActivity(
            this, 0, notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(notifyPendingIntent)
            .build()
        return notification
    }

    private suspend fun runProxy(listenPort: Int, remoteHost: String, remotePort: Int, key: ByteArray) = coroutineScope {
        Log.d(Obfuscator.TAG, "service, runProxy")
        try {
            listenSocket = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort))
            remoteSocket = DatagramSocket()
            val remoteAddress = InetAddress.getByName(remoteHost)
            var clientAddress: InetAddress? = null
            var clientPort: Int? = null
            val obfuscator = Obfuscator(key)
            var handshakeSent = false
            var handshakeResponded = false

            //setStatus(getString(R.string.status_obfuscator_started))
            setStatus(getString(R.string.status_waiting_for_handshake))

            // Listen for client
            launch {
                Log.d(Obfuscator.TAG, "Client thread started")
                val buffer = ByteArray(65535)

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        listenSocket!!.receive(packet)
                        //Log.d(Obfuscator.TAG, "Received " + packet.length + " from client ${packet.address}:${packet.port}")
                        if (packet.length < 4) {
                            Log.w(Obfuscator.TAG, "Received packet from ${packet.address}:${packet.port} is too short: ${packet.length}")
                            continue
                        }
                        val packetType = Obfuscator.wgType(packet.data)
                        if (packetType !in Obfuscator.WG_TYPE_HANDSHAKE..Obfuscator.WG_TYPE_DATA) {
                            Log.w(Obfuscator.TAG, "Received unknown packet from ${packet.address}:${packet.port}, type: $packetType")
                            continue
                        }
                        // Obfuscation
                        packet.length = obfuscator.encode(packet.data, packet.length)
                        // Update client's port if need
                        if (clientAddress != packet.address || clientPort != packet.port) {
                            Log.i(Obfuscator.TAG, "Client address/port updated to ${packet.address}:${packet.port}")
                            clientAddress = packet.address
                            clientPort = packet.port
                            handshakeSent = false
                            handshakeResponded = false
                            setStatus(getString(R.string.status_waiting_for_handshake))
                        }
                        // Send to server
                        val outPacket =
                            DatagramPacket(packet.data, packet.length, remoteAddress, remotePort)
                        remoteSocket!!.send(outPacket)
                        //Log.d(Obfuscator.TAG, "Sent " + packet.length + " to server ${remoteAddress}:${remotePort}")
                        if (packetType == Obfuscator.WG_TYPE_HANDSHAKE && !handshakeSent) {
                            handshakeSent = true
                            Log.d(Obfuscator.TAG, "Sent handshake to server ${remoteAddress}:${remotePort}")
                            setStatus(getString(R.string.status_handshake_sent_waiting_for_response))
                        }
                    } catch (e: java.net.SocketException) {
                        if (!isActive) {
                            Log.d(Obfuscator.TAG, "Stopping client thread")
                        } else {
                            error(e)
                        }
                    } catch (e: Exception) {
                        error(e)
                    }
                }
                Log.d(Obfuscator.TAG, "Client thread stopped")
            }

            // Listen for server
            launch {
                Log.d(Obfuscator.TAG, "Server thread started")
                val buffer = ByteArray(65535)

                while (isActive) {
                    try {
                        val respPacket = DatagramPacket(buffer, buffer.size)
                        remoteSocket!!.receive(respPacket)
                        //Log.d(Obfuscator.TAG, "Received ${respPacket.length} from server ${respPacket.address}:${respPacket.port}")
                        if (respPacket.address != remoteAddress || respPacket.port != remotePort) {
                            Log.w(Obfuscator.TAG, "Received packet from unexpected server ${respPacket.address}:${respPacket.port}, dropping")
                            continue
                        }
                        if (clientAddress == null || clientPort == null) {
                            // Nearly impossible case, but who knows
                            Log.w(Obfuscator.TAG, "Client address/port not known yet, dropping packet")
                            continue
                        }
                        if (respPacket.length < 4) {
                            Log.w(Obfuscator.TAG, "Received packet from ${respPacket.address}:${respPacket.port} is too short: ${respPacket.length}")
                            continue
                        }
                        // Deobfuscation
                        val newLength = obfuscator.decode(respPacket.data, respPacket.length)
                        if (newLength < 4) {
                            Log.w(Obfuscator.TAG, "Failed to decode packet from ${respPacket.address}:${respPacket.port}, (original_length=${respPacket.length}, decoded_length=$newLength)")
                            continue
                        }
                        respPacket.length = newLength
                        val packetType = Obfuscator.wgType(respPacket.data)
                        if (packetType !in Obfuscator.WG_TYPE_HANDSHAKE..Obfuscator.WG_TYPE_DATA) {
                            Log.w(Obfuscator.TAG, "Decoded unknown packet from ${respPacket.address}:${respPacket.port}, type: $packetType")
                            continue
                        }
                        if (packetType == Obfuscator.WG_TYPE_HANDSHAKE_RESP && !handshakeResponded && handshakeSent) {
                            handshakeResponded = true
                            Log.d(Obfuscator.TAG, "Received handshake response from server ${respPacket.address}:${respPacket.port}")
                            setStatus(getString(R.string.status_handshake_completed))
                        }
                        // Sending back
                        val backPacket = DatagramPacket(
                            respPacket.data,
                            respPacket.length,
                            clientAddress,
                            clientPort
                        )
                        listenSocket!!.send(backPacket)
                        //Log.d(Obfuscator.TAG, "Sent " + respPacket.length + " to client ${clientAddress}:${clientPort}")
                    } catch (e: java.net.SocketException) {
                        if (!isActive) {
                            Log.d(Obfuscator.TAG, "Stopping server thread")
                        } else {
                            error(e)
                        }
                    } catch (e: Exception) {
                        error(e)
                    }
                }
                Log.d(Obfuscator.TAG, "Server thread stopped")
            }
        } catch (_: CancellationException) {
            Log.d(Obfuscator.TAG, "runProxy: cancelled")
        } catch (e: Exception) {
            error(e)
        }
    }

    private fun stop() {
        Log.d(Obfuscator.TAG, "service, stop")
        proxyJob?.cancel()
        proxyJob = null
        listenSocket?.close()
        listenSocket = null
    }

    private fun error(e: Exception) {
        setError(e.message)
        Log.e(Obfuscator.TAG, "Error: " + e.printStackTrace())
        stop()
        stopSelf()
    }

    private fun setStatus(status: String) {
        Log.d(Obfuscator.TAG, "service, setStatus: $status")
        if (started) {
            val notification = createNotification(status)
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notification)
        }
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.STATUS] = status
            }
        }
    }

    private fun setError(error: String?) {
        Log.d(Obfuscator.TAG, "service, setError: $error")
        CoroutineScope(Dispatchers.IO).launch {
            dataStore.edit { prefs ->
                prefs[SettingsKeys.ERROR] = error ?: ""
            }
        }
    }
}
