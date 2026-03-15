package com.medprep.prototype.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Screen 2 — Scanning.
 *
 * Displays a linear progress bar and the current progress percentage.
 * This screen is intentionally minimal — its only job is to reassure the
 * user that the pipeline is running. No cancel button: the scan is fast
 * enough at prototype scale that cancellation adds more complexity than value.
 *
 * The [progress] value maps directly from DuplicateEngine's onProgress callback
 * (0–100), translated by the ViewModel into a combined card-fetch + pipeline value.
 */
@Composable
fun ScanningScreen(progress: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Scanning...",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(24.dp))

        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "$progress%",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
