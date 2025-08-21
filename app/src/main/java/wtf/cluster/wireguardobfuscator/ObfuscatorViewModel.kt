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
                    error = prefs[SettingsKeys.ERROR] ?: oldState.status,
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

    fun onEnableToggle(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isRunning = enabled)
        saveValue(SettingsKeys.STARTED, enabled)
        saveValue(SettingsKeys.ERROR, "")
    }

    private fun saveValue(key: Preferences.Key<String>, value: String) {
        Log.d(Obfuscator.TAG, "Value '$key' changed to '$value'");
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[key] = value
            }
        }
    }

    private fun saveValue(key: Preferences.Key<Boolean>, value: Boolean) {
        Log.d(Obfuscator.TAG, "Value '$key' changed to '$value'");
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
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
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
            var status = if (!isRunning) context.getString(R.string.status_not_running) else prefs[SettingsKeys.STATUS] ?: ""
            var error = prefs[SettingsKeys.ERROR] ?: ""

            _uiState.value = _uiState.value.copy(
                isLoaded = true,
                listenPort = listenPort,
                remoteHost = remoteHost,
                remotePort = remotePort,
                obfuscationKey = obfuscationKey,
                isRunning = isRunning,
                status = status,
                error = error,
            )

            if (isRunning) {
                startObfuscator(context)
            }
        }
    }
}
