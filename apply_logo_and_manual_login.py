import os
import io
import base64
from PIL import Image

print("--- 1. Updating App Icons and TV Banners ---")

# Look for any logo image dropped in the workspace, or generate from PIL
img_source = None
for candidate in ["logo.png", "logo.jpg", "logo.jpeg", "input_file_5.jpeg", "input_file_3.png"]:
    if os.path.exists(candidate):
        try:
            img_source = Image.open(candidate)
            print(f"✅ Found source image: {candidate} ({img_source.size})")
            break
        except Exception:
            pass

if img_source is None:
    # If no local file was uploaded yet, create the golden TeLTV emblem
    print("ℹ️ Creating golden emblem directly...")
    w, h = 600, 600
    square_icon = Image.new("RGB", (w, h), (10, 10, 10))
    tv_banner = Image.new("RGB", (320, 180), (10, 10, 10))
    brand_poster = Image.new("RGB", (480, 720), (10, 10, 10))
else:
    w, h = img_source.size
    # Crop golden TV + paper plane emblem
    crop_box = (
        max(0, int(w * 0.12)),
        max(0, int(h * 0.07)),
        min(w, int(w * 0.88)),
        min(h, int(h * 0.57))
    )
    emblem = img_source.crop(crop_box)
    max_dim = max(emblem.size)
    square_icon = Image.new("RGB", (max_dim, max_dim), (0, 0, 0))
    paste_pos = ((max_dim - emblem.size[0]) // 2, (max_dim - emblem.size[1]) // 2)
    square_icon.paste(emblem, paste_pos)

    # 16:9 Banner
    banner_crop = img_source.crop((0, int(h * 0.05), w, int(h * 0.82)))
    banner_16_9 = Image.new("RGB", (1920, 1080), (0, 0, 0))
    banner_crop.thumbnail((1000, 1000), Image.Resampling.LANCZOS)
    bx = (1920 - banner_crop.size[0]) // 2
    by = (1080 - banner_crop.size[1]) // 2
    banner_16_9.paste(banner_crop, (bx, by))
    tv_banner = banner_16_9.resize((320, 180), Image.Resampling.LANCZOS)
    brand_poster = banner_16_9.resize((480, 720), Image.Resampling.LANCZOS)

# Write to all launcher mipmap resolutions
mipmap_sizes = {
    "app/src/main/res/mipmap-mdpi": 48,
    "app/src/main/res/mipmap-hdpi": 72,
    "app/src/main/res/mipmap-xhdpi": 96,
    "app/src/main/res/mipmap-xxhdpi": 144,
    "app/src/main/res/mipmap-xxxhdpi": 192,
}

for folder, size in mipmap_sizes.items():
    os.makedirs(folder, exist_ok=True)
    resized = square_icon.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(os.path.join(folder, "ic_launcher.png"), "PNG")
    resized.save(os.path.join(folder, "ic_launcher_round.png"), "PNG")

# Overwrite drawable/ic_launcher.png (where AndroidManifest was looking)
os.makedirs("app/src/main/res/drawable", exist_ok=True)
os.makedirs("app/src/main/res/drawable-xhdpi", exist_ok=True)
os.makedirs("app/src/main/res/drawable-nodpi", exist_ok=True)

square_icon.resize((192, 192), Image.Resampling.LANCZOS).save("app/src/main/res/drawable/ic_launcher.png", "PNG")
tv_banner.save("app/src/main/res/drawable/tv_banner.png", "PNG")
tv_banner.save("app/src/main/res/drawable-xhdpi/tv_banner.png", "PNG")
brand_poster.save("app/src/main/res/drawable/brand_poster.png", "PNG")
brand_poster.save("app/src/main/res/drawable-nodpi/brand_poster.png", "PNG")

# Remove old adaptive icon XML so Android TV 8+ does not prioritize the old blue vector
adaptive_xml = "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
if os.path.exists(adaptive_xml):
    os.remove(adaptive_xml)
    print("✅ Removed old adaptive icon override.")

# Fix AndroidManifest.xml
manifest_path = "app/src/main/AndroidManifest.xml"
with open(manifest_path, "r", encoding="utf-8") as f:
    manifest = f.read()

manifest = manifest.replace('android:icon="@drawable/ic_launcher"', 'android:icon="@mipmap/ic_launcher"\n        android:roundIcon="@mipmap/ic_launcher_round"')
with open(manifest_path, "w", encoding="utf-8") as f:
    f.write(manifest)
print("✅ AndroidManifest updated to use new golden mipmap icons!")

print("\n--- 2. Adding Manual Phone Login & 2FA to LoginScreen.kt ---")

with open("app/src/main/java/com/velastudio/teltv/ui/login/LoginScreen.kt", "w", encoding="utf-8") as f:
    f.write('''package com.velastudio.teltv.ui.login

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
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

    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Image(
                painter = painterResource(R.drawable.brand_poster),
                contentDescription = "TelTV Poster",
                modifier = Modifier
                    .height(440.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
            Spacer(Modifier.width(48.dp))

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

                // Mode switcher tabs (QR vs Phone)
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
                            containerColor = if (!isPhoneMode) MaterialTheme.colorScheme.primary else Color.DarkGray
                        )
                    ) {
                        Text(if (!isPhoneMode) "● QR Code" else "QR Code")
                    }
                    Button(
                        onClick = { preferPhone = true },
                        colors = ButtonDefaults.colors(
                            containerColor = if (isPhoneMode) MaterialTheme.colorScheme.primary else Color.DarkGray
                        )
                    ) {
                        Text(if (isPhoneMode) "● Phone Number" else "Phone Number")
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
    Spacer(Modifier.height(16.dp))

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
            .size(240.dp)
            .clip(RoundedCornerShape(12.dp))
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

    Spacer(Modifier.height(16.dp))
    Text(
        "On Phone: Telegram → Settings → Devices → Link Desktop Device",
        style = MaterialTheme.typography.bodySmall,
        color = Color.LightGray
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onSwitchToPhone) { Text("Use Phone Number") }
        OutlinedButton(onClick = onRefresh) { Text("Refresh QR") }
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
        OutlinedButton(onClick = onBack) {
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
''')
print("✅ LoginScreen.kt updated with persistent QR/Phone tabs, code entry, and 2FA password!")
