package com.codex.quota.android.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared visual language for the Android dashboard and future companion surfaces. */
object CodexTokens {
  object Color {
    val BackgroundLight = androidx.compose.ui.graphics.Color(0xFFEAF0F3)
    val BackgroundDark = androidx.compose.ui.graphics.Color(0xFF10171B)
    val BackgroundMiddleDark = androidx.compose.ui.graphics.Color(0xFF18262C)
    val BackgroundOfflineDark = androidx.compose.ui.graphics.Color(0xFF171C1F)
    val SurfaceLight = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
    val SurfaceDark = androidx.compose.ui.graphics.Color(0xFF202E34)
    val SurfaceElevatedDark = androidx.compose.ui.graphics.Color(0xFF26363D)
    val SurfaceStrongDark = androidx.compose.ui.graphics.Color(0xFF2B3B42)
    val InkLight = androidx.compose.ui.graphics.Color(0xFF20262A)
    val InkDark = androidx.compose.ui.graphics.Color(0xFFF2F6F7)
    val MutedLight = androidx.compose.ui.graphics.Color(0xFF68747D)
    val MutedDark = androidx.compose.ui.graphics.Color(0xFFB3C0C5)
    val Primary = androidx.compose.ui.graphics.Color(0xFF176FAF)
    val PrimaryDark = androidx.compose.ui.graphics.Color(0xFF91C7F0)
    val Running = androidx.compose.ui.graphics.Color(0xFF1876C1)
    val RunningDark = androidx.compose.ui.graphics.Color(0xFF8FC9F5)
    val Authorization = androidx.compose.ui.graphics.Color(0xFFA96E00)
    val AuthorizationDark = androidx.compose.ui.graphics.Color(0xFFFFC968)
    val Waiting = androidx.compose.ui.graphics.Color(0xFF198052)
    val WaitingDark = androidx.compose.ui.graphics.Color(0xFF72D6A6)
    val Cached = androidx.compose.ui.graphics.Color(0xFF687680)
    val CachedDark = androidx.compose.ui.graphics.Color(0xFFB3C0C5)
    val Error = androidx.compose.ui.graphics.Color(0xFFC5423E)
    val ErrorDark = androidx.compose.ui.graphics.Color(0xFFFFB4AB)
    val DividerLight = androidx.compose.ui.graphics.Color(0x26808D91)
    val DividerDark = androidx.compose.ui.graphics.Color(0x38DBE5E4)
    val GlassBorderLight = androidx.compose.ui.graphics.Color(0xBFFFFFFF)
    val GlassBorderDark = androidx.compose.ui.graphics.Color(0x33FFFFFF)
    val GlassLight = androidx.compose.ui.graphics.Color(0x8FFFFFFF)
    val GlassStrongLight = androidx.compose.ui.graphics.Color(0xBDFFFFFF)
  }

  object Radius {
    val Card: Dp = 24.dp
    val Hero: Dp = 32.dp
    val Chip: Dp = 50.dp
    val Button: Dp = 18.dp
    val Navigation: Dp = 28.dp
  }

  object Space {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 22.dp
    val Page = 18.dp
  }

  object Type {
    val Icon = 17.dp
    val SmallIcon = 14.dp
    val PageTitle = 24.sp
    val SectionTitle = 14.sp
    val Body = 13.sp
    val Supporting = 11.sp
    val Caption = 10.sp
    const val MotionMs = 350
  }
}

internal val CodexLightColors =
  lightColorScheme(
    primary = CodexTokens.Color.Primary,
    onPrimary = Color.White,
    secondary = CodexTokens.Color.Authorization,
    tertiary = CodexTokens.Color.Waiting,
    error = CodexTokens.Color.Error,
    background = CodexTokens.Color.BackgroundLight,
    surface = CodexTokens.Color.SurfaceLight,
    onSurface = CodexTokens.Color.InkLight,
    onSurfaceVariant = CodexTokens.Color.MutedLight,
    surfaceVariant = Color(0xFFE4EAE9),
  )

internal val CodexDarkColors =
  darkColorScheme(
    primary = CodexTokens.Color.PrimaryDark,
    onPrimary = Color(0xFF07324F),
    secondary = CodexTokens.Color.AuthorizationDark,
    onSecondary = Color(0xFF3D2700),
    tertiary = CodexTokens.Color.WaitingDark,
    onTertiary = Color(0xFF003824),
    error = CodexTokens.Color.ErrorDark,
    onError = Color(0xFF690005),
    background = CodexTokens.Color.BackgroundDark,
    onBackground = CodexTokens.Color.InkDark,
    surface = CodexTokens.Color.SurfaceDark,
    onSurface = CodexTokens.Color.InkDark,
    onSurfaceVariant = CodexTokens.Color.MutedDark,
    surfaceVariant = CodexTokens.Color.SurfaceElevatedDark,
    outline = Color(0xFF829197),
    outlineVariant = Color(0xFF46565D),
  )
