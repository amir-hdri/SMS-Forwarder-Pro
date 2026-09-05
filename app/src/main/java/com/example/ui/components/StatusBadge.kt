package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ForwardStatus
import com.example.ui.theme.PaletteCoral
import com.example.ui.theme.PaletteGold
import com.example.ui.theme.PaletteMidnight
import com.example.ui.theme.PaletteSage

@Composable
fun StatusBadge(
    status: ForwardStatus,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, dotColor, label) = when (status) {
        ForwardStatus.SUCCESS -> Quadruple(
            PaletteMidnight,
            PaletteSage,
            PaletteSage,
            "موفق"
        )
        ForwardStatus.FAILED -> Quadruple(
            PaletteMidnight,
            PaletteCoral,
            PaletteCoral,
            "ناموفق"
        )
        ForwardStatus.SKIPPED -> Quadruple(
            PaletteMidnight,
            PaletteGold,
            PaletteGold,
            "رد شده"
        )
        ForwardStatus.PENDING -> Quadruple(
            PaletteMidnight,
            PaletteGold,
            PaletteSage,
            "در انتظار"
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, textColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
