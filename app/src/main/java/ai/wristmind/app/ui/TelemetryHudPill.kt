package ai.wristmind.app.ui

import ai.wristmind.app.edge.DeviceMemoryTelemetry
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Always-on top wrist telemetry pill displaying live `/proc/meminfo` + PSS RAM residency,
 * active local/cloud model tier, System-1 router latency, and one-tap Offline/Online toggle.
 */
@Composable
fun TelemetryHudPill(
    memory: DeviceMemoryTelemetry,
    activeModelShort: String,
    tierBadge: String,
    sysOneLatencyMs: Long?,
    isOnlineSimulated: Boolean,
    onToggleOnlineMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = when {
        !isOnlineSimulated -> WristColors.EmeraldEdge
        tierBadge.startsWith("CLOUD") -> WristColors.CyanCloud
        tierBadge.contains("BLOCK") -> WristColors.CoralDanger
        else -> WristColors.EmeraldEdge
    }

    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(WristColors.CardSurfaceElevated)
            .border(1.5.dp, accentColor.copy(alpha = 0.7f), shape)
            .clickable { onToggleOnlineMode() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val sys1Part = sysOneLatencyMs?.let { " • S1 ${it}ms" }.orEmpty()
        Text(
            text = "${memory.residencyMode.badge} • ${memory.appPssMb}MB PSS • $activeModelShort$sys1Part",
            style = MaterialTheme.typography.labelSmall,
            color = WristColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Text(
            text = if (isOnlineSimulated) "HYBRID" else "AIRPLANE",
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
