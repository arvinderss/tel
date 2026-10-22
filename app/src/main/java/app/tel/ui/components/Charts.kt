package app.tel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** One chart in a card: title, y-range labels, the plot, and first/last x labels. */
@Composable
fun ChartCard(
    title: String,
    values: List<Double>,
    xLabels: Pair<String, String>,
    format: (Double) -> String,
    summary: String,
    bars: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$title. $summary" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (values.size < 2 && !bars) {
                Text("Needs at least two data points.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
            val max = values.maxOrNull() ?: 0.0
            val min = if (bars) 0.0 else values.minOrNull() ?: 0.0
            Row(Modifier.fillMaxWidth().height(140.dp)) {
                Column(Modifier.fillMaxHeight().width(64.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(format(max), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(format(min), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (bars) BarPlot(values, Modifier.weight(1f).fillMaxHeight()) else LinePlot(values, min, max, Modifier.weight(1f).fillMaxHeight())
            }
            Row(Modifier.fillMaxWidth().padding(start = 64.dp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(xLabels.first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(xLabels.second, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
        }
    }
}

@Composable
private fun LinePlot(values: List<Double>, min: Double, max: Double, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        val pad = 6.dp.toPx()
        val h = size.height - pad * 2
        drawLine(grid, Offset(0f, pad), Offset(size.width, pad), 1f)
        drawLine(grid, Offset(0f, size.height - pad), Offset(size.width, size.height - pad), 1f)
        val step = if (values.size > 1) size.width / (values.size - 1) else 0f
        val pts = values.mapIndexed { i, v -> Offset(i * step, pad + (h - ((v - min) / span * h)).toFloat()) }
        val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))
        pts.forEach { drawCircle(line, radius = 3.dp.toPx(), center = it) }
    }
}

@Composable
private fun BarPlot(values: List<Double>, modifier: Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        val w = (slot * 0.6f).coerceAtMost(36.dp.toPx())
        values.forEachIndexed { i, v ->
            val bh = (v / max * size.height).toFloat()
            drawRoundRect(bar, topLeft = Offset(i * slot + (slot - w) / 2, size.height - bh), size = Size(w, bh), cornerRadius = CornerRadius(4.dp.toPx()))
        }
    }
}
