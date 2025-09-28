package wtf.cluster.wireguardobfuscator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(Obfuscator.TAG, "received ${intent.action}")
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            runBlocking {
                val prefs = context.dataStore.data.first()
                val wasRunning = prefs[SettingsKeys.STARTED] == true
                if (wasRunning) {
                    val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
                    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) {
                        Log.w(Obfuscator.TAG, "AndroidTV detected, can't autostart, start service again manually")
                        context.dataStore.edit { prefs ->
                            prefs[SettingsKeys.STARTED] = false
                        }
                        return@runBlocking
                    }
                    // Get settings 
                    val listenPort = prefs[SettingsKeys.LISTEN_PORT] ?: context.getString(R.string.default_listen_port)
                    val remoteHost = prefs[SettingsKeys.REMOTE_HOST] ?: context.getString(R.string.default_remote_host)
                    val remotePort = prefs[SettingsKeys.REMOTE_PORT] ?: context.getString(R.string.default_remote_port)
                    val key = prefs[SettingsKeys.OBFUSCATION_KEY] ?: context.getString(R.string.default_obfuscation_key)
                    val maskingTypeId = prefs[SettingsKeys.MASKING_TYPE] ?: Masking.all()[0].id
                    // Start service
                    val serviceIntent = Intent(context, ObfuscatorService::class.java).apply {
                        putExtra(SettingsKeys.LISTEN_PORT.toString(), listenPort)
                        putExtra(SettingsKeys.REMOTE_HOST.toString(), remoteHost)
                        putExtra(SettingsKeys.REMOTE_PORT.toString(), remotePort)
                        putExtra(SettingsKeys.OBFUSCATION_KEY.toString(), key)
                        putExtra(SettingsKeys.MASKING_TYPE.toString(), maskingTypeId)
                    }
                    
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent);
                        } else {
                            context.startService(serviceIntent);
                        }
                    } catch (e: Exception) {
                        Log.e(Obfuscator.TAG, "Can't start on ACTION_BOOT_COMPLETED ($e)")
                    }
                }
            }
        }
    }
}
