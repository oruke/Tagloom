package com.oruke.tagloom.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun TagloomChevronDown(
    modifier: Modifier = Modifier.size(12.dp),
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    strokeWidth: Dp = 1.5.dp,
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.38f)
            lineTo(w * 0.5f, h * 0.66f)
            lineTo(w * 0.78f, h * 0.38f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
