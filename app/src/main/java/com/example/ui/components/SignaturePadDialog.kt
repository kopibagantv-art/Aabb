package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class SignatureStroke(
    val points: List<Offset>,
    val color: Color,
    val widthPx: Float
)

@Composable
fun SignaturePadDialog(
    onConfirmSignature: (Bitmap) -> Unit,
    onDismiss: () -> Unit
) {
    val inkColors = listOf(
        Color(0xFF0F172A), // Ink Black
        Color(0xFF1565C0), // Sapphire Blue
        Color(0xFFB91C1C)  // Stamp Crimson
    )
    var selectedColor by remember { mutableStateOf(inkColors[1]) }
    var strokeWidth by remember { mutableFloatStateOf(9f) }
    val strokes = remember { mutableStateListOf<SignatureStroke>() }
    var currentPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Digital Signature", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Draw your signature below",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close signature pad")
                    }
                }

                // Drawing Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        .onSizeChanged { canvasSize = it }
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(selectedColor, strokeWidth) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        currentPoints = listOf(offset)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        currentPoints = currentPoints + change.position
                                    },
                                    onDragEnd = {
                                        if (currentPoints.isNotEmpty()) {
                                            strokes.add(SignatureStroke(currentPoints, selectedColor, strokeWidth))
                                            currentPoints = emptyList()
                                        }
                                    },
                                    onDragCancel = {
                                        currentPoints = emptyList()
                                    }
                                )
                            }
                    ) {
                        // Baseline guide
                        drawLine(
                            color = Color(0xFFE2E8F0),
                            start = Offset(24f, size.height * 0.78f),
                            end = Offset(size.width - 24f, size.height * 0.78f),
                            strokeWidth = 2f
                        )

                        fun drawSingleStroke(pts: List<Offset>, col: Color, w: Float) {
                            if (pts.isEmpty()) return
                            if (pts.size == 1) {
                                drawCircle(color = col, radius = w / 2f, center = pts.first())
                            } else {
                                val path = Path().apply {
                                    moveTo(pts.first().x, pts.first().y)
                                    for (i in 1 until pts.size) {
                                        val prev = pts[i - 1]
                                        val curr = pts[i]
                                        val mid = Offset((prev.x + curr.x) / 2f, (prev.y + curr.y) / 2f)
                                        quadraticTo(prev.x, prev.y, mid.x, mid.y)
                                    }
                                    lineTo(pts.last().x, pts.last().y)
                                }
                                drawPath(
                                    path = path,
                                    color = col,
                                    style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                            }
                        }

                        strokes.forEach { s -> drawSingleStroke(s.points, s.color, s.widthPx) }
                        drawSingleStroke(currentPoints, selectedColor, strokeWidth)
                    }

                    if (strokes.isEmpty() && currentPoints.isEmpty()) {
                        Text(
                            text = "Sign here",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                // Controls row: Ink colors + Stroke width
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        inkColors.forEach { col ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(col)
                                    .border(
                                        width = if (col == selectedColor) 3.dp else 1.dp,
                                        color = if (col == selectedColor) MaterialTheme.colorScheme.primary else Color.LightGray,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedColor = col }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) },
                            enabled = strokes.isNotEmpty()
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo stroke")
                        }
                        IconButton(
                            onClick = { strokes.clear() },
                            enabled = strokes.isNotEmpty()
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear signature")
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Thickness", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = strokeWidth,
                        onValueChange = { strokeWidth = it },
                        valueRange = 4f..18f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val bmp = renderSignatureBitmap(strokes.toList(), canvasSize)
                            if (bmp != null) onConfirmSignature(bmp)
                        },
                        enabled = strokes.isNotEmpty() && canvasSize.width > 0 && canvasSize.height > 0,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("confirm_signature_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Use Signature")
                    }
                }
            }
        }
    }
}

private fun renderSignatureBitmap(strokes: List<SignatureStroke>, size: IntSize): Bitmap? {
    if (strokes.isEmpty() || size.width <= 0 || size.height <= 0) return null

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var maxStroke = 10f

    for (s in strokes) {
        maxStroke = maxOf(maxStroke, s.widthPx)
        for (p in s.points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
    }

    val pad = maxStroke + 12f
    val left = (minX - pad).coerceAtLeast(0f)
    val top = (minY - pad).coerceAtLeast(0f)
    val right = (maxX + pad).coerceAtMost(size.width.toFloat())
    val bottom = (maxY + pad).coerceAtMost(size.height.toFloat())

    val outW = (right - left).toInt().coerceAtLeast(64)
    val outH = (bottom - top).toInt().coerceAtLeast(48)

    val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)

    for (s in strokes) {
        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = s.color.toArgb()
            style = AndroidPaint.Style.STROKE
            strokeWidth = s.widthPx
            strokeCap = AndroidPaint.Cap.ROUND
            strokeJoin = AndroidPaint.Join.ROUND
        }
        val pts = s.points
        if (pts.size == 1) {
            paint.style = AndroidPaint.Style.FILL
            canvas.drawCircle(pts[0].x - left, pts[0].y - top, s.widthPx / 2f, paint)
        } else {
            val path = AndroidPath().apply {
                moveTo(pts[0].x - left, pts[0].y - top)
                for (i in 1 until pts.size) {
                    val prev = pts[i - 1]
                    val curr = pts[i]
                    quadTo(
                        prev.x - left,
                        prev.y - top,
                        ((prev.x + curr.x) / 2f) - left,
                        ((prev.y + curr.y) / 2f) - top
                    )
                }
                lineTo(pts.last().x - left, pts.last().y - top)
            }
            canvas.drawPath(path, paint)
        }
    }
    return bmp
}
