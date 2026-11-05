package com.ethic.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.ethic.vpn.BuildConfig
import com.ethic.vpn.R

/** "What's new" after an update: the version's section of CHANGELOG.md. */
@Composable
fun WhatsNewDialog(notes: String, onClose: () -> Unit) {
    // Bullets and paragraphs; a bullet's continuation lines are joined back to it.
    val paragraphs = buildList<String> {
        for (raw in notes.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (raw.startsWith("  ") && isNotEmpty()) add(removeAt(lastIndex) + " " + line) else add(line)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.whats_new_title, BuildConfig.VERSION_NAME)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (p in paragraphs) {
                    if (p.startsWith("- ")) {
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(Brand.Cyan))
                            Spacer(Modifier.width(10.dp))
                            Text(bold(p.removePrefix("- ")))
                        }
                    } else {
                        Text(bold(p))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.whats_new_ok)) } },
    )
}

/** **bold** markers as bold text; `code` marks are dropped. */
private fun bold(s: String) = buildAnnotatedString {
    val parts = s.replace("`", "").split("**")
    parts.forEachIndexed { i, part -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part) }
}
