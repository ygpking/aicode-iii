package com.aicode.core.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.aicode.R

/**
 * 展示代码/路径/日志等结构化文本统一的等宽字体。
 *
 * 不用 [FontFamily.Monospace]：系统等宽（DroidSansMono）不含下标、上标等字符，且字体链上
 * 也没有任何字体能兜住（NotoSansSymbols 同样缺），遇到 `H₂O`、`x₀` 只能显示方框。
 * 内置 JetBrains Mono NL 自带这些字形；它不含的中文等字符仍由系统 fallback 补齐。
 */
val CodeFontFamily: FontFamily = FontFamily(Font(R.font.jetbrains_mono_nl))
