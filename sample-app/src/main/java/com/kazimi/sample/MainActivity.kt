package com.kazimi.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.kazimi.sample.ui.theme.SampleTheme
import com.kazimi.syaravin.VinScanResult
import com.kazimi.syaravin.VinScanner

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val vinScannerLauncher =
        registerForActivityResult(
            VinScanner.Contract(),
        ) { result ->
            when (result) {
                is VinScanResult.Success -> viewModel.onScanSuccess(result.vinNumber)
                is VinScanResult.Cancelled -> viewModel.onScanCancelled()
                is VinScanResult.Error -> viewModel.onScanError(result.message)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SampleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SampleAppScreen(
                        history = viewModel.history,
                        onScanClick = { vinScannerLauncher.launch(Unit) },
                    )
                }
            }
        }
    }
}
