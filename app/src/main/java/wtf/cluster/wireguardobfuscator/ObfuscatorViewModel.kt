package wtf.cluster.wireguardobfuscator

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import java.io.StringReader

class ObfuscatorViewModel(app: Application) : AndroidViewModel(app) {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val dataStore = app.dataStore

    init {
        viewModelScope.launch {
            dataStore.data.collect { prefs ->
                val oldState = _uiState.value
                _uiState.value = oldState.copy(
                    isRunning = prefs[SettingsKeys.STARTED] ?: oldState.isRunning,
                    status = prefs[SettingsKeys.STATUS] ?: oldState.status,
                    error = prefs[SettingsKeys.ERROR] ?: oldState.error,
                )
            }
        }
    }

    fun onListenPortChange(newPort: String) {
        _uiState.value = _uiState.value.copy(listenPort = newPort)
        saveValue(SettingsKeys.LISTEN_PORT, newPort)
    }

    fun onRemoteHostChange(newHost: String) {
        _uiState.value = _uiState.value.copy(remoteHost = newHost)
        saveValue(SettingsKeys.REMOTE_HOST, newHost)
    }

    fun onRemotePortChange(newPort: String) {
        _uiState.value = _uiState.value.copy(remotePort = newPort)
        saveValue(SettingsKeys.REMOTE_PORT, newPort)
    }

    fun onObfuscationKeyChange(newKey: String) {
        _uiState.value = _uiState.value.copy(obfuscationKey = newKey)
        saveValue(SettingsKeys.OBFUSCATION_KEY, newKey)
    }

    fun onMaskingTypeChange(newMasking: Masking.MaskingType) {
        _uiState.value = _uiState.value.copy(maskingType = newMasking)
        saveValue(SettingsKeys.MASKING_TYPE, newMasking.id)
    }

    fun onEnableToggle(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isRunning = enabled)
        saveValue(SettingsKeys.STARTED, enabled)
        saveValue(SettingsKeys.ERROR, "")
    }

    private fun saveValue(key: Preferences.Key<String>, value: String) {
        Log.d(Obfuscator.TAG, "Value '$key' changed to '$value'")
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[key] = value
            }
        }
    }

    private fun saveValue(key: Preferences.Key<Boolean>, value: Boolean) {
        Log.d(Obfuscator.TAG, "Value '$key' changed to '$value'")
            viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[key] = value
            }
        }
    }

    fun startObfuscator(context: Context) {
        Log.d(Obfuscator.TAG, "startObfuscator")
        val state = uiState.value
        val intent = Intent(context, ObfuscatorService::class.java).apply {
            putExtra(SettingsKeys.LISTEN_PORT.toString(), state.listenPort)
            putExtra(SettingsKeys.REMOTE_HOST.toString(), state.remoteHost)
            putExtra(SettingsKeys.REMOTE_PORT.toString(), state.remotePort)
            putExtra(SettingsKeys.OBFUSCATION_KEY.toString(), state.obfuscationKey)
            putExtra(SettingsKeys.MASKING_TYPE.toString(), state.maskingType.id)
        }
        Log.d(Obfuscator.TAG, "starting service with $intent, listenPost=${state.listenPort}, remoteHost=${state.remoteHost}, remotePort=${state.remotePort}, obfuscationKey=${state.obfuscationKey}")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopObfuscator(context: Context) {
        Log.d(Obfuscator.TAG, "stopObfuscator")
        val intent = Intent(context, ObfuscatorService::class.java)
        context.stopService(intent)
    }

    fun loadServiceState(context: Context) {
        Log.d(Obfuscator.TAG, "loadServiceState")

        viewModelScope.launch {
            val prefs = context.dataStore.data.first()
            val isRunning = prefs[SettingsKeys.STARTED] == true
            val listenPort = prefs[SettingsKeys.LISTEN_PORT] ?: context.getString(R.string.default_listen_port)
            val remoteHost = prefs[SettingsKeys.REMOTE_HOST] ?: context.getString(R.string.default_remote_host)
            val remotePort = prefs[SettingsKeys.REMOTE_PORT] ?: context.getString(R.string.default_remote_port)
            var obfuscationKey = prefs[SettingsKeys.OBFUSCATION_KEY] ?: context.getString(R.string.default_obfuscation_key)
            val maskingTypeId = prefs[SettingsKeys.MASKING_TYPE] ?: Masking.all()[0].id
            val maskingType = Masking.findById(maskingTypeId) ?: Masking.all()[0]
            var status = if (!isRunning) context.getString(R.string.status_not_running) else prefs[SettingsKeys.STATUS] ?: ""
            var error = prefs[SettingsKeys.ERROR] ?: ""

            _uiState.value = _uiState.value.copy(
                isLoaded = true,
                listenPort = listenPort,
                remoteHost = remoteHost,
                remotePort = remotePort,
                obfuscationKey = obfuscationKey,
                maskingType = maskingType,
                isRunning = isRunning,
                status = status,
                error = error,
            )

            if (isRunning) {
                startObfuscator(context)
            }
        }
    }

    fun parseQrCode(qrCode: String) {
        // Parse INI-style config manually to avoid Properties escaping issues with special characters
        val config = mutableMapOf<String, String>()
        
        qrCode.lines().forEach { line ->
            val trimmedLine = line.trim()
            // Skip empty lines, comments, and section headers
            if (trimmedLine.isEmpty() || 
                trimmedLine.startsWith("#") || 
                trimmedLine.startsWith(";") ||
                (trimmedLine.startsWith("[") && trimmedLine.endsWith("]"))) {
                return@forEach
            }
            
            // Parse key = value (find first = and split there)
            val equalsIndex = trimmedLine.indexOf('=')
            if (equalsIndex > 0) {
                val key = trimmedLine.substring(0, equalsIndex).trim()
                val value = trimmedLine.substring(equalsIndex + 1).trim()
                config[key] = value
            }
        }

        val listenPort = config["source-lport"]
        val target = config["target"]
        val key = config["key"]
        val masking = config["masking"]

        if (listenPort != null) {
            onListenPortChange(listenPort)
        }
        if (target != null) {
            val parts = target.split(":")
            if (parts.size == 2) {
                onRemoteHostChange(parts[0])
                onRemotePortChange(parts[1])
            }
        }
        if (key != null) {
            onObfuscationKeyChange(key)
        }
        if (masking != null) {
            val maskingType = Masking.all().find { it.id.equals(masking, ignoreCase = true) }
            if (maskingType != null) {
                onMaskingTypeChange(maskingType)
            }
        }
    }
}