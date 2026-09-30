package com.stan.libbylight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.thelightphone.lp3Keyboard.ui.DarkKeyboardColors
import com.thelightphone.lp3Keyboard.ui.LightKeyboardColors
import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardTheme
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.LocalKeyboardColors
import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardWrapper

/** Exact Light SDK embedded LP3 keyboard wrapper, adapted only to Bard's package. */
@Composable
fun LightEmbeddedLp3Keyboard(viewModel: Lp3KeyboardViewModel<*>) {
    val layout by viewModel.layoutFlow.collectAsState()
    val keyboardOptions by viewModel.keyboardOptionsFlow.collectAsState()
    val layoutOptions by viewModel.layoutOptionsFlow.collectAsState()
    val keyboardColors = if (LightThemeTokens.colors == LightThemeColors.Light) {
        LightKeyboardColors
    } else {
        DarkKeyboardColors
    }
    val systemDensity = LocalDensity.current
    val keyboardDensity = Density(
        density = maxOf(systemDensity.density, 3f),
        fontScale = systemDensity.fontScale,
    )
    CompositionLocalProvider(LocalDensity provides keyboardDensity) {
        Lp3KeyboardTheme(keyboardColors) {
            val colors = LocalKeyboardColors.current
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .padding(top = 10.dp),
            ) {
                Lp3KeyboardWrapper(
                    layout,
                    keyboardOptions,
                    layoutOptions,
                    viewModel,
                    viewModel,
                )
            }
        }
    }
}
