package app.tel.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tel.domain.model.ValueSource
import app.tel.ui.theme.FigureStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A dashboard figure: big value, small label underneath. */
@Composable
fun StatCard(value: String, label: String, modifier: Modifier = Modifier, sub: String? = null) {
    Card(
        modifier = modifier.semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge.merge(FigureStyle), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Two cards side by side; stacks naturally inside a scrolling column. */
@Composable
fun StatRow(a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        a(Modifier.weight(1f))
        b(Modifier.weight(1f))
    }
}

/** Shows where a value came from, with an icon *and* text (never colour alone). */
@Composable
fun SourceTag(source: ValueSource, needsReview: Boolean, confidence: Float? = null) {
    val (text, icon, color) = when {
        needsReview && source == ValueSource.OCR -> Triple("Needs review", Icons.Filled.Warning, MaterialTheme.colorScheme.tertiary)
        source == ValueSource.OCR -> Triple("Read from photo", Icons.Filled.Check, MaterialTheme.colorScheme.primary)
        source == ValueSource.USER -> Triple("Edited", Icons.Filled.Edit, MaterialTheme.colorScheme.onSurfaceVariant)
        source == ValueSource.CALCULATED -> Triple("Calculated", Icons.Filled.Info, MaterialTheme.colorScheme.secondary)
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        val pct = if (source == ValueSource.OCR && confidence != null) " · ${(confidence * 100).toInt()}%" else ""
        Text(text + pct, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    unit: String? = null,
    error: String? = null,
    warning: String? = null,
    supporting: (@Composable () -> Unit)? = null,
    decimal: Boolean = true,
) {
    val errorColor = MaterialTheme.colorScheme.tertiary
    val supportContent: (@Composable () -> Unit)? = when {
        error != null -> @Composable { Text(error) }
        warning != null -> @Composable {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = errorColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(warning, color = errorColor)
            }
        }
        else -> supporting
    }
    val suffixContent: (@Composable () -> Unit)? = if (unit != null) @Composable { Text(unit) } else null
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' || it == ',' }) },
        label = { Text(label) },
        suffix = suffixContent,
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        supportingText = supportContent,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Loads a local photo off the main thread, downsampled for display. */
@Composable
fun LocalPhoto(file: File?, description: String, modifier: Modifier = Modifier, maxSide: Int = 600) {
    val bitmap by produceState<Bitmap?>(null, file?.path, file?.lastModified()) {
        value = file?.takeIf { it.exists() }?.let { f ->
            withContext(Dispatchers.IO) {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, b)
                var s = 1
                while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= maxSide) s *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
            }
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Text(if (file == null) "No photo" else "Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
    }
}

/** A short explanatory banner (info or warning) with icon + text. */
@Composable
fun Notice(text: String, warning: Boolean = false, modifier: Modifier = Modifier) {
    val bg = if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (warning) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(if (warning) Icons.Filled.Warning else Icons.Filled.Info, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}
