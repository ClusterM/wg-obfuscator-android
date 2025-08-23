package wtf.cluster.wireguardobfuscator

import android.app.UiModeManager
import android.content.Context.UI_MODE_SERVICE
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import wtf.cluster.wireguardobfuscator.ui.theme.WireguardObfuscatorTheme

@Composable
fun SettingsScreen(vm: ObfuscatorViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.loadServiceState(context)
    }

    SettingsContent(
        state = state,
        onListenPortChange = vm::onListenPortChange,
        onRemoteHostChange = vm::onRemoteHostChange,
        onRemotePortChange = vm::onRemotePortChange,
        onObfuscationKeyChange = vm::onObfuscationKeyChange,
        onEnableToggle = { checked ->
            vm.onEnableToggle(checked)
            if (checked) vm.startObfuscator(context) else vm.stopObfuscator(context)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: UiState,
    onListenPortChange: (String) -> Unit,
    onRemoteHostChange: (String) -> Unit,
    onRemotePortChange: (String) -> Unit,
    onObfuscationKeyChange: (String) -> Unit,
    onEnableToggle: (Boolean) -> Unit
) {
    val scroll = rememberScrollState()

    Scaffold(
        topBar = {
            MyTopAppBar()
        },
        contentWindowInsets = WindowInsets.ime
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Apply padding provided by Scaffold (top bar, system bars)
                .padding(innerPadding)
        ) {
            if (state.isLoaded) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    ProxySettingsContentCore(
                        state = state,
                        onListenPortChange = onListenPortChange,
                        onRemoteHostChange = onRemoteHostChange,
                        onRemotePortChange = onRemotePortChange,
                        onObfuscationKeyChange = onObfuscationKeyChange,
                        onEnableToggle = onEnableToggle
                    )
                }
            } else {
                // When loading
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = "App Icon",
                        modifier = Modifier.fillMaxSize(0.25f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTopAppBar() {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uiModeManager = context.getSystemService(UI_MODE_SERVICE) as UiModeManager

    val (noOptimizationGranted, _) = PermissionHelpers.RememberBatteryOptimizationState()

    TopAppBar(
        title = { Text(text = stringResource(R.string.app_name)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        actions = {
            if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
                return@TopAppBar
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu")
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                // Request POST_NOTIFICATIONS (API 33+)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.allow_notification)) },
                    enabled = true,
                    onClick = {
                        menuExpanded = false
                        Log.d(Obfuscator.TAG, "Requesting notification permission")
                        val notificationSettingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        context.startActivity(notificationSettingsIntent)
                    }
                )
                // Request ignore battery optimizations (API 23+)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.allow_disable_battery_optimization)) },
                    enabled = !noOptimizationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M,
                    onClick = {
                        menuExpanded = false
                        Log.d(Obfuscator.TAG, "Requesting ignore battery optimization")
                        PermissionHelpers.OpenIgnoreBatteryOptimizationRequest(context)
                    }
                )
            }
        }
    )
}

@Composable
fun ProxySettingsContentCore(
    state: UiState,
    onListenPortChange: (String) -> Unit,
    onRemoteHostChange: (String) -> Unit,
    onRemotePortChange: (String) -> Unit,
    onObfuscationKeyChange: (String) -> Unit,
    onEnableToggle: (Boolean) -> Unit
) {
    val conf = LocalConfiguration.current
    val isLandscape = conf.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Center content, limit max width (nice on tablets/TV)
    Box(
        modifier = Modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val maxWidth = if (isLandscape) 900.dp else 700.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = maxWidth)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding(),

            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ListenPort(state, onListenPortChange)
                        RemoteHost(state, onRemoteHostChange)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        RemotePort(state, onRemotePortChange)
                        ObfuscationKey(state, onObfuscationKeyChange)
                    }
                }
            } else {
                // Single column on phones or narrow width
                ListenPort(state, onListenPortChange)
                RemoteHost(state, onRemoteHostChange)
                RemotePort(state, onRemotePortChange)
                ObfuscationKey(state, onObfuscationKeyChange)
            }

            HorizontalDivider()

            ControlAndStatus(state, onEnableToggle)
        } // column
    } // box
}

@Composable
fun ListenPort(
    state: UiState,
    onListenPortChange: (String) -> Unit
) {
    Column {
        NumberFieldBasic(
            value = state.listenPort,
            onChange = onListenPortChange,
            label = stringResource(R.string.listen_port_label),
            placeholder = stringResource(R.string.listen_port_placeholder),
            enabled = !state.isRunning
        )
        Hint(state, stringResource(R.string.listen_port_hint))
    }
}

@Composable
fun RemoteHost(
    state: UiState,
    onRemoteHostChange: (String) -> Unit
) {
    Column {
        TextFieldBasic(
            value = state.remoteHost,
            onChange = onRemoteHostChange,
            label = stringResource(R.string.remote_host_label),
            placeholder = stringResource(R.string.remote_host_placeholder),
            enabled = !state.isRunning
        )
        Hint(state, stringResource(R.string.remote_host_hint))
    }
}

@Composable
fun RemotePort(
    state: UiState,
    onRemotePortChange: (String) -> Unit
) {
    Column {
        NumberFieldBasic(
            value = state.remotePort,
            onChange = onRemotePortChange,
            label = stringResource(R.string.remote_port_label),
            placeholder = stringResource(R.string.remote_port_placeholder),
            enabled = !state.isRunning
        )
        Hint(state, stringResource(R.string.remote_port_hint))
    }
}

@Composable
fun ObfuscationKey(
    state: UiState,
    onObfuscationKeyChange: (String) -> Unit
) {
    Column {
        TextFieldBasic(
            value = state.obfuscationKey,
            onChange = onObfuscationKeyChange,
            label = stringResource(R.string.obfuscation_key_label),
            placeholder = stringResource(R.string.obfuscation_key_placeholder),
            enabled = !state.isRunning
        )
        Hint(state, stringResource(R.string.obfuscation_key_hint))
    }
}

@Composable
fun ControlAndStatus(
    state: UiState,
    onEnableToggle: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            stringResource(R.string.enable_obfuscator),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium
        )
        Switch(
            checked = state.isRunning,
            onCheckedChange = onEnableToggle
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "${stringResource(R.string.status)}: ",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = state.status,
            modifier = Modifier.weight(1f),
            softWrap = true,
            style = MaterialTheme.typography.bodyLarge
        )
    }

    if (state.error.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "${stringResource(R.string.error)}: ",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = state.error,
                modifier = Modifier.weight(1f),
                softWrap = true,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun NumberFieldBasic(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    // Keep only digits; limit to 5 chars (0..65535 fits)
    val filterDigits = remember<(String) -> String> { { s -> s.filter(Char::isDigit).take(5) } }

    OutlinedTextField(
        value = value,
        onValueChange = { onChange(filterDigits(it)) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun TextFieldBasic(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun Hint(
    state: UiState,
    text: String
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (!state.isRunning) 0.7f else 0.3f),
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
@Preview(name = "Phone • Light", showBackground = true, showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO, device = Devices.PHONE)
@Preview(name = "Phone • Dark",  showBackground = true, showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES, device = Devices.PHONE)
@Preview(name = "Phone • Scale", showBackground = true, showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO, device = Devices.PHONE,
    fontScale = 2f)
@Preview(name = "Tablet • Light", showBackground = true, showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO, device = Devices.TABLET)
@Preview(name = "TV • Light", showBackground = true, showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO, device = Devices.TV_1080p)
@Preview(name = "TV • Dark", showBackground = true, showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES, device = Devices.TV_1080p)
annotation class AllDevicePreviews

@AllDevicePreviews
@Composable
fun SettingsScreenPreviewOn() {
    val fakeState = UiState(
        isLoaded = true,
        listenPort = "51820",
        remoteHost = "10.0.0.1",
        remotePort = "13249",
        obfuscationKey = "secret123",
        isRunning = true,
        status = "Waiting for answer from server... from server... from server... from server... so long string",
        error = "Пизда"
    )
    WireguardObfuscatorTheme {
        SettingsContent(
            state = fakeState,
            onListenPortChange = {},
            onRemoteHostChange = {},
            onRemotePortChange = {},
            onObfuscationKeyChange = {},
            onEnableToggle = {}
        )
    }
}

@AllDevicePreviews
@Composable
fun SettingsScreenPreviewOff() {
    val fakeState = UiState(
        isLoaded = true,
        listenPort = "51820",
        remoteHost = "10.0.0.1",
        remotePort = "13249",
        obfuscationKey = "secret123",
        isRunning = false,
        status = "Waiting for answer from server... from server... from server... from server... so long string",
        error = "Пизда"
    )
    WireguardObfuscatorTheme {
        SettingsContent(
            state = fakeState,
            onListenPortChange = {},
            onRemoteHostChange = {},
            onRemotePortChange = {},
            onObfuscationKeyChange = {},
            onEnableToggle = {}
        )
    }
}

@AllDevicePreviews
@Composable
fun SettingsScreenPreviewLoading() {
    val fakeState = UiState(
        isLoaded = false,
        listenPort = "51820",
        remoteHost = "10.0.0.1",
        remotePort = "13249",
        obfuscationKey = "secret123",
        isRunning = false,
        status = "Waiting for answer from server... from server... from server... from server... so long string",
        error = "Пизда"
    )
    WireguardObfuscatorTheme {
        SettingsContent(
            state = fakeState,
            onListenPortChange = {},
            onRemoteHostChange = {},
            onRemotePortChange = {},
            onObfuscationKeyChange = {},
            onEnableToggle = {}
        )
    }
}
