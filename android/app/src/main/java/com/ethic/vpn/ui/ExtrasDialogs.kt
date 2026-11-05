package com.ethic.vpn.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ethic.vpn.R
import com.ethic.vpn.core.TrafficHistory
import com.ethic.vpn.core.XrayConfig
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Picks the resolver the VPN asks for site addresses; [onSave] gets the choice and the custom address. */
@Composable
fun DnsDialog(choice: String, custom: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var picked by remember { mutableStateOf(choice) }
    var draft by remember { mutableStateOf(custom) }
    val options = listOf(
        "" to (stringResource(R.string.dns_auto) to stringResource(R.string.dns_auto_hint)),
        "cloudflare" to ("Cloudflare" to stringResource(R.string.dns_cloudflare_hint)),
        "google" to ("Google" to "8.8.8.8"),
        "adguard" to ("AdGuard" to stringResource(R.string.dns_adguard_hint)),
        "custom" to (stringResource(R.string.dns_custom) to stringResource(R.string.dns_custom_hint)),
    )
    val valid = picked != "custom" || XrayConfig.customDns(draft) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DNS") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((id, label) in options) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { picked = id }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label.first, color = Brand.Text)
                            Text(label.second, color = Brand.TextMuted, fontSize = 12.sp)
                        }
                        if (picked == id) Icon(Icons.Rounded.Check, null, tint = Brand.Cyan)
                    }
                }
                if (picked == "custom") {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it }, singleLine = true,
                        placeholder = { Text("9.9.9.9") }, isError = !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(R.string.dns_footer), color = Brand.TextMuted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(picked, draft.trim()) }, enabled = valid) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun dnsTitle(choice: String): String = when (choice) {
    "cloudflare" -> "Cloudflare"
    "google" -> "Google"
    "adguard" -> "AdGuard"
    "custom" -> stringResource(R.string.dns_custom)
    else -> stringResource(R.string.dns_auto)
}

/**
 * Android can't be told by an app to block traffic without the VPN; the system setting does it
 * ("Always-on VPN" + "Block connections without VPN"), so explain and open it.
 */
@Composable
fun KillSwitchDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kill_switch)) },
        text = { Text(stringResource(R.string.kill_switch_text)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            }) { Text(stringResource(R.string.vpn_denied_settings)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Traffic through the VPN: today, a week, a month, and a bar per day for the last week. */
@Composable
fun StatsDialog(history: TrafficHistory, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Brand.Background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.stats_title), color = Brand.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.stats_today), history.total(1), Modifier.weight(1f))
                StatTile(stringResource(R.string.stats_week), history.total(7), Modifier.weight(1f))
                StatTile(stringResource(R.string.stats_month), history.total(30), Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Surface).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(history.blocked(1).toString(), color = Brand.Green, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.ads_blocked_today), color = Brand.TextMuted, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.ads_blocked_days, 7, history.blocked(7)), color = Brand.TextMuted, fontSize = 12.sp)
                    Text(stringResource(R.string.ads_blocked_days, 30, history.blocked(30)), color = Brand.TextMuted, fontSize = 12.sp)
                }
            }
            Text(stringResource(R.string.stats_last_week), color = Brand.TextMuted, fontSize = 13.sp)
            val days = history.daily(7)
            val peak = maxOf(days.maxOf { it.total }, 1L)
            val today = LocalDate.now()
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brand.Surface).padding(12.dp).height(190.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (day in days) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (day.total > 0) formatBytes(day.total) else "", color = Brand.TextMuted, fontSize = 8.sp, maxLines = 1)
                        Box(
                            Modifier.fillMaxWidth().height((4 + 130 * day.total / peak).toInt().dp).clip(RoundedCornerShape(5.dp))
                                .background(if (day.day == today) Brand.Cyan else Brand.Cyan.copy(alpha = 0.45f)),
                        )
                        Text(day.day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = Brand.TextMuted, fontSize = 11.sp)
                    }
                }
            }
            Text(stringResource(R.string.stats_hint), color = Brand.TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun StatTile(title: String, total: Pair<Long, Long>, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Brand.Surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Brand.TextMuted, fontSize = 12.sp)
        Text(formatBytes(total.first + total.second), color = Brand.Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("↓ " + formatBytes(total.first), color = Brand.TextMuted, fontSize = 11.sp, maxLines = 1)
        Text("↑ " + formatBytes(total.second), color = Brand.TextMuted, fontSize = 11.sp, maxLines = 1)
    }
}

/** Three slides on the first launch: what the app does, how to add a subscription, Russian sites. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    data class Slide(val icon: ImageVector, val color: Color, val title: String, val text: String)
    val slides = listOf(
        Slide(Icons.Rounded.Shield, Brand.Cyan, stringResource(R.string.onboarding_1_title), stringResource(R.string.onboarding_1_text)),
        Slide(Icons.Rounded.Link, Brand.Green, stringResource(R.string.onboarding_2_title), stringResource(R.string.onboarding_2_text)),
        Slide(Icons.Rounded.AltRoute, Brand.Amber, stringResource(R.string.onboarding_3_title), stringResource(R.string.onboarding_3_text)),
    )
    Column(
        Modifier.fillMaxSize().background(Brand.Background).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(targetState = page, modifier = Modifier.weight(1f), label = "slide") { i ->
            val slide = slides[i]
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                Box(Modifier.size(140.dp).clip(CircleShape).background(slide.color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(slide.icon, null, tint = slide.color, modifier = Modifier.size(68.dp))
                }
                Text(slide.title, color = Brand.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(slide.text, color = Brand.TextMuted, fontSize = 16.sp, textAlign = TextAlign.Center)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 20.dp)) {
            slides.indices.forEach { i ->
                Box(
                    Modifier.height(8.dp).width(if (i == page) 22.dp else 8.dp).clip(CircleShape)
                        .background(if (i == page) Brand.Cyan else Brand.SurfaceHigh)
                        .clickable { page = i },
                )
            }
        }
        Button(
            onClick = { if (page < slides.lastIndex) page++ else onFinish() },
            colors = ButtonDefaults.buttonColors(containerColor = Brand.Cyan, contentColor = Color.Black),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text(stringResource(if (page < slides.lastIndex) R.string.onboarding_next else R.string.onboarding_start), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(4.dp))
    }
}
