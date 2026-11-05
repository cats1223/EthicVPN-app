package com.ethic.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethic.vpn.R
import com.ethic.vpn.data.ProfilesState
import com.ethic.vpn.data.StoredProfile

@Composable
fun ServersScreen(
    state: ProfilesState,
    pings: Map<String, Long>,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPing: () -> Unit,
    onRefresh: () -> Unit,
    onRemoveSubscription: () -> Unit,
    onAdd: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.tab_servers),
                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Brand.Text,
                modifier = Modifier.weight(1f),
            )
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Brand.Cyan, strokeWidth = 2.dp)
            IconButton(onClick = onPing) { Icon(Icons.Rounded.NetworkCheck, stringResource(R.string.ping), tint = Brand.Cyan) }
            if (state.subscriptionUrl.isNotEmpty()) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh_subscription), tint = Brand.Cyan)
                }
                IconButton(onClick = onRemoveSubscription) {
                    Icon(Icons.Rounded.LinkOff, stringResource(R.string.remove_subscription), tint = Brand.TextMuted)
                }
            }
            IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, stringResource(R.string.add_key), tint = Brand.Cyan) }
        }

        if (state.profiles.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_servers), color = Brand.TextMuted)
            }
            return
        }

        val selectedId = state.selected?.id
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.profiles, key = { it.profile.id }) { item ->
                ServerRow(item, item.profile.id == selectedId, pings[item.profile.id], onSelect, onRemove)
            }
        }
    }
}

@Composable
private fun ServerRow(
    item: StoredProfile,
    selected: Boolean,
    ping: Long?,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val p = item.profile
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Brand.Surface)
            .border(1.5.dp, if (selected) Brand.Cyan else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable { onSelect(p.id) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).clip(CircleShape).background(if (selected) Brand.Cyan else Brand.SurfaceHigh),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, color = Brand.Text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                "${p.network.uppercase()} · ${p.security.uppercase()}",
                color = Brand.TextMuted, fontSize = 12.sp,
            )
        }
        ping?.let {
            Text(
                if (it < 0) "—" else stringResource(R.string.ping_ms, it),
                color = when {
                    it < 0 -> Brand.Red
                    it < 150 -> Brand.Green
                    it < 400 -> Brand.Amber
                    else -> Brand.Red
                },
                fontSize = 13.sp,
            )
        }
        // Subscription servers come back on the next refresh, so only manual keys can be deleted.
        if (!item.fromSubscription) {
            IconButton(onClick = { onRemove(p.id) }) {
                Icon(Icons.Rounded.Delete, stringResource(R.string.delete), tint = Brand.TextMuted)
            }
        }
    }
}
