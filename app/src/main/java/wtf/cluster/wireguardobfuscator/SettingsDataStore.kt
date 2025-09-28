package wtf.cluster.wireguardobfuscator

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DATASTORE_NAME = "settings"

val Context.dataStore by preferencesDataStore(
    name = DATASTORE_NAME
)

object SettingsKeys {
    val LISTEN_PORT = stringPreferencesKey("listen_port")
    val REMOTE_HOST = stringPreferencesKey("remote_host")
    val REMOTE_PORT = stringPreferencesKey("remote_port")
    val OBFUSCATION_KEY = stringPreferencesKey("key")
    val MASKING_TYPE = stringPreferencesKey("masking_type")
    val STARTED = booleanPreferencesKey("started")
    val STATUS = stringPreferencesKey("status")
    val ERROR = stringPreferencesKey("error")
}
