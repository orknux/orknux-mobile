package io.mszymanski.orknux.mobile.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.mszymanski.orknux.mobile.core.AppSession
import io.mszymanski.orknux.mobile.core.AuthMethod
import io.mszymanski.orknux.mobile.core.ServerAddress
import io.mszymanski.orknux.mobile.design.Banner
import io.mszymanski.orknux.mobile.design.FieldLabel
import io.mszymanski.orknux.mobile.design.Ork
import io.mszymanski.orknux.mobile.design.OrkField
import io.mszymanski.orknux.mobile.design.OrknuxLockup
import io.mszymanski.orknux.mobile.design.PrimaryButton
import io.mszymanski.orknux.mobile.design.SecondaryButton
import io.mszymanski.orknux.mobile.design.Sky
import kotlinx.coroutines.launch
import java.io.IOException

/** The web sign-in page's card over the sky, with the server's address asked first. */
@Composable
fun SignInScreen(session: AppSession, notice: String?) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(session.lastServer.orEmpty()) }
    var base by remember { mutableStateOf<String?>(null) }
    var method by remember { mutableStateOf<AuthMethod?>(null) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var useToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun attempt(act: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                act()
            } catch (failure: IOException) {
                error = failure.message ?: "That did not work."
            } finally {
                busy = false
            }
        }
    }

    fun connect() {
        val normal = ServerAddress.normalise(address)
        if (normal == null) {
            error = "That does not look like an address."
            return
        }
        attempt {
            val found = session.authMethod(normal)
            if (found.noSignIn) {
                session.signInWith(normal, null)
            } else {
                base = normal
                method = found
                useToken = !found.takesPassword
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Sky(Modifier.fillMaxSize(), drift = true)
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val shape = RoundedCornerShape(Ork.radius12)
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().background(Ork.surface, shape).border(1.dp, Ork.border, shape).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                OrknuxLockup()
                val server = base
                val how = method
                if (server == null || how == null) {
                    Heading("Connect", "Your Orknux server's address.")
                    Field("Server") {
                        OrkField(
                            address,
                            { address = it },
                            "orknux.example.com",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        )
                    }
                    Messages(notice, error)
                    PrimaryButton(if (busy) "Connecting…" else "Continue", ::connect, Modifier.fillMaxWidth(), enabled = !busy && address.isNotBlank())
                } else {
                    Heading("Sign in", server)
                    how.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ork.textSecondary) }
                    if (useToken) {
                        if (!how.takesPassword) {
                            Banner(
                                "This server signs in with ${how.displayName ?: "single sign-on"}, which needs a browser. On a phone, use an access token from Preferences.",
                                color = Ork.textBody,
                                background = Ork.accentSoft,
                                border = Ork.accent,
                            )
                        }
                        Field("Access token") {
                            OrkField(token, { token = it }, "orkx_…", visualTransformation = PasswordVisualTransformation())
                        }
                    } else {
                        Field("Username") {
                            OrkField(username, { username = it }, "Username", keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                        }
                        Field("Password") {
                            OrkField(
                                password,
                                { password = it },
                                "Password",
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                        }
                    }
                    if (ServerAddress.isExposed(server)) {
                        Banner(
                            "This address is not encrypted. What you type here crosses the network in the clear.",
                            color = Ork.amber,
                            background = Ork.amberSoft,
                            border = Ork.amber,
                        )
                    }
                    Messages(notice, error)
                    PrimaryButton(
                        if (busy) "Signing in…" else "Sign in",
                        {
                            attempt {
                                if (useToken) session.signInWith(server, token.trim())
                                else session.signInWithPassword(server, username.trim(), password)
                            }
                        },
                        Modifier.fillMaxWidth(),
                        enabled = !busy && if (useToken) token.isNotBlank() else username.isNotBlank() && password.isNotEmpty(),
                    )
                    if (how.takesPassword) {
                        Text(
                            if (useToken) "Use a password instead" else "Use an access token instead",
                            Modifier.clickable { useToken = !useToken },
                            style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                            color = Ork.textMuted,
                        )
                    }
                    SecondaryButton(
                        "Another server",
                        {
                            base = null
                            method = null
                            error = null
                        },
                        Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ork.textSecondary)
    }
}

@Composable
private fun Field(label: String, field: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel(label)
        field()
    }
}

@Composable
private fun Messages(notice: String?, error: String?) {
    notice?.takeIf { error == null }?.let { Banner(it, color = Ork.amber, background = Ork.amberSoft, border = Ork.amber) }
    error?.let { Banner(it) }
}
