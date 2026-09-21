package com.kirtan.companion.ui.account

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirtan.companion.AppContainer
import com.kirtan.companion.data.PaletteToken
import com.kirtan.companion.storage.storageErrorMessage
import com.kirtan.companion.ui.components.KcSheet
import com.kirtan.companion.ui.components.PrimaryButton
import com.kirtan.companion.ui.components.SecondaryButton
import com.kirtan.companion.ui.components.SectionLabel
import com.kirtan.companion.ui.theme.KirtanTheme
import com.kirtan.companion.ui.theme.color
import kotlinx.coroutines.launch

/**
 * The account section of Settings: sign in, sign out, and nothing else.
 *
 * The auth machinery already existed and was tested — PKCE OAuth, email sign-in,
 * session restore and refresh — but had no way for a person to reach it. This is
 * that reach: the last mile between a tested client and someone actually logged
 * in.
 *
 * Google sign-in runs in a CUSTOM TAB rather than a WebView we own. That is a
 * security decision as much as a UX one: the Google credential entry happens in
 * the browser the user already trusts, with their existing session and their own
 * password manager, and this app never sees the password. A WebView would put our
 * code between the user and their credential, which is exactly the position an
 * app should avoid.
 *
 * The callback comes back as `kirtan://auth/callback`, handled once in
 * MainActivity; the session then arrives here through [com.kirtan.companion.storage.SupabaseClient.session],
 * so this composable needs no knowledge of the exchange.
 */
@Composable
internal fun AccountSection(container: AppContainer) {
    val dimens = KirtanTheme.dimens
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
        SectionLabel("Account")

        val supabase = container.supabase
        if (supabase == null) {
            Text(
                text = "Cloud sync isn't configured in this build, so there is nothing " +
                    "to sign in to. Everything you make is stored on this device.",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 13.4.sp,
                lineHeight = 19.sp,
            )
            return
        }

        val session by supabase.session.collectAsState()
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var emailFormOpen by remember { mutableStateOf(false) }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }

        /**
         * Run an auth call, turning any failure into a sentence the user can read.
         * [storageErrorMessage] is the sanctioned mapping; auth calls throw
         * StorageError carrying exactly such a sentence.
         */
        fun run(block: suspend () -> Unit) {
            if (busy) return
            busy = true
            error = null
            scope.launch {
                try {
                    block()
                } catch (e: Exception) {
                    error = storageErrorMessage(e)
                } finally {
                    busy = false
                }
            }
        }

        val current = session
        if (current != null) {
            Text(
                text = current.email ?: current.displayName ?: "Signed in",
                color = PaletteToken.SYAHI.color,
                fontSize = 15.2.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Your beats and lists sync to this account.",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
            )
            SecondaryButton(
                label = "Sign out",
                enabled = !busy,
                minHeight = 46.dp,
                onClick = { run { supabase.signOut() } },
            )
        } else {
            PrimaryButton(
                label = if (busy) "Signing in…" else "Sign in with Google",
                enabled = !busy,
                minHeight = 50.dp,
                onClick = {
                    run {
                        val url = supabase.beginOAuth("google")
                        // Custom Tabs keeps the user in our task and shares the
                        // browser's cookies; a plain ACTION_VIEW would work but
                        // loses both.
                        CustomTabsIntent.Builder().build()
                            .launchUrl(context, Uri.parse(url))
                    }
                },
            )

            SecondaryButton(
                label = if (emailFormOpen) "Hide email sign-in" else "Use email instead",
                minHeight = 44.dp,
                onClick = { emailFormOpen = !emailFormOpen },
            )

            if (emailFormOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.space2)) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = { Text("Email", fontSize = 13.4.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = { Text("Password", fontSize = 13.4.sp) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
                        SecondaryButton(
                            label = "Sign in",
                            enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            minHeight = 44.dp,
                            onClick = {
                                run { supabase.signInWithPassword(email.trim(), password) }
                            },
                        )
                        SecondaryButton(
                            label = "Create account",
                            enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            minHeight = 44.dp,
                            onClick = {
                                run {
                                    // signUp returns null when the server wants
                                    // email confirmation first: the session
                                    // arrives later, via the confirmation link,
                                    // so there is nothing to show yet.
                                    supabase.signUp(email.trim(), password, null)
                                }
                            },
                        )
                    }
                }
            }
        }

        error?.let { message ->
            Text(
                text = message,
                color = PaletteToken.DANGER.color,
                fontSize = 13.4.sp,
                lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = PaletteToken.CLAY.color,
    unfocusedBorderColor = KirtanTheme.colors.rule,
    focusedContainerColor = PaletteToken.HEAD.color,
    unfocusedContainerColor = PaletteToken.HEAD.color,
    focusedTextColor = PaletteToken.SYAHI.color,
    unfocusedTextColor = PaletteToken.SYAHI.color,
    cursorColor = PaletteToken.CLAY.color,
)

/**
 * The first-sign-in migration prompt.
 *
 * A fresh sign-in captures the device library BEFORE the repository switches to
 * the cloud (see [AppContainer.pendingMigration]); without that capture the local
 * beats would become invisible the moment the provider swapped, and the user
 * would have no way to move them.
 *
 * Declining is a real choice, not a cancel: the beats stay on the device and the
 * account starts empty. The offer does not return on later launches, because it
 * is only made on a signed-out → signed-in transition.
 */
@Composable
internal fun MigrationPromptSheet(container: AppContainer) {
    val pending by container.pendingMigration.collectAsState()
    val local = pending ?: return
    val dimens = KirtanTheme.dimens
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    KcSheet(
        title = "Move your beats to your account?",
        onDismiss = { container.pendingMigration.value = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.space3)) {
            Text(
                text = "This device holds ${local.beats.size} " +
                    (if (local.beats.size == 1) "beat" else "beats") +
                    (if (local.categories.isEmpty()) "" else
                        " and ${local.categories.size} " +
                            (if (local.categories.size == 1) "list" else "lists")) +
                    " that are not in your account yet.",
                color = PaletteToken.SYAHI.color,
                fontSize = 15.2.sp,
                lineHeight = 21.sp,
            )
            Text(
                text = "Moving copies them up; the copies on this device stay until " +
                    "you delete them. Leaving them here means this account starts empty.",
                color = PaletteToken.SYAHI_SOFT.color,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
                SecondaryButton(
                    label = "Leave on device",
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    minHeight = 46.dp,
                    onClick = { container.pendingMigration.value = null },
                )
                PrimaryButton(
                    label = if (busy) "Moving…" else "Move them",
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    minHeight = 46.dp,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                container.library.importLibrary(local)
                            } finally {
                                container.pendingMigration.value = null
                            }
                        }
                    },
                )
            }
        }
    }
}
