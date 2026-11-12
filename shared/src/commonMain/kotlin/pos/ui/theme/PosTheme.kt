package pos.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import pos.shared.generated.resources.Res
import pos.shared.generated.resources.ibm_plex_sans_regular
import pos.shared.generated.resources.ibm_plex_sans_medium
import pos.shared.generated.resources.ibm_plex_sans_semibold

/** Semantic roles mirror design/tokens.json. Status is always accompanied by words. */
object PosPalette {
    val Canvas = Color(0xFFF4F6F8)
    val Surface = Color.White
    val SurfaceSubtle = Color(0xFFF8FAFC)
    val Ink = Color(0xFF182230)
    val Secondary = Color(0xFF536174)
    val Muted = Color(0xFF64748B)
    val Border = Color(0xFFDCE2E8)
    val ControlBorder = Color(0xFF7A8796)
    val Accent = Color(0xFF087F73)
    val AccentHover = Color(0xFF06695F)
    val AccentPressed = Color(0xFF05564F)
    val AccentTint = Color(0xFFF1FAF8)
    val Focus = Color(0xFF2563EB)
    val Success = Color(0xFF067647)
    val SuccessBackground = Color(0xFFECFDF3)
    val Warning = Color(0xFF92400E)
    val WarningBackground = Color(0xFFFFFBEB)
    val Danger = Color(0xFFB42318)
    val DangerBackground = Color(0xFFFEF3F2)
    val Info = Color(0xFF175CD3)
    val InfoBackground = Color(0xFFEFF8FF)
}

object PosSpacing {
    val Tiny = 4.dp
    val Small = 8.dp
    val Related = 12.dp
    val Normal = 16.dp
    val Section = 24.dp
    val Large = 32.dp
    val Generous = 48.dp
    val ControlHeight = 44.dp
    val ActionHeight = 56.dp
    val NavigationWidth = 208.dp
    val BasketWidth = 380.dp
}

private fun text(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    fontFeatureSettings = "tnum",
)

private val PosTypography = Typography(
    displayLarge = text(48, 56, FontWeight.SemiBold),
    displayMedium = text(40, 48, FontWeight.SemiBold),
    displaySmall = text(32, 40, FontWeight.SemiBold),
    headlineLarge = text(24, 32, FontWeight.SemiBold),
    headlineMedium = text(24, 32, FontWeight.SemiBold),
    headlineSmall = text(24, 32, FontWeight.SemiBold),
    titleLarge = text(24, 32, FontWeight.SemiBold),
    titleMedium = text(18, 24, FontWeight.SemiBold),
    titleSmall = text(16, 24, FontWeight.Medium),
    bodyLarge = text(16, 24),
    bodyMedium = text(16, 24),
    bodySmall = text(14, 20),
    labelLarge = text(16, 24, FontWeight.SemiBold),
    labelMedium = text(14, 20, FontWeight.Medium),
    labelSmall = text(14, 20, FontWeight.Medium),
)

private val PosColorScheme = lightColorScheme(
    primary = PosPalette.Accent,
    onPrimary = Color.White,
    primaryContainer = PosPalette.AccentTint,
    onPrimaryContainer = PosPalette.Accent,
    secondary = PosPalette.Secondary,
    onSecondary = Color.White,
    secondaryContainer = PosPalette.SurfaceSubtle,
    onSecondaryContainer = PosPalette.Ink,
    tertiary = PosPalette.Info,
    background = PosPalette.Canvas,
    onBackground = PosPalette.Ink,
    surface = PosPalette.Surface,
    onSurface = PosPalette.Ink,
    surfaceVariant = PosPalette.SurfaceSubtle,
    // Material overlays must use our existing neutral surfaces, not its default purple containers.
    surfaceTint = PosPalette.Accent,
    surfaceContainerLowest = PosPalette.Surface,
    surfaceContainerLow = PosPalette.SurfaceSubtle,
    surfaceContainer = PosPalette.Surface,
    surfaceContainerHigh = PosPalette.Surface,
    surfaceContainerHighest = PosPalette.SurfaceSubtle,
    onSurfaceVariant = PosPalette.Secondary,
    outline = PosPalette.ControlBorder,
    outlineVariant = PosPalette.Border,
    error = PosPalette.Danger,
    onError = Color.White,
    errorContainer = PosPalette.DangerBackground,
    onErrorContainer = PosPalette.Danger,
)

@Composable
fun PosTheme(content: @Composable () -> Unit) {
    val font = FontFamily(
        Font(Res.font.ibm_plex_sans_regular, FontWeight.Normal),
        Font(Res.font.ibm_plex_sans_medium, FontWeight.Medium),
        Font(Res.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    )
    val typography = remember(font) {
        PosTypography.copy(
            displayLarge = PosTypography.displayLarge.copy(fontFamily = font),
            displayMedium = PosTypography.displayMedium.copy(fontFamily = font),
            displaySmall = PosTypography.displaySmall.copy(fontFamily = font),
            headlineLarge = PosTypography.headlineLarge.copy(fontFamily = font),
            headlineMedium = PosTypography.headlineMedium.copy(fontFamily = font),
            headlineSmall = PosTypography.headlineSmall.copy(fontFamily = font),
            titleLarge = PosTypography.titleLarge.copy(fontFamily = font),
            titleMedium = PosTypography.titleMedium.copy(fontFamily = font),
            titleSmall = PosTypography.titleSmall.copy(fontFamily = font),
            bodyLarge = PosTypography.bodyLarge.copy(fontFamily = font),
            bodyMedium = PosTypography.bodyMedium.copy(fontFamily = font),
            bodySmall = PosTypography.bodySmall.copy(fontFamily = font),
            labelLarge = PosTypography.labelLarge.copy(fontFamily = font),
            labelMedium = PosTypography.labelMedium.copy(fontFamily = font),
            labelSmall = PosTypography.labelSmall.copy(fontFamily = font),
        )
    }
    MaterialTheme(
        colorScheme = PosColorScheme,
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(12.dp),
            extraLarge = RoundedCornerShape(12.dp),
        ),
        content = content,
    )
}
