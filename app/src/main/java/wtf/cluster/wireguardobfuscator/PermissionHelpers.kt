package wtf.cluster.wireguardobfuscator

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

class PermissionHelpers() {
    companion object {
        // Battery optimization: open screens + state that refreshes on resume
        fun OpenIgnoreBatteryOptimizationRequest(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

            val pkg = context.packageName
            val pm = context.packageManager

            // 1) Direct request screen
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
                addCategory(Intent.CATEGORY_DEFAULT)
                //addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // reliability across OEMs
            }
            try {
                if (direct.resolveActivity(pm) != null) {
                    Log.d(Obfuscator.TAG, "BatteryOpt: direct request")
                    context.startActivity(direct)
                    return
                }
            } catch (_: ActivityNotFoundException) { /* fall through */
            }

            // 2) Fallback: list of apps
            val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                //addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                if (list.resolveActivity(pm) != null) {
                    Log.d(Obfuscator.TAG, "BatteryOpt: list screen")
                    context.startActivity(list)
                    return
                }
            } catch (_: ActivityNotFoundException) { /* fall through */
            }

            // 3) Last resort: app details
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", pkg, null)
                //addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                Log.d(Obfuscator.TAG, "BatteryOpt: app details")
                context.startActivity(details)
            } catch (e: ActivityNotFoundException) {
                Log.e(Obfuscator.TAG, "BatteryOpt: cannot open any settings", e)
            }
        }

        @Composable
        fun RememberBatteryOptimizationState(): Pair<Boolean, () -> Unit> {
            val context = LocalContext.current
            val powerManager = remember { context.getSystemService<PowerManager>()!! }
            var noOptimizationGranted by remember { mutableStateOf(true) }

            // Initial check
            LaunchedEffect(Unit) {
                noOptimizationGranted = if (Build.VERSION.SDK_INT >= 23) {
                    powerManager.isIgnoringBatteryOptimizations(context.packageName)
                } else true
            }

            // Re-check function (can be called manually)
            val recalc = {
                if (Build.VERSION.SDK_INT >= 23) {
                    noOptimizationGranted =
                        powerManager.isIgnoringBatteryOptimizations(context.packageName)
                } else {
                    noOptimizationGranted = true
                }
            }

            // Also re-check automatically on ON_RESUME
            val lifecycleOwner = LocalLifecycleOwner.current
            val recalcState by rememberUpdatedState(recalc)
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) recalcState()
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            return noOptimizationGranted to recalc
        }
    }
}