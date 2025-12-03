package com.syarah.vinscanner.presentation.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun VinTextField(
    vin: String,
    onVinChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    isValid: Boolean = true,
    enabled: Boolean = true,
    onDone: () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    var isEditing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isEditing) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    }
                )
                .border(
                    width = 2.dp,
                    color = when {
                        !isValid -> MaterialTheme.colorScheme.error
                        isEditing -> MaterialTheme.colorScheme.primary
                        else -> Color.Transparent
                    },
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                BasicTextField(
                    value = vin,
                    onValueChange = { newValue ->
                        // Only allow alphanumeric characters and limit to 17 characters
                        val filtered = newValue.uppercase()
                            .filter { it.isLetterOrDigit() }
                            .take(17)
                        onVinChanged(filtered)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    enabled = enabled,
                    textStyle = TextStyle(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = 3.sp
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (vin.isEmpty()) {
                                Text(
                                    text = "Enter VIN Number",
                                    style = TextStyle(
                                        fontSize = 20.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        textAlign = TextAlign.Center
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                
                Row(
                    modifier = Modifier.padding(start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Paste button - show when field is empty or editing
                    if ((vin.isEmpty() || isEditing) && enabled) {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipData = clipboard.primaryClip
                                if (clipData != null && clipData.itemCount > 0) {
                                    val pastedText = clipData.getItemAt(0).text?.toString() ?: ""
                                    val filtered = pastedText.uppercase()
                                        .filter { it.isLetterOrDigit() }
                                        .take(17)
                                    onVinChanged(filtered)
                                }
                                isEditing = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddCircle,
                                contentDescription = "Paste VIN",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    // Edit button - show when not editing and field has content
                    if (!isEditing && vin.isNotEmpty() && enabled) {
                        IconButton(
                            onClick = {
                                isEditing = true
                                focusRequester.requestFocus()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit VIN",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
        
        // Character counter
        Text(
            text = "${vin.length} / 17 characters",
            style = MaterialTheme.typography.bodySmall,
            color = if (vin.length == 17) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    
    // Request focus when editing starts
    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun VinTextFieldPreview() {
    MaterialTheme {
        Surface(
            modifier = Modifier.padding(16.dp)
        ) {
            VinTextField(
                vin = "1HGBH41JXMN109186",
                onVinChanged = {},
                isValid = true
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun VinTextFieldInvalidPreview() {
    MaterialTheme {
        Surface(
            modifier = Modifier.padding(16.dp)
        ) {
            VinTextField(
                vin = "INVALID123",
                onVinChanged = {},
                isValid = false
            )
        }
    }
}