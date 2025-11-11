package wtf.cluster.wireguardobfuscator

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import wtf.cluster.wireguardobfuscator.ui.theme.WireguardObfuscatorTheme

class MainActivity : ComponentActivity() {
    private val vm: ObfuscatorViewModel by viewModels()
    
    private val qrCodeScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            Log.d(Obfuscator.TAG, "QR Code scanned: ${result.contents}")
            vm.parseQrCode(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(Obfuscator.TAG, getString(R.string.main_activity_on_create))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WireguardObfuscatorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(vm = vm)
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(Obfuscator.TAG, getString(R.string.main_activity_on_destroy))
        super.onDestroy()
    }

    fun launchQrCodeScanner() {
        val options = ScanOptions().apply {
            setPrompt(getString(R.string.scan_qr))
            setBeepEnabled(true)
            // prefer our custom capture activity which allows sensor-driven orientation
            setCaptureActivity(CustomCaptureActivity::class.java)
            setOrientationLocked(false)
        }
        qrCodeScannerLauncher.launch(options)
    }
}
