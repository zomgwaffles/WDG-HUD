package com.example.wdghud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// G5A37: Android-only presentation components. No BLE, GPS, API, or cache logic.
internal val HudBackground = Color(0xFF05090D)
internal val HudPanelBackground = Color(0xFF0A151B)
internal val HudCyan = Color(0xFF00EAF5)
internal val HudBlue = Color(0xFF3C9AFF)
internal val HudGreen = Color(0xFF48F5B1)
internal val HudRed = Color(0xFFFF6078)
internal val HudYellow = Color(0xFFFFD36C)
internal val HudText = Color(0xFFEAF8FA)
internal val HudMuted = Color(0xFF8DA9B1)
internal val HudBorder = Color(0xFF24505A)

@Composable
internal fun HudAppHeader(connected: Boolean) {
    val statusColor = if (connected) HudGreen else HudRed
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HudBackground)
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 11.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "WDG // HUD",
                    color = HudCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 27.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1
                )
                Text(
                    "CONTROL TERMINAL  /  WDGWARS",
                    color = HudBlue,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(7.dp))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (connected) "LINKED" else "OFFLINE",
                        color = statusColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("WATCH / BLE", color = HudMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(11.dp))
        // Bright angular PCB trace and short segmented blocks, echoing the watch's cyan frame.
        Canvas(Modifier.fillMaxWidth().height(9.dp)) {
            val y = size.height / 2f
            drawLine(HudCyan, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * .72f, y), strokeWidth = 2.dp.toPx())
            drawLine(HudBlue, androidx.compose.ui.geometry.Offset(size.width * .75f, y), androidx.compose.ui.geometry.Offset(size.width * .90f, y), strokeWidth = 2.dp.toPx())
            drawLine(HudCyan, androidx.compose.ui.geometry.Offset(size.width * .93f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 2.dp.toPx())
            drawRect(HudCyan, topLeft = androidx.compose.ui.geometry.Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(7.dp.toPx(), 7.dp.toPx()))
        }
    }
}

@Composable
internal fun HudPanel(
    title: String,
    index: String,
    accent: Color = HudCyan,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, HudBorder, RoundedCornerShape(5.dp))
            .background(HudPanelBackground, RoundedCornerShape(5.dp))
    ) {
        // Bright top-left and bottom-right corners; no animation or touch overlay.
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val l = 10.dp.toPx()
            val s = 2.dp.toPx()
            drawLine(accent, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(l, 0f), strokeWidth = s)
            drawLine(accent, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, l), strokeWidth = s)
            drawLine(accent, androidx.compose.ui.geometry.Offset(w - l, h), androidx.compose.ui.geometry.Offset(w, h), strokeWidth = s)
            drawLine(accent, androidx.compose.ui.geometry.Offset(w, h - l), androidx.compose.ui.geometry.Offset(w, h), strokeWidth = s)
        }
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(width = 5.dp, height = 13.dp).background(accent))
                Spacer(Modifier.width(9.dp))
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 0.35.sp
                )
                Text(index, color = HudMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = 1.dp, color = HudBorder)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
internal fun HudReadout(label: String, value: String, valueColor: Color = HudText) {
    Text(label, color = HudMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = .45.sp)
    Spacer(Modifier.height(3.dp))
    Text(value, color = valueColor, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
}

@Composable
internal fun HudAction(
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (destructive) HudRed else HudCyan
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) Color(0xFF421C29) else Color(0xFF053D48),
            contentColor = color,
            disabledContainerColor = Color(0xFF16252B),
            disabledContentColor = HudMuted
        ),
        border = BorderStroke(1.dp, if (enabled) color else HudBorder),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .4.sp,
            maxLines = 2
        )
    }
}

@Composable
internal fun HudSecondaryAction(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 45.dp),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, HudBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = HudBlue),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
