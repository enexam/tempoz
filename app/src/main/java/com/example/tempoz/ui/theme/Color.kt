package com.example.tempoz.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Warm Analog palette — parchment cream, walnut brown, and a rust/terracotta accent, evoking a
 * classic wind-up metronome and vinyl-era hi-fi. Every Material 3 role is specified explicitly
 * (dynamic color is disabled in [TempozTheme]) so both schemes stay cohesive.
 */

// ---- Light (hero) : cream paper, walnut ink, rust accent ----
val WarmLightColors = lightColorScheme(
    primary = Color(0xFFB4521F),
    onPrimary = Color(0xFFFCF5E7),
    primaryContainer = Color(0xFFFFDBC8),
    onPrimaryContainer = Color(0xFF3C1500),
    secondary = Color(0xFF6B4E34),
    onSecondary = Color(0xFFFCF5E7),
    secondaryContainer = Color(0xFFF4DEC2),
    onSecondaryContainer = Color(0xFF261605),
    tertiary = Color(0xFF8A6A1E),
    onTertiary = Color(0xFFFCF5E7),
    tertiaryContainer = Color(0xFFF6E0A6),
    onTertiaryContainer = Color(0xFF2C2000),
    background = Color(0xFFEFE6D2),
    onBackground = Color(0xFF2A1E12),
    surface = Color(0xFFF3EAD6),
    onSurface = Color(0xFF2A1E12),
    surfaceVariant = Color(0xFFE6D9BE),
    onSurfaceVariant = Color(0xFF6E5E45),
    surfaceDim = Color(0xFFDBCFB4),
    surfaceBright = Color(0xFFFBF5E6),
    surfaceContainerLowest = Color(0xFFFBF5E6),
    surfaceContainerLow = Color(0xFFF6EEDC),
    surfaceContainer = Color(0xFFF0E7D1),
    surfaceContainerHigh = Color(0xFFEAE0C8),
    surfaceContainerHighest = Color(0xFFE4D9BE),
    outline = Color(0xFF897A5E),
    outlineVariant = Color(0xFFD2C3A4),
    error = Color(0xFF9F4034),
    onError = Color(0xFFFCF5E7),
    errorContainer = Color(0xFFFBD9D0),
    onErrorContainer = Color(0xFF3B0A05),
    inverseSurface = Color(0xFF322619),
    inverseOnSurface = Color(0xFFF3EAD6),
    inversePrimary = Color(0xFFFFB68F),
    scrim = Color(0xFF000000),
)

// ---- Dark : espresso night, same warm soul ----
val WarmDarkColors = darkColorScheme(
    primary = Color(0xFFFFB68F),
    onPrimary = Color(0xFF5A2400),
    primaryContainer = Color(0xFF8C3D12),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Color(0xFFDBC0A0),
    onSecondary = Color(0xFF3C2A16),
    secondaryContainer = Color(0xFF54402A),
    onSecondaryContainer = Color(0xFFF4DEC2),
    tertiary = Color(0xFFDCC78A),
    onTertiary = Color(0xFF3B2F08),
    tertiaryContainer = Color(0xFF5A4A20),
    onTertiaryContainer = Color(0xFFF6E0A6),
    background = Color(0xFF18120B),
    onBackground = Color(0xFFECE0CB),
    surface = Color(0xFF1E1710),
    onSurface = Color(0xFFECE0CB),
    surfaceVariant = Color(0xFF4A3D2B),
    onSurfaceVariant = Color(0xFFD0C0A3),
    surfaceDim = Color(0xFF18120B),
    surfaceBright = Color(0xFF3E3325),
    surfaceContainerLowest = Color(0xFF120D07),
    surfaceContainerLow = Color(0xFF1E1710),
    surfaceContainer = Color(0xFF221A12),
    surfaceContainerHigh = Color(0xFF2D241A),
    surfaceContainerHighest = Color(0xFF382E22),
    outline = Color(0xFF9C8B6E),
    outlineVariant = Color(0xFF4A3D2B),
    error = Color(0xFFFFB4A8),
    onError = Color(0xFF5E1609),
    errorContainer = Color(0xFF832E1F),
    onErrorContainer = Color(0xFFFBD9D0),
    inverseSurface = Color(0xFFECE0CB),
    inverseOnSurface = Color(0xFF322619),
    inversePrimary = Color(0xFFB4521F),
    scrim = Color(0xFF000000),
)
