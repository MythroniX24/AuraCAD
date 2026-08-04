package com.modelviewer3d

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local, on-device storage for the user's Gemini API key. */
object AiPrefs {
    private const val PREFS = "ai_prefs"
    private const val KEY_API = "gemini_api_key"

    fun apiKey(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, "").orEmpty().trim()

    fun saveApiKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API, key.trim()).apply()
    }
}

/**
 * AI Assistant settings — the dedicated section where the user pastes their
 * Google Gemini API key (stored only on this device). Also lets them verify
 * the key with a live test call.
 */
class AiSettingsFragment : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 48)
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        scroll.addView(root)

        // Handle bar
        root.addView(LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL; setPadding(0, 14, 0, 0)
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#404058"))
                layoutParams = LinearLayout.LayoutParams(48, 4)
            })
        })

        // Header
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 14, 20, 6)
            addView(TextView(ctx).apply {
                text = "✨  AI Assistant"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = "GEMINI"
                textSize = 9f; letterSpacing = 0.12f
                setTextColor(Color.parseColor("#00D4FF"))
                background = ctx.getDrawable(R.drawable.bg_pill); setPadding(10, 3, 10, 3)
            })
        })
        root.addView(divider(ctx))

        // Info card
        root.addView(TextView(ctx).apply {
            text = "The AI Ring Fit uses Google Gemini to look at your ring model " +
                "(screenshot of the 3D preview) and understand your request — " +
                "\"US 6\", \"17mm\", \"comfortable size 7\" — then recommends exact " +
                "mm values which you apply with one tap. Sizing math always runs " +
                "locally, so results stay accurate even without a key."
            textSize = 10f; setTextColor(Color.parseColor("#9090B0"))
            setLineSpacing(0f, 1.25f)
            background = ctx.getDrawable(R.drawable.bg_hint_card)
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 10, 14, 0) }
        })

        // API key label + input
        root.addView(TextView(ctx).apply {
            text = "GEMINI API KEY"
            textSize = 9f; letterSpacing = 0.14f
            setTextColor(Color.parseColor("#00D4FF")); setPadding(20, 18, 20, 6)
        })
        val etKey = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "AIza…"
            setHintTextColor(Color.parseColor("#404060"))
            setText(AiPrefs.apiKey(ctx))
            setTextColor(Color.WHITE); textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            background = ctx.getDrawable(R.drawable.bg_input_field)
            setPadding(14, 12, 14, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(20, 0, 20, 0) }
        }
        root.addView(etKey)

        val tvStatus = TextView(ctx).apply {
            text = if (AiPrefs.apiKey(ctx).isNotEmpty()) "✓ Key saved on this device" else "No key yet"
            textSize = 10f
            setTextColor(if (AiPrefs.apiKey(ctx).isNotEmpty()) Color.parseColor("#4CAF82") else Color.parseColor("#FF7043"))
            setPadding(20, 8, 20, 0)
        }
        root.addView(tvStatus)

        // Save + Test buttons
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(20, 12, 20, 0)
            addView(Button(ctx).apply {
                text = "Save Key"
                textSize = 11f; setTextColor(Color.parseColor("#050508"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = ctx.getDrawable(R.drawable.bg_btn_accent)
                layoutParams = LinearLayout.LayoutParams(0, 46, 1f).apply { setMargins(0, 0, 8, 0) }
                setOnClickListener {
                    AiPrefs.saveApiKey(ctx, etKey.text.toString())
                    tvStatus.text = "✓ Key saved on this device"
                    tvStatus.setTextColor(Color.parseColor("#4CAF82"))
                }
            })
            addView(Button(ctx).apply {
                text = "Test Connection"
                textSize = 11f; setTextColor(Color.parseColor("#9090B0"))
                background = ctx.getDrawable(R.drawable.bg_card_dark)
                layoutParams = LinearLayout.LayoutParams(0, 46, 1f).apply { setMargins(8, 0, 0, 0) }
                setOnClickListener {
                    val key = etKey.text.toString().trim()
                    tvStatus.text = "⏳ Testing…"
                    tvStatus.setTextColor(Color.parseColor("#FFD54F"))
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try {
                                val resp = GeminiClient.generate(
                                    key, "You reply with a single word.", "Reply with exactly: OK"
                                )
                                when {
                                    resp == null -> "EMPTY"
                                    resp.contains("OK", ignoreCase = true) -> "OK"
                                    else -> "REPLY:${resp.take(60)}"
                                }
                            } catch (e: Exception) { e.message ?: "FAIL" }
                        }
                        tvStatus.text = when {
                            result == "OK" -> "✅ Connected — Gemini responded!"
                            result == "EMPTY" -> "⚠ Key works but returned no text"
                            result.startsWith("REPLY:") -> "⚠ Got: ${result.removePrefix("REPLY:")}"
                            else -> "❌ ${result.ifEmpty { "Connection failed" }}"
                        }
                        tvStatus.setTextColor(if (result == "OK") Color.parseColor("#4CAF82") else Color.parseColor("#FF7043"))
                    }
                }
            })
        })

        // How to get a key
        root.addView(TextView(ctx).apply {
            text = "🔑 Free key: aistudio.google.com/apikey  →  Create API key\n" +
                "Your key stays on THIS device only (local app storage). A screenshot " +
                "of the ring is sent to Google only when you tap \"AI Fit\"."
            textSize = 9f; setTextColor(Color.parseColor("#505070"))
            setLineSpacing(0f, 1.3f)
            setPadding(20, 14, 20, 0)
        })

        return scroll
    }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1A1A28"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    companion object {
        const val TAG = "AiSettings"
        fun newInstance() = AiSettingsFragment()
    }
}
