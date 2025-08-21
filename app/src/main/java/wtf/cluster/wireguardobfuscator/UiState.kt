package wtf.cluster.wireguardobfuscator

data class UiState(
    val isLoaded: Boolean = false,
    val listenPort: String = "",
    val remoteHost: String = "",
    val remotePort: String = "",
    val obfuscationKey: String = "",
    val isRunning: Boolean = false,
    val status: String = "",
    val error: String = "",
)