package com.ethic.vpn.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethic.vpn.R

/** Accepts a vless:// or trojan:// key, several keys, or a subscription URL. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(busy: Boolean, onDismiss: () -> Unit, onSubmit: (String) -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Brand.Surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.add_key), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Brand.Text)
            Text(stringResource(R.string.add_hint), color = Brand.TextMuted, fontSize = 13.sp)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.add_placeholder)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Brand.Cyan,
                    unfocusedBorderColor = Brand.SurfaceHigh,
                ),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.let { text = it.toString() }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.ContentPaste, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.paste))
                }
                OutlinedButton(onClick = onScan, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.QrCodeScanner, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.scan_qr))
                }
            }
            Button(
                onClick = { onSubmit(text) },
                enabled = text.isNotBlank() && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Cyan, contentColor = Brand.Background),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(12.dp))
        }
    }
}
