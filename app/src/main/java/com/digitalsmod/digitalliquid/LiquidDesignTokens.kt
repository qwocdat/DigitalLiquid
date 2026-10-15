package com.digitalsmod.digitalliquid

import android.graphics.Color

/** Central palette and motion tokens for the native iOS-inspired Liquid Glass interface. */
object LiquidDesignTokens {
    val background = Color.rgb(5, 6, 9)
    val surface = 0xE81A1917.toInt()
    val glassStroke = 0x45E7C28B
    val accent = Color.rgb(218, 174, 113)
    val systemBlue = Color.rgb(10, 132, 255)
    val systemGreen = Color.rgb(52, 199, 89)
    val secondaryText = 0xFF9FA3B3.toInt()
    val primaryText = Color.WHITE
    const val motionMs = 260L
    const val springOvershoot = 0.72f
}
