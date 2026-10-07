package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// =========================================================================
// Official BarPro Design System Palette
// Mirroring BarPro Web App (Slate 950 + Cyan/Blue/Purple + Emerald/Amber/Rose)
// =========================================================================

// Backgrounds & Surfaces
val BarProBg = Color(0xFF030712)                 // Slate 950 - Root deep background
val BarProSurface = Color(0xFF0F172A)            // Slate 900 - Primary cards & surfaces
val BarProSurfaceElevated = Color(0xFF1E293B)    // Slate 800 - Elevated containers, badges
val BarProSurfaceSubtle = Color(0xFF131D31)      // Slate 850 - Input fields & inner panels

// Accents & Gradients
val BarProCyan = Color(0xFF06B6D4)               // Cyan 500 - Signature brand color
val BarProCyanBright = Color(0xFF22D3EE)         // Cyan 400 - Hover / glow / focus
val BarProCyanMuted = Color(0x1F06B6D4)          // Cyan with ~12% opacity for card backgrounds
val BarProBlue = Color(0xFF3B82F6)               // Blue 500 - Gradient bridge
val BarProPurple = Color(0xFF8B5CF6)             // Purple 500 - Gradient end

// Status & Semantic Feedback
val BarProEmerald = Color(0xFF10B981)            // Emerald 500 - Success, Active, Online
val BarProEmeraldBg = Color(0x1F10B981)          // Emerald with 12% opacity
val BarProAmber = Color(0xFFF59E0B)              // Amber 500 - Pending, Warning
val BarProAmberBg = Color(0x1FF59E0B)            // Amber with 12% opacity
val BarProRose = Color(0xFFF43F5E)               // Rose 500 - Error, Failed, Disconnected
val BarProRoseBg = Color(0x1FF43F5E)             // Rose with 12% opacity

// Typography & Content
val BarProTextPrimary = Color(0xFFF8FAFC)        // Slate 50 - High contrast text
val BarProTextSecondary = Color(0xFF94A3B8)      // Slate 400 - Labels, hints, secondary text
val BarProTextMuted = Color(0xFF64748B)          // Slate 500 - Timestamps, borders, placeholders

// Borders & Dividers
val BarProBorder = Color(0x1AFFFFFF)             // White with 10% opacity
val BarProBorderCyan = Color(0x3306B6D4)         // Cyan border with 20% opacity

// =========================================================================
// Semantic Aliases for Clean Backward Compatibility
// =========================================================================
val PaletteMidnight = BarProBg
val PaletteOceanic = BarProSurface
val PaletteSage = BarProCyan
val PaletteGold = BarProTextPrimary
val PaletteCoral = BarProCyanBright

val PalettePale = BarProTextPrimary
val PaletteLight = BarProCyan
val PaletteMedium = BarProBorderCyan
val PaletteDeep = BarProSurface
val PaletteDarkest = BarProBg

val Slate950 = BarProBg
val Slate900 = BarProSurface
val Slate850 = BarProSurfaceSubtle
val Slate800 = BarProSurfaceElevated
val Slate700 = Color(0xFF334155)
val Slate600 = Color(0xFF475569)
val Slate500 = BarProTextMuted
val Slate400 = BarProTextSecondary
val Slate300 = Color(0xFFCBD5E1)
val Slate100 = BarProTextPrimary

val Cyan500 = BarProCyan
val Cyan400 = BarProCyanBright
val Sky500 = BarProCyan
val Sky400 = BarProCyanBright
val Sky600 = Color(0xFF0284C7)
val Indigo600 = BarProBlue
val Indigo500 = BarProBlue
val Indigo400 = BarProCyanBright

val Emerald500 = BarProEmerald
val Emerald400 = Color(0xFF34D399)
val Rose500 = BarProRose
val Rose400 = Color(0xFFFB7185)
val Amber500 = BarProAmber
val Amber400 = Color(0xFFFBBF24)
