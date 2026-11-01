package io.mszymanski.orknux.mobile.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mszymanski.orknux.mobile.R

@Composable
fun OrknuxMark(height: Dp = 20.dp) {
    Image(
        painterResource(R.drawable.ic_orknux_mark),
        contentDescription = null,
        modifier = Modifier.height(height).width(height * 1.2f),
    )
}

/** The mark on its tile and the mono wordmark, as in the web client's top bar. */
@Composable
fun OrknuxLockup() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(Ork.radius8)).background(Ork.surfaceRaised)
                .border(1.dp, Ork.border, RoundedCornerShape(Ork.radius8)),
            contentAlignment = Alignment.Center,
        ) { OrknuxMark(16.dp) }
        Text("ORKNUX", style = MonoLabel.copy(fontSize = 14.sp, letterSpacing = 1.6.sp), color = Ork.textPrimary)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false) {
    Button(
        onClick,
        modifier.defaultMinSize(minHeight = if (compact) 32.dp else Ork.control, minWidth = 1.dp),
        enabled = enabled,
        shape = RoundedCornerShape(Ork.radius6),
        contentPadding = if (compact) PaddingValues(horizontal = 12.dp, vertical = 4.dp) else ButtonDefaults.ContentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = Ork.accent,
            contentColor = Ork.onAccent,
            disabledContainerColor = Ork.accentSoft,
            disabledContentColor = Ork.textMuted,
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false, color: Color = Ork.textPrimary) {
    OutlinedButton(
        onClick,
        modifier.defaultMinSize(minHeight = if (compact) 32.dp else Ork.control, minWidth = 1.dp),
        enabled = enabled,
        shape = RoundedCornerShape(Ork.radius6),
        border = BorderStroke(1.dp, Ork.border),
        contentPadding = if (compact) PaddingValues(horizontal = 12.dp, vertical = 4.dp) else ButtonDefaults.ContentPadding,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Ork.surfaceRaised, contentColor = color),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun OrkIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Ork.textSecondary,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
) {
    IconButton(onClick, modifier, enabled = enabled, colors = IconButtonDefaults.iconButtonColors(disabledContentColor = Ork.textMuted.copy(alpha = 0.5f))) {
        Icon(icon, description, Modifier.size(iconSize), tint = if (enabled) tint else Ork.textMuted.copy(alpha = 0.5f))
    }
}

/** The web client's `inputContainer`: raised surface, one-pixel border, muted placeholder. */
@Composable
fun OrkField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
) {
    val text = MaterialTheme.typography.bodyLarge.copy(color = Ork.textPrimary)
    BasicTextField(
        value,
        onValueChange,
        modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        textStyle = text,
        cursorBrush = SolidColor(Ork.accent),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = Ork.control)
                    .background(Ork.surfaceRaised, RoundedCornerShape(Ork.radius6))
                    .border(1.dp, Ork.border, RoundedCornerShape(Ork.radius6))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (leading != null) Icon(leading, null, Modifier.size(16.dp), tint = Ork.textMuted)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = text, color = Ork.textMuted, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

@Composable
fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Ork.textSecondary)
}

/** A small tinted tag: TOOL in cyan, THINKING in red. */
@Composable
fun Tag(text: String, color: Color, background: Color) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(Ork.radius4)).background(background).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MonoLabel.copy(fontSize = 10.sp),
        color = color,
    )
}

@Composable
fun Banner(text: String, color: Color = Ork.danger, background: Color = Ork.dangerSoft, border: Color = Ork.dangerBorder, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Ork.radius6)).background(background)
            .border(1.dp, border, RoundedCornerShape(Ork.radius6)).padding(12.dp),
    ) { Text(text, style = MaterialTheme.typography.bodyMedium, color = color) }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, danger: Boolean = true, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ork.surface,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = Ork.textSecondary) },
        confirmButton = {
            TextButton(onConfirm) { Text(confirm, color = if (danger) Ork.danger else Ork.accent) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Ork.textSecondary) } },
    )
}

@Composable
fun TextDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ork.surface,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { OrkField(text, { text = it }, title) },
        confirmButton = { TextButton({ onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm, color = Ork.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = Ork.textSecondary) } },
    )
}

/** Two letters on a tile, as the web client shows a workspace. */
@Composable
fun InitialsTile(name: String, size: Dp = 24.dp, background: Color = Ork.accentSoft, color: Color = Ork.textPrimary) {
    val initials = name.split(' ').filter(String::isNotEmpty).take(2).joinToString("") { it.take(1).uppercase() }
        .ifEmpty { "?" }
    Box(Modifier.size(size).clip(RoundedCornerShape(Ork.radius4)).background(background), contentAlignment = Alignment.Center) {
        Text(initials, style = MonoLabel.copy(fontSize = 10.sp, letterSpacing = 0.sp), color = color)
    }
}

@Composable
fun AvatarIcon(icon: ImageVector, size: Dp = 30.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Ork.surfaceRaised).border(1.dp, Ork.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(size * 0.55f), tint = Ork.textSecondary) }
}

/** The web client's avatar: a circle with the first two letters of the name, or a figure until the name is known. */
@Composable
fun UserAvatar(name: String?, size: Dp = 32.dp) {
    val initials = name?.trim()?.take(2)?.uppercase().orEmpty()
    if (initials.isEmpty()) {
        AvatarIcon(OrkIcons.User, size)
        return
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(Ork.surfaceRaised).border(1.dp, Ork.border, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(initials, style = MonoLabel.copy(fontSize = 11.sp, letterSpacing = 0.sp), color = Ork.textPrimary) }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = MonoLabel, color = Ork.textMuted)
}
