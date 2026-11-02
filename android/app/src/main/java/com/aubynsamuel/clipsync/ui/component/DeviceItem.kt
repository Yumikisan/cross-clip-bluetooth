package com.aubynsamuel.clipsync.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aubynsamuel.clipsync.core.DeviceKind

/** Icon shown on the round badge in front of every device row. */
fun DeviceKind.icon(): ImageVector = when (this) {
    DeviceKind.PHONE -> Icons.Default.PhoneAndroid
    DeviceKind.TABLET -> Icons.Default.Tablet
    DeviceKind.COMPUTER -> Icons.Default.Computer
    DeviceKind.AUDIO -> Icons.Default.Headphones
    DeviceKind.OTHER -> Icons.Default.DevicesOther
}

/**
 * One device row: selection, a type badge, the name with its address underneath, an optional
 * "probably cannot sync" hint and the per-device settings entry point.
 */
@Composable
fun DeviceItem(
    onChecked: () -> Unit,
    checked: Boolean,
    name: String,
    address: String,
    kind: DeviceKind,
    onSettings: () -> Unit,
    hint: String? = null,
    /** "Connected · encrypted" and friends; null when the link is down. */
    status: String? = null,
    statusColor: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable(onClick = { onChecked() }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onChecked() }
        )

        // The "?" marker rides on the corner of the type bubble, so it has to be laid out
        // *outside* the clipped circle: anything a `.clip(CircleShape)` Box draws near its edge
        // gets cut off by the clip path. That is why the badge kept showing up as a small blob
        // squeezed between the icon and the name instead of a readable "?".
        Box(modifier = Modifier.size(36.dp)) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surfaceVariant)
            ) {
                Icon(
                    kind.icon(),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(17.dp),
                    tint = colorScheme.onSurfaceVariant
                )
            }

            if (hint != null) {
                // Headsets / unidentified peripherals almost never run this app.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(colorScheme.surface)
                        .border(1.dp, colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "?",
                        fontSize = 9.sp,
                        // A single glyph in a small circle: Compose's default font padding adds
                        // asymmetric space above the baseline, so the "?" sat visibly low.
                        lineHeight = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = name,
                color = colorScheme.onBackground,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Address and state share one line: the link state is what tells a user whether
            // the sync they are looking at is live, and a third line per row would have pushed
            // the list down for information that is usually the same on every row.
            Text(
                text = buildAnnotatedString {
                    append(address)
                    if (status != null) {
                        withStyle(
                            SpanStyle(
                                color = statusColor ?: colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        ) {
                            append(" · ")
                            append(status)
                        }
                    }
                },
                color = colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onSettings)
                .padding(7.dp),
            tint = colorScheme.onSurfaceVariant
        )
    }
}
