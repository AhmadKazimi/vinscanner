package com.kazimi.syaravin.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun VinEditBar(
    vin: String,
    onVinChanged: (String) -> Unit,
    onDone: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            VinInputField(
                vin = vin,
                onVinChanged = onVinChanged
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear VIN"
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onDone) {
                    Icon(
                        imageVector = Icons.Default.Done,
                        contentDescription = "Done"
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Done")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun VinEditBarPreview() {
    var vin by remember { mutableStateOf("1234567890ABCDEFG") }
    MaterialTheme {
        VinEditBar(
            vin = vin,
            onVinChanged = { vin = it },
            onDone = {},
            onClear = { vin = "" }
        )
    }
}