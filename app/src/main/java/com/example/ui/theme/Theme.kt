package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ExclusiveColorScheme = darkColorScheme(
    primary = PaletteCoral,              // #d5896f (Terracotta Coral for interactive elements)
    onPrimary = PaletteMidnight,         // #031d44
    primaryContainer = PaletteOceanic,   // #04395e
    onPrimaryContainer = PaletteGold,    // #dab785
    secondary = PaletteSage,             // #70a288 (Sage Green for secondary icons & indicators)
    onSecondary = PaletteMidnight,       // #031d44
    secondaryContainer = PaletteOceanic, // #04395e
    onSecondaryContainer = PaletteGold,  // #dab785
    tertiary = PaletteGold,              // #dab785 (Warm Champagne Gold)
    onTertiary = PaletteMidnight,        // #031d44
    background = PaletteMidnight,        // #031d44 (Deep Midnight Navy Canvas)
    onBackground = PaletteGold,          // #dab785
    surface = PaletteOceanic,            // #04395e (Oceanic Slate Surfaces)
    onSurface = PaletteGold,             // #dab785
    surfaceVariant = PaletteMidnight,    // #031d44
    onSurfaceVariant = PaletteGold,      // #dab785
    outline = PaletteSage,               // #70a288 (Crisp Sage Borders)
    outlineVariant = PaletteOceanic,     // #04395e
    error = PaletteCoral,                // #d5896f
    onError = PaletteMidnight            // #031d44
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = ExclusiveColorScheme,
        typography = Typography,
        content = content
    )
}
