package com.threadprotection.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadprotection.app.ui.components.BackCircleButton
import com.threadprotection.app.ui.components.PrimaryPillButton
import com.threadprotection.app.ui.theme.LocalTpPalette
import com.threadprotection.app.ui.theme.TpType

/** Real, on-device "Create an account" form — PBKDF2-hashed local credential, no backend needed. */
@Composable
fun CreateAccountScreen(
    error: String?,
    onBack: () -> Unit,
    onCreate: (name: String, email: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTpPalette.current
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BackCircleButton(onClick = onBack)
            Text("Create an account", style = TpType.screenTitle, color = palette.fg)
        }
        Text(
            "Kept only on this phone — your name, email and a securely hashed password, nothing else.",
            style = TpType.body.copy(fontSize = 15.5.sp),
            color = palette.muted,
        )

        LabeledField("Name", name) { name = it }
        LabeledField("Email", email, keyboardType = KeyboardType.Email) { email = it }
        LabeledField("Password (min 8 characters)", password, isPassword = true, keyboardType = KeyboardType.Password) { password = it }

        if (!error.isNullOrBlank()) {
            Text(error, style = TpType.caption.copy(fontSize = 14.5.sp), color = palette.danger)
        }

        PrimaryPillButton(text = "Create account", onClick = { onCreate(name, email, password) })
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    val palette = LocalTpPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = TpType.cardTitle.copy(fontSize = 15.sp), color = palette.fg2)
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).border(BorderStroke(1.dp, palette.line3), RoundedCornerShape(14.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = palette.card,
                unfocusedContainerColor = palette.card,
                focusedTextColor = palette.fg,
                unfocusedTextColor = palette.fg,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                cursorColor = palette.accent,
            ),
        )
    }
}
