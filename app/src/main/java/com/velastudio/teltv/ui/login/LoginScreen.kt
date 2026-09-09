import androidx.compose.material3.OutlinedTextField
package com.velastudio.teltv.ui.login

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.velastudio.teltv.telegram.TelegramClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import timber.log.Timber

/**
 * Drives TDLib's QR-code / phone-number / code / 2FA-password authorization flow.
 *
 * This is also what actually starts the TDLib client: [TelegramClient.authorizationFlow] is what
 * calls `Client.create(...)` under the hood, and previously nothing in the app ever collected
 * it -- `client` stayed null forever and every other TDLib call would have failed with
 * "TDLib client not started". [MainActivity]'s NavHost now starts on this route rather than
 * "home" so that always happens, whether or not there's already a saved session.
 *
 * If TDLib already has a valid session on disk, the flow goes almost immediately from whatever
 * intermediate state to [TdApi.AuthorizationStateReady] and this screen is only visible for a
 * moment -- [onReady] is what the caller uses to navigate away once that happens.
 *
 * QR sign-in is the default, since typing a phone number and SMS code with a TV remote is
 * genuinely painful (same reasoning Telegram Desktop/TV clients use). [preferPhone] is
 * screen-local UI state, not TDLib state: TDLib's own [TelegramClient.authState] is still the
 * source of truth for what step we're actually on, `preferPhone` just controls which of the two
 * *presentations* we show for a given state (see the `when` block below).
 */
@Composable
fun LoginScreen(telegramClient: TelegramClient, onReady: () -> Unit) {
    val scope = rememberCoroutineScope()
    var authState by remember { mutableStateOf<TdApi.AuthorizationState?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var preferPhone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        telegramClient.authorizationFlow().collect { state ->
            errorMessage = null
            authState = state
            if (state is TdApi.AuthorizationStateReady) onReady()
        }
    }

    // Runs [action], clearing/setting isBusy and surfacing any thrown TDLib error (see
    // TelegramClient.send(), which turns a TdApi.Error into a thrown RuntimeException) --
    // covers things like "invalid phone number", "wrong code", "flood wait", etc.
    fun runAuthStep(action: suspend () -> Unit) {
        if (isBusy) return
        isBusy = true
        errorMessage = null
        scope.launch {
            runCatching { action() }
                .onFailure { errorMessage = it.message ?: "Something went wrong. Try again." }
            isBusy = false
        }
    }

    // Fires once whenever we land on AuthorizationStateWaitPhoneNumber (a fresh client, or a
    // logout) with QR still the preferred mode -- kicks off RequestQrCodeAuthentication so the
    // very first thing the user sees is a scannable code rather than an empty phone field.
    // Re-keyed on (authState's class, preferPhone) rather than the whole state object, since
    // some states (e.g. WaitOtherDeviceConfirmation) change their `link` field every ~30s and
    // we do NOT want to re-fire this for every one of those refreshes.
    LaunchedEffect(authState?.let { it::class }, preferPhone) {
        if (authState is TdApi.AuthorizationStateWaitPhoneNumber && !preferPhone) {
            runCatching { telegramClient.requestQrCodeAuthentication() }
                .onFailure { Timber.w(it, "Failed to start QR-code authentication") }
        }
    }

    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Sign in to Telegram", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))

            when (val state = authState) {
                is TdApi.AuthorizationStateWaitPhoneNumber -> if (preferPhone) {
                    PhoneNumberStep(
                        isBusy = isBusy,
                        errorMessage = errorMessage,
                        onSubmit = { phone -> runAuthStep { telegramClient.setPhoneNumber(phone) } },
                        onSwitchToQr = { preferPhone = false }
                    )
                } else {
                    // QR request was just fired by the LaunchedEffect above; TDLib hasn't
                    // replied with AuthorizationStateWaitOtherDeviceConfirmation (and its `link`)
                    // yet, so there's nothing to render as a code.
                    Text("Preparing QR code…", style = MaterialTheme.typography.bodyMedium)
                }

                is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> if (preferPhone) {
                    // User backed out of QR mode before finishing it. TDLib allows calling
                    // SetAuthenticationPhoneNumber directly from this state -- it switches the
                    // flow to phone-based and moves on to AuthorizationStateWaitCode itself, no
                    // separate "cancel QR" call needed.
                    PhoneNumberStep(
                        isBusy = isBusy,
                        errorMessage = errorMessage,
                        onSubmit = { phone -> runAuthStep { telegramClient.setPhoneNumber(phone) } },
                        onSwitchToQr = { preferPhone = false }
                    )
                } else {
                    QrCodeStep(
                        link = state.link,
                        onSwitchToPhone = { preferPhone = true }
                    )
                }

                is TdApi.AuthorizationStateWaitCode -> CodeStep(
                    codeInfo = state.codeInfo,
                    isBusy = isBusy,
                    errorMessage = errorMessage,
                    onSubmit = { code ->
                        runAuthStep { telegramClient.checkCode(code) }
                    }
                )

                is TdApi.AuthorizationStateWaitPassword -> PasswordStep(
                    hint = state.passwordHint,
                    isBusy = isBusy,
                    errorMessage = errorMessage,
                    onSubmit = { password ->
                        runAuthStep { telegramClient.checkPassword(password) }
                    }
                )

                is TdApi.AuthorizationStateClosed -> Text(
                    "The connection to Telegram closed unexpectedly. Restart the app to try again.",
                    style = MaterialTheme.typography.bodyMedium
                )

                // AuthorizationStateWaitTdlibParameters, AuthorizationStateReady (about to
                // navigate away via onReady), or null (flow hasn't emitted yet).
                else -> Text("Connecting to Telegram…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun QrCodeStep(
    link: String?,
    onSwitchToPhone: () -> Unit
) {
    Text("Scan to sign in", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))

    // Generated off the main thread (ZXing's matrix walk + per-pixel Bitmap writes are cheap
    // but there's no reason to risk a frame drop on a TV box for it). Re-runs whenever `link`
    // changes, which TDLib does on its own roughly every 30s to keep the token from expiring --
    // so the code on screen always matches what scanning it will actually do.
    val qrBitmap by produceState<Bitmap?>(initialValue = null, link) {
        value = link?.let { l ->
            withContext(Dispatchers.Default) {
                runCatching { qrCodeBitmap(l) }
                    .onFailure { Timber.e(it, "Failed to render QR code") }
                    .getOrNull()
            }
        }
    }

    Box(
        modifier = Modifier.size(240.dp).background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        val bmp = qrBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "QR code to sign in to Telegram",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("Generating…", color = Color.Black, style = MaterialTheme.typography.bodySmall)
        }
    }

    Spacer(Modifier.height(20.dp))
    Text(
        "On your phone: Settings → Devices → Link Desktop Device, then point your camera at this code.",
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = onSwitchToPhone) { Text("Use phone number instead") }
}

/** Renders [content] (a `tg://login?token=...` URL) as a black-on-white QR bitmap. */
private fun qrCodeBitmap(content: String, sizePx: Int = 512): Bitmap {
    val matrix: BitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    return bitmap
}

@Composable
private fun PhoneNumberStep(
    isBusy: Boolean,
    errorMessage: String?,
    onSubmit: (String) -> Unit,
    onSwitchToQr: () -> Unit
) {
    var phone by remember { mutableStateOf("") }

    Text(
        "Enter your phone number, including country code (e.g. +1 555 123 4567).",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        enabled = !isBusy,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (phone.isNotBlank()) onSubmit(phone) }),
        placeholder = { Text("+1 555 123 4567") },
        modifier = Modifier.fillMaxWidth()
    )
    ErrorText(errorMessage)
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { onSubmit(phone) },
        enabled = !isBusy && phone.isNotBlank()
    ) { Text(if (isBusy) "Sending…" else "Continue") }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onSwitchToQr, enabled = !isBusy) { Text("Use QR code instead") }
}

@Composable
private fun CodeStep(
    codeInfo: TdApi.AuthenticationCodeInfo?,
    isBusy: Boolean,
    errorMessage: String?,
    onSubmit: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }

    Text(codeDeliveryDescription(codeInfo), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        enabled = !isBusy,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank()) onSubmit(code) }),
        placeholder = { Text("12345") },
        modifier = Modifier.fillMaxWidth()
    )
    ErrorText(errorMessage)
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { onSubmit(code) },
        enabled = !isBusy && code.isNotBlank()
    ) { Text(if (isBusy) "Verifying…" else "Verify") }
}

@Composable
private fun PasswordStep(
    hint: String?,
    isBusy: Boolean,
    errorMessage: String?,
    onSubmit: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }

    Text(
        "Two-step verification is on for this account. Enter your password to continue.",
        style = MaterialTheme.typography.bodyMedium
    )
    if (!hint.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text("Hint: $hint", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        enabled = !isBusy,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (password.isNotBlank()) onSubmit(password) }),
        modifier = Modifier.fillMaxWidth()
    )
    ErrorText(errorMessage)
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { onSubmit(password) },
        enabled = !isBusy && password.isNotBlank()
    ) { Text(if (isBusy) "Checking…" else "Log in") }
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) {
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/** Friendly description of how the login code was sent, for the small set of common cases. */
private fun codeDeliveryDescription(codeInfo: TdApi.AuthenticationCodeInfo?): String {
    val base = "Enter the code"
    return when (codeInfo?.type) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> "$base sent to your Telegram app on another device."
        is TdApi.AuthenticationCodeTypeSms -> "$base sent to you by SMS."
        is TdApi.AuthenticationCodeTypeCall -> "$base you'll receive in a phone call."
        is TdApi.AuthenticationCodeTypeFlashCall -> "$base that appears as the incoming caller ID on the call you're about to receive."
        is TdApi.AuthenticationCodeTypeMissedCall -> "$base: the last digits of the number that just called you."
        else -> "$base you received."
    }
}
