package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// =========================================================================
// Exclusive 5-Color Palette:
// ["#031d44", "#04395e", "#70a288", "#dab785", "#d5896f"]
// =========================================================================
// 1. #031d44 - Deep Midnight Navy (Canvas background, deepest foundation)
val PaletteMidnight = Color(0xFF031D44)

// 2. #04395e - Rich Oceanic Slate (Cards, elevated containers, app bar)
val PaletteOceanic = Color(0xFF04395E)

// 3. #70a288 - Sage Mint / Marine Green (Active status, connectivity, borders, signals)
val PaletteSage = Color(0xFF70A288)

// 4. #dab785 - Warm Champagne Gold (Primary text, prominent badges, key values, high contrast)
val PaletteGold = Color(0xFFDAB785)

// 5. #d5896f - Terracotta Coral (Accent buttons, active switches, alerts, energy points)
val PaletteCoral = Color(0xFFD5896F)

// Backward-compatible semantic mappings to maintain perfect integrity:
val PalettePale = PaletteGold         // High contrast text & labels
val PaletteLight = PaletteCoral       // Vibrant action/active indicator & buttons
val PaletteMedium = PaletteSage       // Badges, borders, secondary indicators
val PaletteDeep = PaletteOceanic      // Elevated cards, dialog surfaces
val PaletteDarkest = PaletteMidnight  // Root background

val Slate950 = PaletteMidnight
val Slate900 = PaletteOceanic
val Slate850 = PaletteOceanic
val Slate800 = PaletteSage
val Slate700 = PaletteSage
val Slate600 = PaletteSage
val Slate400 = PaletteGold
val Slate300 = PaletteGold
val Slate100 = PaletteGold

val Cyan500 = PaletteSage
val Cyan400 = PaletteSage
val Sky500 = PaletteSage
val Sky400 = PaletteSage
val Sky600 = PaletteSage
val Indigo600 = PaletteOceanic
val Indigo500 = PaletteCoral
val Indigo400 = PaletteCoral

val Emerald500 = PaletteSage
val Emerald400 = PaletteSage
val Rose500 = PaletteCoral
val Rose400 = PaletteCoral
val Amber500 = PaletteGold
val Amber400 = PaletteGold
