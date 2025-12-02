package com.kazimi.syaravin.presentation.components

import android.graphics.Color.WHITE
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.WhitePoint
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun VinInputField(
    vin: String,
    onVinChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val vinLength = 17
    val focusRequesters = remember { (0 until vinLength).map { FocusRequester() } }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        (0 until vinLength).forEach { index ->
            val char = vin.getOrNull(index)?.toString() ?: ""
            var textFieldValue by remember(char) { mutableStateOf(TextFieldValue(char)) }

            BasicTextField(
                value = textFieldValue,
                onValueChange = {
                    val newChar = it.text.uppercase()
                    if (newChar.length <= 1) {
                        val newVin = vin.toCharArray()
                        if (index < vin.length) {
                            newVin[index] = newChar.firstOrNull() ?: ' '
                        } else if (newChar.isNotEmpty()) {
                            // This part might need adjustment based on desired behavior for new characters
                        }
                        onVinChanged(String(newVin))
                        textFieldValue = it

                        if (newChar.isNotEmpty() && index < vinLength - 1) {
                            focusRequesters[index + 1].requestFocus()
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(2.dp)
                    )
                    .focusRequester(focusRequesters[index]),
                enabled = enabled,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    textAlign = TextAlign.Center,
                    fontSize = 20.sp,
                    color = Color.White
                ),
                decorationBox = { innerTextField ->
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        innerTextField()
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = " ",
                                color = Color.Gray
                            )
                        }
                    }
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
internal fun VinInputFieldPreview() {
    var vin by remember { mutableStateOf("1234567890ABCDEFG") }
    MaterialTheme {
        Surface {
            VinInputField(
                vin = vin,
                onVinChanged = { vin = it }
            )
        }
    }
}
