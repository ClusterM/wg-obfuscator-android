package wtf.cluster.wireguardobfuscator

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.activity.enableEdgeToEdge
import wtf.cluster.wireguardobfuscator.ui.theme.WireguardObfuscatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(Obfuscator.TAG, "MainActivity onCreate")
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WireguardObfuscatorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(Obfuscator.TAG, "MainActivity onDestroy")
        super.onDestroy()
    }
}
