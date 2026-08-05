package com.modelviewer3d

import android.content.Context
import android.content.ClipboardManager
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

/** Local, on-device storage for the Gemini API key. */
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

/**
 * AI Settings — rebuilt from scratch on the AuraCAD UI Kit.
 *
 *  Layout (top → bottom):
 *   1. What this does info card
 *   2. API key status card (saved / not saved)
 *   3. Key input + Show/Hide toggle + Paste from clipboard
 *   4. Save Key (primary)  ·  Test Connection (secondary)
 *   5. Status feedback line + helper footer
 */
class AiSettingsFragment : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000); isFillViewport = true }
        val root = UISheetKit.sheetRoot(ctx)
        scroll.addView(root)

        root.addView(UISheetKit.handle(ctx))
        root.addView(UISheetKit.titleRow(ctx, "AI Settings", "GEMINI"))
        root.addView(UISheetKit.divider(ctx))

        // ── Info card ─────────────────────────────────────────────────────────
        val infoCard = UISheetKit.card(ctx, marginTopDp = 0).apply {
            addView(UISheetKit.cardTitle(ctx, "WHAT THIS DOES", "#4DD8FF"))
            addView(UISheetKit.subText(ctx,
                "Your Gemini key powers AI ring sizing. It is stored only on this " +
                "device and is sent to Google only when you run an AI Fit request.",
                "#7A8BA3", 11f))
        }
        root.addView(infoCard)

        // ── Status card ───────────────────────────────────────────────────────
        val savedKey = AiPrefs.apiKey(ctx)
        val statusCard = UISheetKit.card(ctx, marginTopDp = 10).apply {
            addView(UISheetKit.cardTitle(ctx, "STATUS", "#4CAF82"))
            addView(TextView(ctx).apply {
                text = if (savedKey.isBlank())
                    "No key saved on this device"
                else "✓ Key saved · ${savedKey.take(4)}••••••••"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(if (savedKey.isBlank()) "#FFC46B" else "#4CAF82"))
            })
        }
        root.addView(statusCard)

        // ── Key input card ────────────────────────────────────────────────────
        root.addView(UISheetKit.sectionLabel(ctx, "API KEY"))
        val inputCard = UISheetKit.card(ctx, marginTopDp = 0).apply {
            addView(EditText(ctx).apply {
                hint = "Paste your key — starts with AIza"
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setText(savedKey)
                setHintTextColor(Color.parseColor("#4A5B73"))
                setTextColor(Color.parseColor("#F2F6FB"))
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                background = ctx.getDrawable(R.drawable.bg_input_field)
                setPadding(UISheetKit.dp(ctx, 12), UISheetKit.dp(ctx, 12),
                    UISheetKit.dp(ctx, 12), UISheetKit.dp(ctx, 12))
            }.also { keyInput = it })

            // Action row: Paste · Show/Hide
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, UISheetKit.dp(ctx, 8), 0, 0)
                addView(UISheetKit.secondaryButton(ctx, "📋  Paste", "#A9B8CC", 42).apply {
                    layoutParams = LinearLayout.LayoutParams(0,
                        UISheetKit.dp(ctx, 42), 1f).apply {
                        setMargins(0, 0, UISheetKit.dp(ctx, 6), 0)
                    }
                    setOnClickListener { pasteFromClipboard() }
                })
                addView(Button(ctx).apply {
                    text = "Show"
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#A9B8CC"))
                    background = ctx.getDrawable(R.drawable.bg_card_dark)
                    layoutParams = LinearLayout.LayoutParams(0,
                        UISheetKit.dp(ctx, 42), 1f).apply {
                        setMargins(UISheetKit.dp(ctx, 6), 0, 0, 0)
                    }
                    setOnClickListener { toggleKeyVisibility() }
                }.also { btnToggle = it })
            })
        }
        root.addView(inputCard)

        // ── Action buttons ────────────────────────────────────────────────────
        root.addView(UISheetKit.primaryButton(ctx, "💾  Save Key").apply {
            setOnClickListener { saveKey() }
        })
        root.addView(UISheetKit.secondaryButton(ctx, "🔌  Test Connection").apply {
            setOnClickListener { testConnection() }
        }.also { btnTest = it })

        // ── Feedback line ─────────────────────────────────────────────────────
        tvFeedback = TextView(ctx).apply {
            text = ""
            textSize = 11f
            setLineSpacing(0f, 1.25f)
            setPadding(UISheetKit.dp(ctx, 16), UISheetKit.dp(ctx, 10),
                UISheetKit.dp(ctx, 16), 0)
            setTextColor(Color.parseColor("#7A8BA3"))
        }
        root.addView(tvFeedback)

        // ── Footer ────────────────────────────────────────────────────────────
        root.addView(UISheetKit.infoText(ctx,
            "Get a free key: aistudio.google.com/apikey\n" +
            "The key is cleaned automatically when pasted (spaces, quotes, " +
            "\"GEMINI_API_KEY=\" prefixes are removed).",
            "#5A6B85", 9f))

        return scroll
    }

    private var keyInput: EditText? = null
    private var btnToggle: Button? = null
    private var btnTest: Button? = null
    private var tvFeedback: TextView? = null
    private var keyVisible = false

    private fun pasteFromClipboard() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(requireContext()).toString()
        if (text.isBlank()) return
        val cleaned = GeminiClient.sanitizeApiKey(text)
        keyInput?.setText(cleaned)
        setFeedback(if (cleaned.length >= 20) "✓ Key pasted and cleaned" else "Pasted — check it looks complete",
            if (cleaned.length >= 20) "#4CAF82" else "#FFC46B")
    }

    private fun toggleKeyVisibility() {
        keyVisible = !keyVisible
        val t = keyInput ?: return
        t.inputType = InputType.TYPE_CLASS_TEXT or
            (if (keyVisible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
             else InputType.TYPE_TEXT_VARIATION_PASSWORD)
        t.setSelection(t.text.length)
        btnToggle?.text = if (keyVisible) "Hide" else "Show"
    }

    private fun saveKey() {
        val raw = keyInput?.text?.toString().orEmpty()
        val error = GeminiClient.validateApiKey(raw)
        if (error != null) {
            setFeedback("⚠ $error", "#FF7A72")
            return
        }
        AiPrefs.saveApiKey(requireContext(), raw)
        keyInput?.setText(AiPrefs.apiKey(requireContext()))
        setFeedback("✓ Key saved on this device", "#4CAF82")
    }

    private fun testConnection() {
        val ctx = requireContext()
        val raw = keyInput?.text?.toString().orEmpty()
        val key = GeminiClient.sanitizeApiKey(raw)
        val error = GeminiClient.validateApiKey(key)
        if (error != null) {
            setFeedback("⚠ $error", "#FF7A72")
            return
        }
        AiPrefs.saveApiKey(ctx, key)
        btnTest?.isEnabled = false
        setFeedback("Testing secure connection…", "#FFD54F")
        lifecycleScope.launch {
            try {
                val reply = GeminiClient.generate(
                    key,
                    "Return only valid JSON with a boolean ok field.",
                    "Return exactly: {\"ok\":true}"
                )
                val connected = parseConnectionReply(reply)
                setFeedback(
                    if (connected) "✓ Connected · Gemini is ready" else "✕ Gemini replied with an unexpected response",
                    if (connected) "#4CAF82" else "#FF7A72")
            } catch (e: GeminiClient.GeminiException) {
                setFeedback("✕ ${e.message}", "#FF7A72")
            } catch (e: Exception) {
                setFeedback("✕ Connection failed. Check internet access.", "#FF7A72")
            } finally {
                btnTest?.isEnabled = true
            }
        }
    }

    private fun setFeedback(msg: String, color: String) {
        tvFeedback?.text = msg
        tvFeedback?.setTextColor(Color.parseColor(color))
    }

    private fun parseConnectionReply(reply: String?): Boolean {
        val normalized = reply.orEmpty()
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = normalized.indexOf('{')
        val end = normalized.lastIndexOf('}')
        if (start < 0 || end <= start) return false
        return try {
            org.json.JSONObject(normalized.substring(start, end + 1))
                .optBoolean("ok", false)
        } catch (_: Exception) { false }
    }

    companion object {
        const val TAG = "AiSettings"
        fun newInstance() = AiSettingsFragment()
    }
}
