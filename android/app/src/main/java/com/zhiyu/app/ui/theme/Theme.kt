package com.zhiyu.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiyu.app.R

// Pigments on cream paper: dusty rose, olive foliage, warm graphite.
private val PaperLight = lightColorScheme(
    primary = Color(0xFF925349), onPrimary = Color(0xFFFFFBF4),
    primaryContainer = Color(0xFFF3DED5), onPrimaryContainer = Color(0xFF5B332C),
    secondary = Color(0xFF5D6B4E), onSecondary = Color(0xFFFFFBF4),
    secondaryContainer = Color(0xFFE6E9D8), onSecondaryContainer = Color(0xFF35402A),
    tertiary = Color(0xFF796449), onTertiary = Color.White,
    background = Color(0xFFFCF7E8), onBackground = Color(0xFF403A32),
    surface = Color(0xFFFCF7E8), onSurface = Color(0xFF403A32),
    surfaceVariant = Color(0xFFF0EAD9), onSurfaceVariant = Color(0xFF746A5B),
    surfaceContainerLowest = Color(0xFFFFFCF4), surfaceContainerLow = Color(0xFFF9F2E2),
    surfaceContainer = Color(0xFFF3ECD9), surfaceContainerHigh = Color(0xFFEDE5D1),
    surfaceContainerHighest = Color(0xFFE6DCC6),
    outline = Color(0xFF9C8D79), outlineVariant = Color(0xFFD7CAB3),
    inverseSurface = Color(0xFF39352D), inverseOnSurface = Color(0xFFF8F0DF),
    inversePrimary = Color(0xFFEAB5A4), surfaceTint = Color(0xFF925349),
    error = Color(0xFFA13932), onError = Color.White,
    errorContainer = Color(0xFFF8DCD4), onErrorContainer = Color(0xFF64251F),
)
private val PaperDark = darkColorScheme(
    primary = Color(0xFFE7B09F), onPrimary = Color(0xFF542D26),
    primaryContainer = Color(0xFF684138), onPrimaryContainer = Color(0xFFFFE0D5),
    secondary = Color(0xFFBAC7A4), onSecondary = Color(0xFF2B3920),
    secondaryContainer = Color(0xFF424D35), onSecondaryContainer = Color(0xFFDFE9CD),
    tertiary = Color(0xFFD6BE99), onTertiary = Color(0xFF40341F),
    background = Color(0xFF201E19), onBackground = Color(0xFFF0E8D8),
    surface = Color(0xFF201E19), onSurface = Color(0xFFF0E8D8),
    surfaceVariant = Color(0xFF494338), onSurfaceVariant = Color(0xFFD0C4B0),
    surfaceContainerLowest = Color(0xFF171610), surfaceContainerLow = Color(0xFF28251E),
    surfaceContainer = Color(0xFF302C24), surfaceContainerHigh = Color(0xFF38332A),
    surfaceContainerHighest = Color(0xFF423C31),
    outline = Color(0xFFA79A83), outlineVariant = Color(0xFF554B3D),
    inverseSurface = Color(0xFFF0E8D8), inverseOnSurface = Color(0xFF39352D),
    inversePrimary = Color(0xFF925349), surfaceTint = Color(0xFFE7B09F),
    error = Color(0xFFFFB4A4), onError = Color(0xFF5F1D16),
    errorContainer = Color(0xFF7D3026), onErrorContainer = Color(0xFFFFDAD2),
)
private val PaperTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, letterSpacing = 4.sp),
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, letterSpacing = 2.sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, letterSpacing = 1.sp),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, letterSpacing = 1.sp),
        bodyLarge = base.bodyLarge.copy(lineHeight = 27.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 23.sp),
    )
}
private val PaperShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable fun ZhiYuTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val artwork = painterResource(R.drawable.watercolor_bouquet_v1)
    CompositionLocalProvider(LocalWatercolorPainter provides artwork) {
        MaterialTheme(colorScheme = if (darkTheme) PaperDark else PaperLight,
            typography = PaperTypography, shapes = PaperShapes, content = content)
    }
}
