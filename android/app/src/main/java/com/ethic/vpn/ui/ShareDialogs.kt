package com.ethic.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ethic.vpn.R

/** The router export: XKeen's outbounds and routing files, with how to put them on a Keenetic. */
@Composable
fun RouterExportDialog(outbounds: String, routing: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Brand.Background).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.router_title), color = Brand.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
            Text(stringResource(R.string.router_steps), color = Brand.TextMuted, fontSize = 13.sp)
            CodeBlock("04_outbounds.json", outbounds, context)
            CodeBlock("05_routing.json", routing, context)
            TextButton(onClick = {
                val text = "// 04_outbounds.json\n$outbounds\n\n// 05_routing.json\n$routing"
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }) { Text(stringResource(R.string.router_share)) }
            Text(stringResource(R.string.router_note), color = Brand.TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CodeBlock(name: String, text: String, context: Context) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.Surface).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Brand.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(name, text))
                Toast.makeText(context, context.getString(R.string.copied, name), Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.copy)) }
        }
        Text(
            text, color = Brand.TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
        )
    }
}
