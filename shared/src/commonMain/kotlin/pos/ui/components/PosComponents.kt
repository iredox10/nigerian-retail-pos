package pos.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pos.domain.money
import pos.ui.theme.PosPalette
import pos.ui.theme.PosSpacing

/** A task's main action; no icon-only or context-free confirmation label. */
@Composable
fun PosButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = PosSpacing.ActionHeight),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = PosPalette.Accent,
            contentColor = Color.White,
            disabledContainerColor = PosPalette.Border,
            disabledContentColor = PosPalette.Secondary,
        ),
    ) {
        actionIcon(text)?.let {
            PosIcon(it)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.Unspecified)
    }
}

/** Secondary actions stay neutral so they do not compete with Take payment. */
@Composable
fun NeutralButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = PosSpacing.ControlHeight),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (enabled) PosPalette.ControlBorder else PosPalette.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PosPalette.Ink),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        actionIcon(text)?.let {
            PosIcon(it)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.Unspecified)
    }
}

@Composable
fun MoneyText(
    amountMinor: Long,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    Text(
        text = money(amountMinor),
        modifier = modifier,
        style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.bodyLarge,
        fontWeight = if (large) FontWeight.SemiBold else FontWeight.Medium,
        color = PosPalette.Ink,
        softWrap = true,
    )
}

@Composable
fun SectionHeader(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = PosPalette.AccentTint, shape = RoundedCornerShape(12.dp)) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                PosIcon(destinationIcon(title), tint = PosPalette.Accent)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = PosPalette.Secondary)
            }
        }
    }
}

@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 32.dp, horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(color = PosPalette.AccentTint, shape = RoundedCornerShape(16.dp)) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                PosIcon(PosIcons.Inbox, Modifier.size(28.dp), PosPalette.Accent)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = PosPalette.Secondary)
    }
}

@Composable
fun PosTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String = "",
    error: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    moneyInput: Boolean = label.contains("(₦)") || label.contains("in naira", ignoreCase = true),
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = PosSpacing.ControlHeight),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = if (hint.isBlank()) null else ({ Text(hint, color = PosPalette.Secondary) }),
            leadingIcon = if (label.contains("search", true) || label.contains("find", true)) ({ PosIcon(PosIcons.Search) }) else null,
            prefix = if (moneyInput) ({ Text("₦ ") }) else null,
            visualTransformation = if (moneyInput) MoneyInputTransformation else androidx.compose.ui.text.input.VisualTransformation.None,
            isError = error != null,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PosPalette.Focus,
                unfocusedBorderColor = PosPalette.ControlBorder,
                focusedContainerColor = PosPalette.Surface,
                unfocusedContainerColor = PosPalette.Surface,
            ),
        )
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = PosPalette.Danger)
        }
    }
}

enum class NoticeTone { Neutral, Success, Warning, Danger, Info }

private fun noticeColors(tone: NoticeTone): Pair<Color, Color> = when (tone) {
    NoticeTone.Neutral -> PosPalette.Ink to PosPalette.SurfaceSubtle
    NoticeTone.Success -> PosPalette.Success to PosPalette.SuccessBackground
    NoticeTone.Warning -> PosPalette.Warning to PosPalette.WarningBackground
    NoticeTone.Danger -> PosPalette.Danger to PosPalette.DangerBackground
    NoticeTone.Info -> PosPalette.Info to PosPalette.InfoBackground
}

@Composable
fun StatusPill(text: String, tone: NoticeTone = NoticeTone.Neutral, modifier: Modifier = Modifier) {
    val (foreground, background) = noticeColors(tone)
    Surface(modifier = modifier, color = background, shape = RoundedCornerShape(20.dp)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
        )
    }
}

/** Persistent notices carry the affected task's status rather than disappearing in a toast. */
@Composable
fun InlineNotice(
    title: String,
    detail: String = "",
    tone: NoticeTone = NoticeTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val (foreground, background) = noticeColors(tone)
    Surface(modifier = modifier, color = background, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PosIcon(
                when (tone) {
                    NoticeTone.Success -> PosIcons.CircleCheck
                    NoticeTone.Warning, NoticeTone.Danger -> PosIcons.TriangleAlert
                    else -> PosIcons.Info
                },
                modifier = Modifier.padding(top = 2.dp),
                tint = foreground,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = foreground)
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = foreground)
                }
            }
        }
    }
}

@Composable
fun LabelValueRow(label: String, value: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp), color = PosPalette.Secondary)
        Text(value, fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal)
    }
}
