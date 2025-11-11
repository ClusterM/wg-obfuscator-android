package wtf.cluster.wireguardobfuscator

import android.content.pm.ActivityInfo
import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * Custom capture activity that allows the camera activity to rotate with the device.
 * The default capture activity in the ZXing embedded lib can be locked. We request
 * full sensor orientation here so the scanner follows phone rotation.
 */
class CustomCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Allow sensor-based orientation changes
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        super.onCreate(savedInstanceState)
    }
}
