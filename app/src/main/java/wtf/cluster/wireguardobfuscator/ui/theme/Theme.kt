package wtf.cluster.wireguardobfuscator.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF141E8A), // switch background, top bar background
    secondary = Color(0xFF0000FF),
    background = Color.Black,
    onPrimary = Color(0xFFC0C0FF), // switch, top bar text
    error = Color.Red,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFC0C0FF), // switch background, top bar background
    secondary = Color(0xFF0000FF),
    //background = Color.White,
    onPrimary = Color(0xFF141E8A), // switch, top bar text
    error = Color.Red,
)

@Composable
fun WireguardObfuscatorTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MaterialTheme.shapes,
        content = content
    )
}