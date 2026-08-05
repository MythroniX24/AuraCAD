package com.modelviewer3d

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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
import kotlinx.coroutines.launch

object AiPrefs {
    private const val PREFS = "ai_prefs"
    private const val KEY_API = "gemini_api_key"

    fun apiKey(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_API, "").orEmpty().let(GeminiClient::sanitizeApiKey)

    fun saveApiKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API, GeminiClient.sanitizeApiKey(key)).apply()
    }
}

/** Dedicated AI settings panel. The key remains local to this device. */
class AiSettingsFragment : BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 48)
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        scroll.addView(root)
        root.addView(handle(ctx))
        root.addView(header(ctx))
        root.addView(divider(ctx))

        root.addView(TextView(ctx).apply {
            text = "AI RING FIT"
            textSize = 10f; letterSpacing = .16f
            setTextColor(Color.parseColor("#62E6FF"))
            setPadding(20, 18, 20, 6)
        })
        root.addView(TextView(ctx).apply {
            text = "Gemini can inspect the ring preview and translate requests like US 6, 17 mm, or a comfortable fit into a safe mm recommendation. Local sizing math remains the source of truth."
            textSize = 11f; setTextColor(Color.parseColor("#A8B6C7")); setLineSpacing(0f, 1.2f)
            background = ctx.getDrawable(R.drawable.bg_hint_card); setPadding(16, 14, 16, 14)
            layoutParams = margins(14, 0, 14, 0)
        })

        root.addView(TextView(ctx).apply {
            text = "GOOGLE AI STUDIO KEY"
            textSize = 10f; letterSpacing = .14f; setTextColor(Color.parseColor("#62E6FF"))
            setPadding(20, 20, 20, 7)
        })
        val keyInput = EditText(ctx).apply {
            hint = "AIza…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(AiPrefs.apiKey(ctx))
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#607286")); textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            background = ctx.getDrawable(R.drawable.bg_input_field); setPadding(14, 13, 14, 13)
            layoutParams = margins(20, 0, 20, 0)
        }
        root.addView(keyInput)

        val tvStatus = TextView(ctx).apply {
            textSize = 10f; setPadding(20, 8, 20, 0)
            setTextColor(Color.parseColor("#74869A"))
        }
        root.addView(tvStatus)
        updateSavedStatus(tvStatus, AiPrefs.apiKey(ctx))

        var visible = false
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 10, 20, 0)
            addView(Button(ctx).apply {
                text = "SAVE KEY"; textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#061018")); background = ctx.getDrawable(R.drawable.bg_btn_accent)
                layoutParams = LinearLayout.LayoutParams(0, 46, 1f).apply { setMargins(0, 0, 6, 0) }
                setOnClickListener {
                    val raw = keyInput.text.toString()
                    val error = GeminiClient.validateApiKey(raw)
                    if (error != null) {
                        tvStatus.text = "⚠ $error"; tvStatus.setTextColor(Color.parseColor("#FF9B71"))
                    } else {
                        AiPrefs.saveApiKey(ctx, raw)
                        keyInput.setText(AiPrefs.apiKey(ctx))
                        updateSavedStatus(tvStatus, AiPrefs.apiKey(ctx))
                    }
                }
            })
            addView(Button(ctx).apply {
                text = "SHOW"; textSize = 10f; setTextColor(Color.parseColor("#A8B6C7"))
                background = ctx.getDrawable(R.drawable.bg_card_dark)
                layoutParams = LinearLayout.LayoutParams(76, 46).apply { setMargins(6, 0, 0, 0) }
                setOnClickListener {
                    visible = !visible
                    keyInput.inputType = InputType.TYPE_CLASS_TEXT or
                        if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        else InputType.TYPE_TEXT_VARIATION_PASSWORD
                    keyInput.setSelection(keyInput.text.length)
                    text = if (visible) "HIDE" else "SHOW"
                }
            })
        })

        root.addView(Button(ctx).apply {
            text = "TEST CONNECTION"
            textSize = 11f; setTextColor(Color.parseColor("#62E6FF")); background = ctx.getDrawable(R.drawable.bg_card_dark)
            layoutParams = margins(20, 10, 20, 0)
            setOnClickListener {
                val key = GeminiClient.sanitizeApiKey(keyInput.text.toString())
                val error = GeminiClient.validateApiKey(key)
                if (error != null) {
                    tvStatus.text = "⚠ $error"; tvStatus.setTextColor(Color.parseColor("#FF9B71")); return@setOnClickListener
                }
                AiPrefs.saveApiKey(ctx, key)
                isEnabled = false
                tvStatus.text = "TESTING SECURE CONNECTION…"; tvStatus.setTextColor(Color.parseColor("#FFD54F"))
                lifecycleScope.launch {
                    try {
                        val reply = GeminiClient.generate(
                            key,
                            "Return only valid JSON with a boolean ok field.",
                            "Return exactly: {\"ok\":true}"
                        )
                        val connected = parseConnectionReply(reply)
                        if (!connected) throw GeminiClient.GeminiException("Gemini replied, but the connection test response was invalid.")
                        tvStatus.text = "✓ CONNECTED · Gemini is ready"
                        tvStatus.setTextColor(Color.parseColor("#4CAF82"))
                    } catch (e: GeminiClient.GeminiException) {
                        tvStatus.text = "✕ ${e.message}"
                        tvStatus.setTextColor(Color.parseColor("#FF9B71"))
                    } catch (e: Exception) {
                        tvStatus.text = "✕ Connection failed. Check internet access."
                        tvStatus.setTextColor(Color.parseColor("#FF9B71"))
                    } finally { isEnabled = true }
                }
            }
        })
        root.addView(TextView(ctx).apply {
            text = "Get a key at aistudio.google.com/apikey\nIt is cleaned before storage and never uploaded anywhere except Google's Gemini request when you use AI Fit."
            textSize = 9f; setTextColor(Color.parseColor("#74869A")); setLineSpacing(0f, 1.3f)
            setPadding(20, 16, 20, 0)
        })
        return scroll
    }

    private fun parseConnectionReply(reply: String?): Boolean {
        val normalized = reply.orEmpty()
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val jsonStart = normalized.indexOf('{')
        val jsonEnd = normalized.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return false
        return try { org.json.JSONObject(normalized.substring(jsonStart, jsonEnd + 1)).optBoolean("ok", false) }
        catch (_: Exception) { false }
    }

    private fun updateSavedStatus(tv: TextView, key: String) {
        tv.text = if (key.isBlank()) "No key saved on this device" else "✓ Key saved locally · ${key.take(4)}••••"
        tv.setTextColor(Color.parseColor(if (key.isBlank()) "#74869A" else "#4CAF82"))
    }
    private fun handle(ctx: Context) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 14, 0, 0)
        addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#607286")); layoutParams = LinearLayout.LayoutParams(48, 4) })
    }
    private fun header(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(20, 16, 20, 8)
        addView(TextView(ctx).apply {
            text = "AI WORKSPACE"; textSize = 17f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        addView(TextView(ctx).apply {
            text = "GEMINI"; textSize = 9f; letterSpacing = .12f; setTextColor(Color.parseColor("#62E6FF"))
            background = ctx.getDrawable(R.drawable.bg_pill); setPadding(10, 4, 10, 4)
        })
    }
    private fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#334A6070")); layoutParams = LinearLayout.LayoutParams(-1, 1)
    }
    private fun margins(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(-1, -2).apply { setMargins(l, t, r, b) }
    companion object { const val TAG = "AiSettings"; fun newInstance() = AiSettingsFragment() }
}
