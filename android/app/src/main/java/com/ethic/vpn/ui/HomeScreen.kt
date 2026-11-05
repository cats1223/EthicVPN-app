package com.ethic.vpn.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import com.ethic.vpn.core.GeoIP
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethic.vpn.R
import com.ethic.vpn.core.SubscriptionInfo
import com.ethic.vpn.data.ProfilesState
import com.ethic.vpn.vpn.VpnState
import kotlinx.coroutines.delay

/**
 * The map fills the screen and shows where sites see you: your own location before the VPN,
 * the server's after. A panel at the bottom holds the status, the button and the servers, and
 * pulls up for the subscription, all servers and settings.
 */
@Composable
fun HomeScreen(
    state: ProfilesState,
    vpn: VpnState,
    homeLocation: GeoIP.Location?,
    visibleLocation: GeoIP.Location?,
    serverLocations: Map<String, GeoIP.Location>,
    pings: Map<String, Long>,
    traffic: MainViewModel.Traffic,
    onPing: () -> Unit,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val connected = vpn is VpnState.Connected
    val server = state.selected?.let { serverLocations[it.address] }
    // Ping the servers for the chips; again whenever the list changes.
    LaunchedEffect(state.profiles.map { it.profile.id }) { onPing() }
    var expanded by rememberSaveable { mutableStateOf(false) }

    val pins = buildList {
        homeLocation?.let { add(MapPin(it.latitude, it.longitude, "${it.flag} " + stringResource(if (connected) R.string.pin_real else R.string.pin_here), false)) }
        server?.let {
            add(MapPin(it.latitude, it.longitude, "${it.flag} " + if (connected) stringResource(R.string.pin_now) else state.selected?.name.orEmpty(), connected))
        }
    }
    val focus = if (connected) server ?: homeLocation else homeLocation ?: server

    Box(Modifier.fillMaxSize()) {
        WorldMap(
            centerLat = focus?.latitude ?: 55.75,
            centerLon = focus?.longitude ?: 37.62,
            pins = pins,
            line = if (connected && pins.size == 2) pins[0] to pins[1] else null,
            dark = !LocalLightTheme.current,
            modifier = Modifier.fillMaxSize(),
        )

        StatusPill(vpn, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp))

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Brand.Surface.copy(alpha = 0.88f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(32.dp))
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -12) expanded = true
                        if (dragAmount > 12) expanded = false
                    }
                }
                .animateContentSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(40.dp, 5.dp).clip(CircleShape).background(Brand.TextMuted.copy(alpha = 0.6f)))
            }

            StatusCard(state, vpn, homeLocation, visibleLocation, traffic, onToggle, onAdd)

            if (state.profiles.isNotEmpty()) {
                Text(stringResource(R.string.servers_title), color = Brand.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.profiles.forEach { item ->
                        val p = item.profile
                        val selected = p.id == state.selected?.id
                        val scale by animateFloatAsState(if (selected) 1f else 0.95f, spring(dampingRatio = 0.5f), label = "chip")
                        Row(
                            Modifier
                                .scale(scale)
                                .height(46.dp)
                                .clip(CircleShape)
                                .background(if (selected) Brand.Cyan.copy(alpha = 0.22f) else Brand.SurfaceHigh)
                                .border(1.dp, if (selected) Brand.Cyan else Color.Transparent, CircleShape)
                                .clickable { onSelect(p.id) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(leadingFlag(p.name) ?: serverLocations[p.address]?.flag ?: "\uD83C\uDF10", fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stripFlag(p.name), color = if (selected) Brand.Cyan else Brand.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            pings[p.id]?.let { ms ->
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (ms > 0) stringResource(R.string.ping_ms, ms) else "—",
                                    fontSize = 12.sp,
                                    color = when {
                                        ms <= 0 -> Brand.Red
                                        ms < 150 -> Brand.Green
                                        ms < 400 -> Brand.Amber
                                        else -> Brand.Red
                                    },
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    state.subscriptionInfo?.let { SubscriptionCard(it) }
                    Column(Modifier.clip(RoundedCornerShape(24.dp)).background(Brand.SurfaceHigh)) {
                        MenuRow(Icons.Rounded.Dns, stringResource(R.string.all_servers), onOpenServers)
                        MenuRow(Icons.Rounded.Settings, stringResource(R.string.tab_settings), onOpenSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(vpn: VpnState, modifier: Modifier) {
    val (text, color) = when (vpn) {
        is VpnState.Connected -> stringResource(R.string.status_connected) to Brand.Green
        is VpnState.Connecting -> stringResource(R.string.status_connecting) to Brand.Amber
        is VpnState.Error -> stringResource(R.string.status_error) to Brand.Red
        VpnState.Disconnected -> stringResource(R.string.status_disconnected) to Brand.Red
    }
    val dot by animateColorAsState(color, tween(400), label = "dot")
    // While connecting the dot sends out rings.
    val ring = rememberInfiniteTransition(label = "ring")
    val ringScale by ring.animateFloat(1f, 2.6f, infiniteRepeatable(tween(1100, easing = LinearOutSlowInEasing)), label = "scale")
    val ringAlpha by ring.animateFloat(0.8f, 0f, infiniteRepeatable(tween(1100, easing = LinearOutSlowInEasing)), label = "alpha")
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.75f)).animateContentSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (vpn is VpnState.Connecting) {
                Box(Modifier.size(12.dp).scale(ringScale).alpha(ringAlpha).clip(CircleShape).background(dot))
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        }
        Spacer(Modifier.width(10.dp))
        AnimatedContent(text, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "pill") {
            Text(it, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatusCard(
    state: ProfilesState,
    vpn: VpnState,
    homeLocation: GeoIP.Location?,
    visibleLocation: GeoIP.Location?,
    traffic: MainViewModel.Traffic,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
) {
    val connected = vpn is VpnState.Connected
    val connecting = vpn is VpnState.Connecting
    val border by animateColorAsState(if (connected) Brand.Green.copy(alpha = 0.6f) else Color.Transparent, tween(500), label = "border")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brand.SurfaceHigh)
            .border(1.5.dp, border, RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A ring bursts out of the shield once when the connection comes up.
            val burst = remember { Animatable(1f) }
            LaunchedEffect(connected) {
                if (connected) {
                    burst.snapTo(0f)
                    burst.animateTo(1f, tween(900, easing = LinearOutSlowInEasing))
                }
            }
            val green = Brand.Green
            Box(
                Modifier.size(40.dp).drawBehind {
                    if (burst.value < 1f) {
                        drawCircle(
                            green.copy(alpha = 1f - burst.value),
                            radius = size.minDimension / 2 * (0.6f + 1.6f * burst.value),
                            style = Stroke(width = 3.dp.toPx()),
                        )
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = when {
                        connected -> 2
                        connecting -> 1
                        else -> 0
                    },
                    transitionSpec = {
                        (scaleIn(spring(dampingRatio = 0.5f), initialScale = 0.4f) + fadeIn()) togetherWith (scaleOut() + fadeOut())
                    },
                    label = "icon",
                ) { status ->
                    val pulse = rememberInfiniteTransition(label = "pulse")
                    val alpha by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
                    Icon(
                        when (status) {
                            2 -> Icons.Rounded.VerifiedUser
                            1 -> Icons.Rounded.Shield
                            else -> Icons.Rounded.WarningAmber
                        },
                        null,
                        tint = when (status) {
                            2 -> Brand.Green
                            1 -> Brand.Amber
                            else -> Brand.TextMuted
                        },
                        modifier = Modifier.size(34.dp).alpha(if (status == 1) alpha else 1f),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                AnimatedContent(
                    when {
                        connected -> stringResource(R.string.protected_title)
                        connecting -> stringResource(R.string.status_connecting)
                        else -> stringResource(R.string.unprotected_title)
                    },
                    transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith fadeOut() },
                    label = "title",
                ) { title ->
                    Text(title, color = Brand.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                }
                val subtitle = when {
                    vpn is VpnState.Error -> vpn.message
                    connected -> {
                        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(vpn) {
                            while (true) {
                                now = System.currentTimeMillis()
                                delay(1000)
                            }
                        }
                        locationLine(visibleLocation, stringResource(R.string.sites_see), stringResource(R.string.locating)) + " · " + formatDuration((now - (vpn as VpnState.Connected).since) / 1000)
                    }
                    homeLocation != null -> locationLine(homeLocation, stringResource(R.string.your_address), "")
                    else -> stringResource(R.string.connect_hint)
                }
                Text(subtitle, color = Brand.TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedVisibility(connected, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            // Download and upload speed right now, and what the session has used.
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("↓ " + stringResource(R.string.rate, formatBytes(traffic.down)), color = Brand.Text, fontSize = 13.sp)
                Spacer(Modifier.width(14.dp))
                Text("↑ " + stringResource(R.string.rate, formatBytes(traffic.up)), color = Brand.Text, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text(formatBytes(traffic.session), color = Brand.TextMuted, fontSize = 13.sp)
            }
        }
        val (label, color, action) = when {
            state.selected == null -> Triple(stringResource(R.string.add_key), Brand.Cyan, onAdd)
            connected -> Triple(stringResource(R.string.action_disconnect), Brand.Red, onToggle)
            connecting -> Triple(stringResource(R.string.cancel), Brand.Red, onToggle)
            else -> Triple(stringResource(R.string.action_connect), Brand.Cyan, onToggle)
        }
        val buttonColor by animateColorAsState(color, tween(450), label = "button")
        val view = LocalView.current
        // Only real transitions: opening the app starts from whatever state the VPN is in.
        var previous by remember { mutableStateOf(vpn) }
        LaunchedEffect(vpn) {
            val before = previous
            previous = vpn
            when {
                vpn is VpnState.Connected && before is VpnState.Connecting -> Feedback.connected(view)
                vpn is VpnState.Error && before !is VpnState.Error -> Feedback.failed(view)
                vpn is VpnState.Disconnected && (before is VpnState.Connected || before is VpnState.Connecting) -> Feedback.disconnected(view)
            }
        }
        Button(
            onClick = {
                Feedback.tap(view)
                action()
            },
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor, contentColor = Color.White),
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Brand.Text)
        Spacer(Modifier.width(14.dp))
        Text(title, color = Brand.Text, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, null, tint = Brand.TextMuted)
    }
}

private fun locationLine(location: GeoIP.Location?, prefix: String, locating: String): String =
    if (location == null) "$prefix: $locating" else "$prefix: ${location.flag} ${location.place}"

/** A flag emoji at the start of a server name, like "🇳🇱 Нидерланды". */
private fun leadingFlag(name: String): String? {
    if (name.length < 4) return null
    val a = name.codePointAt(0)
    val b = name.codePointAt(Character.charCount(a))
    return if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) name.substring(0, 4) else null
}

private fun stripFlag(name: String): String = if (leadingFlag(name) != null) name.substring(4).trim() else name

@Composable
private fun SubscriptionCard(info: SubscriptionInfo) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                info.title.ifEmpty { stringResource(R.string.subscription) },
                color = Brand.Text, fontWeight = FontWeight.SemiBold,
            )
            val used = info.upload + info.download
            if (info.total > 0) {
                LinearProgressIndicator(
                    progress = { (used.toFloat() / info.total).coerceIn(0f, 1f) },
                    color = Brand.Cyan,
                    trackColor = Brand.SurfaceHigh,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Text(
                    stringResource(R.string.traffic_of, formatBytes(used), formatBytes(info.total)),
                    color = Brand.TextMuted, fontSize = 13.sp,
                )
            } else {
                Text(stringResource(R.string.traffic_used, formatBytes(used)), color = Brand.TextMuted, fontSize = 13.sp)
            }
            if (info.expire > 0) {
                val days = ((info.expire * 1000 - System.currentTimeMillis()) / 86_400_000).coerceAtLeast(0)
                Text(
                    stringResource(R.string.expires_in_days, days),
                    color = if (days <= 3) Brand.Amber else Brand.TextMuted, fontSize = 13.sp,
                )
            }
            // A message the panel sends to everyone (Remnawave "announce").
            if (info.announce.isNotBlank()) Text("📣 " + info.announce, color = Brand.Cyan, fontSize = 13.sp)
        }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).then(modifier).background(Brand.Surface).padding(16.dp),
    ) { content() }
}

fun formatDuration(seconds: Long): String =
    "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)

fun formatBytes(bytes: Long): String {
    val units = if (java.util.Locale.getDefault().language == "ru") listOf("Б", "КБ", "МБ", "ГБ", "ТБ") else listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (i == 0) "$bytes ${units[0]}" else "%.1f %s".format(value, units[i])
}
