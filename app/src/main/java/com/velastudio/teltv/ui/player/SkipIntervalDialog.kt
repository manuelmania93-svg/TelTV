package com.velastudio.teltv.ui.player

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.velastudio.teltv.player.SeekSteps

@Composable
internal fun SkipIntervalDialog(selectedMs: Long, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
    val selectedFocus = remember { FocusRequester() }
    val current = SeekSteps.normalize(selectedMs)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(320.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState()).background(Color(0xFF222222), RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Skip interval", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("Used by the skip forward and skip back buttons.",
                style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            SeekSteps.choicesMs.forEach { ms ->
                Button(
                    onClick = { onSelect(ms) },
                    modifier = Modifier.fillMaxWidth().then(
                        if (ms == current) Modifier.focusRequester(selectedFocus) else Modifier
                    )
                ) {
                    Text((if (ms == current) "Selected: " else "") + SeekSteps.label(ms))
                }
            }
        }
        LaunchedEffect(Unit) { selectedFocus.requestFocus() }
    }
}
