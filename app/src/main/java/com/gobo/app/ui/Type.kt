package com.gobo.app.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.gobo.app.R

// Poppins is bundled locally (res/font, OFL — see assets/poppins_license_ofl.txt) so
// the app pulls nothing from Google's downloadable-fonts provider at runtime. Its round
// "O"s echo the shape of Go stones.
private val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
)

/** Material3 type scale rendered in Poppins: headings semi-bold, labels medium, body regular. */
val GoboTypography: Typography = Typography().run {
    fun TextStyle.poppins(weight: FontWeight) = copy(fontFamily = Poppins, fontWeight = weight)
    copy(
        displayLarge = displayLarge.poppins(FontWeight.SemiBold),
        displayMedium = displayMedium.poppins(FontWeight.SemiBold),
        displaySmall = displaySmall.poppins(FontWeight.SemiBold),
        headlineLarge = headlineLarge.poppins(FontWeight.SemiBold),
        headlineMedium = headlineMedium.poppins(FontWeight.SemiBold),
        headlineSmall = headlineSmall.poppins(FontWeight.SemiBold),
        titleLarge = titleLarge.poppins(FontWeight.SemiBold),
        titleMedium = titleMedium.poppins(FontWeight.Medium),
        titleSmall = titleSmall.poppins(FontWeight.Medium),
        bodyLarge = bodyLarge.poppins(FontWeight.Normal),
        bodyMedium = bodyMedium.poppins(FontWeight.Normal),
        bodySmall = bodySmall.poppins(FontWeight.Normal),
        labelLarge = labelLarge.poppins(FontWeight.Medium),
        labelMedium = labelMedium.poppins(FontWeight.Medium),
        labelSmall = labelSmall.poppins(FontWeight.Medium),
    )
}
