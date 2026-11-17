package moe.hexzii.earpick

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import moe.hexzii.earpick.ui.EarpickRoute
import moe.hexzii.earpick.ui.EarpickTheme
import moe.hexzii.earpick.ui.EarpickViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: EarpickViewModel by viewModels { EarpickViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EarpickTheme {
                EarpickRoute(viewModel)
            }
        }

        if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_DEV_HOST)?.let { host ->
                viewModel.connectDev(host, intent.getIntExtra(EXTRA_DEV_PORT, 40000))
            }
        }
    }

    private companion object {
        const val EXTRA_DEV_HOST = "moe.hexzii.earpick.dev_host"
        const val EXTRA_DEV_PORT = "moe.hexzii.earpick.dev_port"
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app releases the camera hotspot; rotation is not a stop.
        if (!isChangingConfigurations) viewModel.onHostStopped()
    }
}
