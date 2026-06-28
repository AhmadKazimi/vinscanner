@file:Suppress("ktlint:standard:function-naming")

package com.kazimi.syaravin.presentation.scanner

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kazimi.syaravin.R

/**
 * Lets the user pick among ambiguous-VIN candidates (manual capture). Characters shared by every
 * candidate render in normal black; characters that differ are highlighted bold + accent so the
 * user can immediately see which positions are uncertain.
 */
@Composable
internal fun VinSelectionDialog(
    candidates: List<String>,
    liveCandidates: List<String>,
    capturedImage: Bitmap?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val displayCandidates =
        remember(candidates, liveCandidates) {
            buildList {
                addAll(liveCandidates.filter { it.isNotBlank() })
                addAll(candidates)
            }.distinct()
        }
    var editedVin by remember(displayCandidates) {
        mutableStateOf(
            displayCandidates.firstOrNull().orEmpty(),
        )
    }
    val length = displayCandidates.firstOrNull()?.length ?: 0
    val ambiguousIndices =
        remember(displayCandidates) {
            (0 until length)
                .filter { i ->
                    displayCandidates.map { it.getOrNull(i) }.distinct().size > 1
                }.toSet()
        }
    val colorScheme = MaterialTheme.colorScheme
    val candidateLabels =
        remember(displayCandidates, ambiguousIndices, colorScheme.error) {
            displayCandidates.map { vin ->
                vin to
                    buildAnnotatedString {
                        vin.forEachIndexed { i, ch ->
                            if (i in ambiguousIndices) {
                                withStyle(SpanStyle(color = colorScheme.error)) {
                                    append(ch)
                                }
                            } else {
                                append(ch)
                            }
                        }
                    }
            }
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnClickOutside = false,
                dismissOnBackPress = false,
            ),
    ) {
        val shapes = MaterialTheme.shapes
        val typography = MaterialTheme.typography
        Surface(
            shape = shapes.medium,
            color = colorScheme.surface,
            contentColor = colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.scanner_select_vin),
                    style = typography.titleMedium,
                )
                capturedImage?.takeUnless(Bitmap::isRecycled)?.let { image ->
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = stringResource(R.string.vin_image_content_description),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 150.dp)
                                .clip(shapes.medium),
                        contentScale = ContentScale.FillWidth,
                    )
                }
                OutlinedTextField(
                    value = editedVin,
                    onValueChange = { editedVin = it.uppercase().take(17) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.vin_number)) },
                    singleLine = true,
                    textStyle = typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    candidateLabels.forEach { (vin, label) ->
                        Surface(
                            onClick = { editedVin = vin },
                            shape = shapes.medium,
                            color = colorScheme.surfaceVariant,
                            contentColor = colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = label,
                                style = typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
                Button(
                    onClick = { onSelect(editedVin) },
                    enabled = editedVin.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.scanner_save_vin))
                }
            }
        }
    }
}
