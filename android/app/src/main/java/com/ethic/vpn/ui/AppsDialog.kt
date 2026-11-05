package com.ethic.vpn.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.ethic.vpn.R
import com.ethic.vpn.core.RussianApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class InstalledApp(val packageName: String, val label: String)

/** Picks the apps that bypass the VPN; [onSave] gets the chosen package names. */
@Composable
fun AppsDialog(selected: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<InstalledApp>?>(null) {
        value = withContext(Dispatchers.IO) { launcherApps(context.packageManager, context.packageName) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Brand.Background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.apps_title), color = Brand.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { onSave(chosen) }) { Text(stringResource(R.string.done)) }
            }
            Text(
                stringResource(R.string.apps_hint),
                color = Brand.TextMuted, fontSize = 12.sp,
            )
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(onClick = {
                    chosen = chosen + apps.orEmpty().map { it.packageName }.filter(RussianApps::isRussian)
                }) { Text(stringResource(R.string.apps_mark_russian)) }
                TextButton(onClick = { chosen = emptySet() }) { Text(stringResource(R.string.apps_clear), color = Brand.TextMuted) }
            }

            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Cyan) }
            } else {
                // Chosen apps first, so the selection is visible at a glance; the order is fixed when the list opens.
                val ordered = remember(list) { list.sortedBy { it.packageName !in selected } }
                val shown = ordered.filter {
                    query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.packageName }) { app ->
                        AppRow(app, checked = app.packageName in chosen) { on ->
                            chosen = if (on) chosen + app.packageName else chosen - app.packageName
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var icon by remember(app.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.packageName) {
        icon = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(app.label, color = Brand.Text, maxLines = 1)
            Text(app.packageName, color = Brand.TextMuted, fontSize = 11.sp, maxLines = 1)
        }
        Checkbox(
            checked = checked, onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = Brand.Cyan),
        )
    }
}

private fun launcherApps(pm: PackageManager, own: String): List<InstalledApp> =
    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != own }
        .map { InstalledApp(it.packageName, it.loadLabel(pm).toString()) }
        .sortedBy { it.label.lowercase() }
