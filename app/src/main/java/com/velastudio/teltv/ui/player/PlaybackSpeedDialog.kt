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

@Composable
internal fun PlaybackSpeedDialog(selected: Float, onSelect: (Float) -> Unit, onDismiss: () -> Unit) {
    val selectedFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(320.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState()).background(Color(0xFF222222), RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Playback speed", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("Voice pitch stays normal. High speeds may stutter on some TVs.",
                style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            PlaybackSpeedPolicy.options.forEach { speed ->
                Button(
                    onClick = { onSelect(speed) },
                    modifier = Modifier.fillMaxWidth().then(
                        if (speed == selected) Modifier.focusRequester(selectedFocus) else Modifier
                    )
                ) {
                    Text((if (speed == selected) "Selected: " else "") + PlaybackSpeedPolicy.label(speed))
                }
            }
        }
        LaunchedEffect(Unit) { selectedFocus.requestFocus() }
    }
}
