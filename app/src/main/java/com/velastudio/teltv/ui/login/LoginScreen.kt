package com.velastudio.teltv.ui.login

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.velastudio.teltv.R
import com.velastudio.teltv.telegram.TelegramClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import timber.log.Timber

@OptIn(ExperimentalTvMaterial3Api::class)
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

    LaunchedEffect(authState?.let { it::class }, preferPhone) {
        if (authState is TdApi.AuthorizationStateWaitPhoneNumber && !preferPhone) {
            runCatching { telegramClient.requestQrCodeAuthentication() }
                .onFailure { Timber.w(it, "Failed to start QR-code authentication") }
        }
    }

    // While TDLib is initializing or confirming session: show clean Splash screen (no QR flashing)
    if (authState == null || authState is TdApi.AuthorizationStateWaitTdlibParameters || authState is TdApi.AuthorizationStateReady) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0E14)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.brand_poster),
                    contentDescription = "TelTV",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(190.dp)
                        .clip(RoundedCornerShape(24.dp))
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "TelTV",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF29B6F6)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Connecting to Telegram…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0BEC5)
                )
                Spacer(Modifier.height(20.dp))
                androidx.compose.material3.CircularProgressIndicator(
                    color = Color(0xFF29B6F6),
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp
                )
            }
        }
        return
    }

    // Genuinely logged out -> Show clean, proportional Sign-in UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .padding(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Left Column: TelTV Emblem
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.width(300.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.brand_poster),
                    contentDescription = "TelTV Emblem",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(20.dp))
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "TelTV",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF29B6F6)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Telegram Streaming for Android TV",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0BEC5)
                )
            }

            Spacer(Modifier.width(60.dp))

            // Right Column: QR Code / Phone Login
            Column(
                modifier = Modifier.widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Sign in to Telegram",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))

                val currentState = authState
                val isPhoneMode = preferPhone || currentState is TdApi.AuthorizationStateWaitCode || currentState is TdApi.AuthorizationStateWaitPassword
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Button(
                        onClick = {
                            preferPhone = false
                            if (currentState is TdApi.AuthorizationStateWaitPhoneNumber) {
                                scope.launch {
                                    runCatching { telegramClient.requestQrCodeAuthentication() }
                                }
                            }
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = if (!isPhoneMode) Color(0xFF004D73) else Color(0xFF1E2638),
                            focusedContainerColor = Color(0xFF29B6F6)
                        )
                    ) {
                        Text(if (!isPhoneMode) "● QR Code" else "QR Code", color = Color.White)
                    }
                    Button(
                        onClick = { preferPhone = true },
                        colors = ButtonDefaults.colors(
                            containerColor = if (isPhoneMode) Color(0xFF004D73) else Color(0xFF1E2638),
                            focusedContainerColor = Color(0xFF29B6F6)
                        )
                    ) {
                        Text(if (isPhoneMode) "● Phone Number" else "Phone Number", color = Color.White)
                    }
                }

                when (val state = authState) {
                    is TdApi.AuthorizationStateWaitPhoneNumber -> if (preferPhone) {
                        PhoneNumberStep(
                            isBusy = isBusy,
                            errorMessage = errorMessage,
                            onSubmit = { phone -> runAuthStep { telegramClient.setPhoneNumber(phone) } }
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Preparing QR code…", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { preferPhone = true }) {
                                Text("Sign in with Phone Number")
                            }
                        }
                    }

                    is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> if (preferPhone) {
                        PhoneNumberStep(
                            isBusy = isBusy,
                            errorMessage = errorMessage,
                            onSubmit = { phone -> runAuthStep { telegramClient.setPhoneNumber(phone) } }
                        )
                    } else {
                        QrCodeStep(
                            link = state.link,
                            onSwitchToPhone = { preferPhone = true },
                            onRefresh = {
                                scope.launch {
                                    runCatching { telegramClient.requestQrCodeAuthentication() }
                                }
                            }
                        )
                    }

                    is TdApi.AuthorizationStateWaitCode -> CodeStep(
                        codeInfo = state.codeInfo,
                        isBusy = isBusy,
                        errorMessage = errorMessage,
                        onSubmit = { code -> runAuthStep { telegramClient.checkCode(code) } },
                        onBack = { preferPhone = true }
                    )

                    is TdApi.AuthorizationStateWaitPassword -> PasswordStep(
                        hint = state.passwordHint,
                        isBusy = isBusy,
                        errorMessage = errorMessage,
                        onSubmit = { password -> runAuthStep { telegramClient.checkPassword(password) } }
                    )

                    is TdApi.AuthorizationStateClosed -> Text(
                        "The connection to Telegram closed unexpectedly. Restart the app to try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )

                    else -> Text("Connecting to Telegram…", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun QrCodeStep(
    link: String?,
    onSwitchToPhone: () -> Unit,
    onRefresh: () -> Unit
) {
    Text("Scan with your phone to sign in", style = MaterialTheme.typography.bodyMedium, color = Color.White)
    Spacer(Modifier.height(14.dp))

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
        modifier = Modifier
            .size(230.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(12.dp),
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
            androidx.compose.material3.CircularProgressIndicator(color = Color.Black)
        }
    }

    Spacer(Modifier.height(14.dp))
    Text(
        "Telegram → Settings → Devices → Link Desktop Device",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF90CAF9)
    )
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onSwitchToPhone,
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFF1E2638),
                focusedContainerColor = Color(0xFF29B6F6)
            )
        ) { Text("Use Phone Number", color = Color.White) }
        Button(
            onClick = onRefresh,
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFF1E2638),
                focusedContainerColor = Color(0xFF29B6F6)
            )
        ) { Text("Refresh QR", color = Color.White) }
    }
}

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
    onSubmit: (String) -> Unit
) {
    var phone by remember { mutableStateOf("") }

    Text(
        "Enter your phone number with country code (e.g. +1 555 123 4567)",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        enabled = !isBusy,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (phone.isNotBlank()) onSubmit(phone) }),
        placeholder = { Text("+1 555 123 4567", color = Color.Gray) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        ),
        modifier = Modifier.fillMaxWidth()
    )
    ErrorText(errorMessage)
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { onSubmit(phone) },
        enabled = !isBusy && phone.isNotBlank()
    ) {
        Text(if (isBusy) "Sending Code…" else "Send Login Code")
    }
}

@Composable
private fun CodeStep(
    codeInfo: TdApi.AuthenticationCodeInfo?,
    isBusy: Boolean,
    errorMessage: String?,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit
) {
    var code by remember { mutableStateOf("") }

    Text(codeDeliveryDescription(codeInfo), style = MaterialTheme.typography.bodyMedium, color = Color.White)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        enabled = !isBusy,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank()) onSubmit(code) }),
        placeholder = { Text("12345", color = Color.Gray) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        ),
        modifier = Modifier.fillMaxWidth()
    )
    ErrorText(errorMessage)
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { onSubmit(code) },
            enabled = !isBusy && code.isNotBlank()
        ) {
            Text(if (isBusy) "Verifying…" else "Verify Code")
        }
        Button(onClick = onBack) {
            Text("Back")
        }
    }
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
        "Two-Step Verification (2FA) is on. Enter your password to continue.",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White
    )
    if (!hint.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Text("Hint: $hint", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
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
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
        ),
        modifier = Modifier.fillMaxWidth()
    )
    ErrorText(errorMessage)
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { onSubmit(password) },
        enabled = !isBusy && password.isNotBlank()
    ) {
        Text(if (isBusy) "Checking Password…" else "Log in")
    }
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) {
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private fun codeDeliveryDescription(codeInfo: TdApi.AuthenticationCodeInfo?): String {
    val base = "Enter the code"
    return when (codeInfo?.type) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> "$base sent to your Telegram app on another device."
        is TdApi.AuthenticationCodeTypeSms -> "$base sent to you by SMS."
        is TdApi.AuthenticationCodeTypeCall -> "$base you will receive in a phone call."
        is TdApi.AuthenticationCodeTypeFlashCall -> "$base that appears as the incoming caller ID."
        is TdApi.AuthenticationCodeTypeMissedCall -> "$base: the last digits of the calling number."
        else -> "$base you received."
    }
}
