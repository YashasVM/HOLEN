package com.yashasvm.holen.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.yashasvm.holen.R

val HolenBackground = Color(0xFFF5EFE0)
val HolenInk = Color(0xFF1A1714)
val HolenSurface = Color(0xFFFFFBF0)
val HolenSurfaceTwo = Color(0xFFECE5D3)
val HolenRed = Color(0xFFD42B20)
val HolenBlue = Color(0xFF1A56A0)
val HolenYellow = Color(0xFFF0BC1E)
val HolenGreen = Color(0xFF268A5B)
val HolenMuted = Color(0xFF625B4D)

private val DmSans = FontFamily(
    Font(R.font.dm_sans, FontWeight.Normal),
    Font(R.font.dm_sans, FontWeight.Medium),
    Font(R.font.dm_sans, FontWeight.SemiBold),
    Font(R.font.dm_sans, FontWeight.Bold),
)

val Syne = FontFamily(
    Font(R.font.syne, FontWeight.Normal),
    Font(R.font.syne, FontWeight.SemiBold),
    Font(R.font.syne, FontWeight.Bold),
    Font(R.font.syne, FontWeight.ExtraBold),
)

private val HolenColors = lightColorScheme(
    primary = HolenBlue,
    onPrimary = Color.White,
    secondary = HolenRed,
    onSecondary = Color.White,
    tertiary = HolenGreen,
    onTertiary = Color.White,
    background = HolenBackground,
    onBackground = HolenInk,
    surface = HolenSurface,
    onSurface = HolenInk,
    surfaceVariant = HolenSurfaceTwo,
    onSurfaceVariant = HolenInk,
    error = HolenRed,
    onError = Color.White,
    outline = HolenInk,
)

private val HolenTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Syne,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Syne,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 21.sp,
        lineHeight = 25.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Syne,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Syne,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 19.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = DmSans,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = DmSans,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = DmSans,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

private val SquareShape = RoundedCornerShape(0.dp)

@Composable
fun HolenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HolenColors,
        typography = HolenTypography,
        shapes = Shapes(
            extraSmall = SquareShape,
            small = SquareShape,
            medium = SquareShape,
            large = SquareShape,
            extraLarge = SquareShape,
        ),
        content = content,
    )
}
