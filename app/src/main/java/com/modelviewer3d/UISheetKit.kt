package com.modelviewer3d

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * AuraCAD UI Kit — single design language for every bottom sheet.
 * All screens build their layout from these helpers so spacing, colors,
 * typography and card style stay consistent and nothing overlaps.
 */
object UISheetKit {

    // ── Palette ───────────────────────────────────────────────────────────────
    const val BG_SHEET     = "#0F1520"   // sheet background
    const val CARD_BG      = "#1A2434"   // card background
    const val CARD_BORDER  = "#26334A"   // card hairline
    const val INPUT_BG     = "#141F2E"
    const val TEXT_MAIN    = "#F2F6FB"
    const val TEXT_SUB     = "#A9B8CC"
    const val TEXT_MUTED   = "#7A8BA3"
    const val DIVIDER      = "#223046"
    const val CYAN         = "#4DD8FF"
    const val AMBER        = "#FFC46B"
    const val VIOLET       = "#A78BFA"
    const val GREEN        = "#4CAF82"
    const val RED          = "#FF7A72"
    const val YELLOW       = "#FFD54F"
    const val WHITE        = "#FFFFFF"

    // ── Spacing (dp) ──────────────────────────────────────────────────────────
    const val PAD_SIDE = 16
    const val PAD_CARD = 16

    /** Convert dp → px for a context. */
    fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    // ── Root builders ─────────────────────────────────────────────────────────
    /** Scrollable root with the shared sheet background. */
    fun sheetRoot(ctx: Context, bottomPadDp: Int = 40): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ctx, bottomPadDp))
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }

    /** Drag-handle pill at the top of every sheet. */
    fun handle(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(ctx, 12), 0, 0)
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#5A6B85"))
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 4))
            })
        }

    /** Sheet title row with an optional right badge. */
    fun titleRow(ctx: Context, title: String, badge: String?, badgeColor: String = CYAN):
            LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, PAD_SIDE), dp(ctx, 16), dp(ctx, PAD_SIDE), dp(ctx, 8))
            addView(TextView(ctx).apply {
                text = title
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(TEXT_MAIN))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (badge != null) {
                addView(TextView(ctx).apply {
                    text = badge
                    textSize = 9f
                    letterSpacing = 0.14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0B1320"))
                    background = ctx.getDrawable(R.drawable.bg_btn_accent)
                    setPadding(dp(ctx, 10), dp(ctx, 3), dp(ctx, 10), dp(ctx, 3))
                })
            }
        }

    /** Small uppercase section label. */
    fun sectionLabel(ctx: Context, text: String, accent: String = CYAN): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 9f
            letterSpacing = 0.16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(accent))
            setPadding(dp(ctx, PAD_SIDE), dp(ctx, 18), dp(ctx, PAD_SIDE), dp(ctx, 6))
        }

    /** 1px divider. */
    fun divider(ctx: Context): View =
        View(ctx).apply {
            setBackgroundColor(Color.parseColor(DIVIDER))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }

    /** Vertical spacer. */
    fun spacer(ctx: Context, dp: Int): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, dp))
        }

    // ── Cards ─────────────────────────────────────────────────────────────────
    /** Card container with margins. Returns the LinearLayout to fill. */
    fun card(ctx: Context, marginTopDp: Int = 8): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_dark)
            setPadding(dp(ctx, PAD_CARD), dp(ctx, 14),
                       dp(ctx, PAD_CARD), dp(ctx, 14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(ctx, PAD_SIDE), dp(ctx, marginTopDp),
                                 dp(ctx, PAD_SIDE), 0) }
        }

    /** Card title inside a card. */
    fun cardTitle(ctx: Context, text: String, accent: String = CYAN): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 10f
            letterSpacing = 0.12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(accent))
            setPadding(0, 0, 0, dp(ctx, 6))
        }

    /** Small helper/info text. */
    fun infoText(ctx: Context, text: String, color: String = TEXT_MUTED, size: Float = 10f):
            TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            setLineSpacing(0f, 1.25f)
            setPadding(dp(ctx, PAD_SIDE), dp(ctx, 8), dp(ctx, PAD_SIDE), 0)
        }

    /** Subtitle text inside cards. */
    fun subText(ctx: Context, text: String, color: String = TEXT_SUB, size: Float = 11f):
            TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
        }

    // ── Controls ──────────────────────────────────────────────────────────────
    /** Primary filled button. */
    fun primaryButton(ctx: Context, text: String, heightDp: Int = 50): Button =
        Button(ctx).apply {
            this.text = text
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B1320"))
            background = ctx.getDrawable(R.drawable.bg_btn_accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)
            ).apply { setMargins(dp(ctx, PAD_SIDE), dp(ctx, 12), dp(ctx, PAD_SIDE), 0) }
        }

    /** Secondary outlined button. */
    fun secondaryButton(ctx: Context, text: String, accent: String = CYAN,
                        heightDp: Int = 46,
                        onClick: () -> Unit = {}): Button =
        Button(ctx).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(accent))
            background = ctx.getDrawable(R.drawable.bg_card_dark)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)
            ).apply { setMargins(dp(ctx, PAD_SIDE), dp(ctx, 8), dp(ctx, PAD_SIDE), 0) }
            setOnClickListener { onClick() }
        }

    /** Compact chip button (used in preset rows / small actions). */
    fun chipButton(ctx: Context, text: String, accent: String = CYAN,
                   widthDp: Int = 52, heightDp: Int = 38,
                   onClick: () -> Unit = {}): Button =
        Button(ctx).apply {
            this.text = text
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(accent))
            background = ctx.getDrawable(R.drawable.bg_card_dark)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                dp(ctx, widthDp), dp(ctx, heightDp)
            ).apply { setMargins(0, 0, dp(ctx, 6), 0) }
            setOnClickListener { onClick() }
        }

    /** +/- step button. */
    fun stepButton(ctx: Context, label: String, accent: String = CYAN,
                   widthDp: Int = 44, heightDp: Int = 40): Button =
        Button(ctx).apply {
            this.text = label
            textSize = 16f
            setTextColor(Color.parseColor(accent))
            background = ctx.getDrawable(R.drawable.bg_card_dark)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                dp(ctx, widthDp), dp(ctx, heightDp)
            ).apply { setMargins(0, 0, dp(ctx, 8), 0) }
        }

    /** Text input field. */
    fun inputField(ctx: Context, hint: String = "", value: String = "",
                   numeric: Boolean = false, mono: Boolean = false): EditText =
        EditText(ctx).apply {
            this.hint = hint
            setText(value)
            inputType = if (numeric)
                android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            else android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHintTextColor(Color.parseColor("#4A5B73"))
            setTextColor(Color.parseColor(TEXT_MAIN))
            textSize = 13f
            if (mono) typeface = Typeface.MONOSPACE
            background = ctx.getDrawable(R.drawable.bg_input_field)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        }

    /** Themed seek bar. */
    fun seekBar(ctx: Context, accent: String = CYAN, max: Int = 1000): SeekBar =
        SeekBar(ctx).apply {
            this.max = max
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(accent))
            thumbTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(accent))
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), 0)
        }
}
