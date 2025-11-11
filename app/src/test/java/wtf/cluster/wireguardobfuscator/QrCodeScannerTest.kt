package wtf.cluster.wireguardobfuscator

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QrCodeScannerTest {

    @get:Rule
    val rule = InstantTaskExecutorRule()

    private lateinit var viewModel: ObfuscatorViewModel
    private lateinit var application: Application

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        viewModel = ObfuscatorViewModel(application)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testParseQrCode() = runTest {
        val qrCode = """[main]
source-lport = 3333
target = 2.2.2.2:1111
key = Ipy:SMOQnfxK6>;Ks<?njL#0ta|W:To-e)Vb;+h?O&(|E!7nA73F&;x&uGi_X*Ja
masking = NONE
verbose = INFO"""

        viewModel.parseQrCode(qrCode)

        assertEquals("3333", viewModel.uiState.value.listenPort)
        assertEquals("2.2.2.2", viewModel.uiState.value.remoteHost)
        assertEquals("1111", viewModel.uiState.value.remotePort)
        assertEquals("Ipy:SMOQnfxK6>;Ks<?njL#0ta|W:To-e)Vb;+h?O&(|E!7nA73F&;x&uGi_X*Ja", viewModel.uiState.value.obfuscationKey)
        assertEquals(Masking.all()[0], viewModel.uiState.value.maskingType)
    }
    
}