package com.modelviewer3d

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch

/**
 * Ring Tool — rebuilt from scratch on the AuraCAD UI Kit.
 *
 *  Layout (top → bottom, no overlap):
 *   1. Live ring preview card
 *   2. Detection card (mesh target + Detect button + status)
 *   3. Summary card (appears after detection)
 *   4. Proportional toggle row
 *   5. Three measurement cards (Band Width / Inner Diameter / Height)
 *      each with a big value field, −/+ steppers, slider and live info
 *   6. US size preset chips (horizontal scroll row)
 *   7. Action row: Reset + Re-Detect
 */
class RingToolFragment : BottomSheetDialogFragment() {

    // Native ring params (mm)
    private var origInnerDiaMM  = 0f
    private var origBandWidthMM = 0f
    private var origHeightMM    = 0f
    private var origOuterDiaMM  = 0f

    // Slider ranges (set after detection)
    private var bwMin = 0.05f;  private var bwMax = 20f
    private var idMin = 1f;     private var idMax = 50f
    private var hMin  = 0.5f;   private var hMax  = 50f
    private val STEPS = 1000

    private var targetMeshIdx = 0
    private var ringAnalyzed  = false
    private var proportional = false

    // Suppress callbacks while programmatically seeding sliders
    @Volatile private var suppressBW = false
    @Volatile private var suppressID = false
    @Volatile private var suppressH  = false

    // UI refs
    private var etMeshIdx: EditText? = null
    private var tvStatus:  TextView? = null
    private var tvSummary: TextView? = null
    private var preview:   RingPreviewView? = null

    private var sbBW: SeekBar? = null; private var etBW: EditText? = null; private var tvBW: TextView? = null
    private var sbID: SeekBar? = null; private var etID: EditText? = null; private var tvID: TextView? = null
    private var sbH:  SeekBar? = null; private var etH:  EditText? = null; private var tvH:  TextView? = null

    private var cardBW: View? = null
    private var cardID: View? = null
    private var cardH:  View? = null
    // Field type is View on purpose — assigning LinearLayout children
    // (measureCard result) must stay View-typed to avoid smart-cast errors.
    private var presetScroll: LinearLayout? = null

    // Gemini AI Ring Fit
    private var aiCard: View? = null
    private var aiTargetInput: EditText? = null
    private var aiStatus: TextView? = null
    private var aiButton: Button? = null

    private var lastBW = -1f
    private var lastID = -1f
    private var lastH  = -1f

    private val usPresets = listOf(
        3f to 14.1f, 4f to 14.9f, 5f to 15.7f, 6f to 16.5f, 7f to 17.3f,
        8f to 18.1f, 9f to 18.9f, 10f to 19.8f, 11f to 20.6f, 12f to 21.4f
    )

    private fun watcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { action() }
    }

    private fun glRun(block: () -> Unit) =
        (activity as? MainActivity)?.glView?.queueEvent(block)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000); isFillViewport = true }
        val root = UISheetKit.sheetRoot(ctx)
        scroll.addView(root)

        root.addView(UISheetKit.handle(ctx))
        root.addView(UISheetKit.titleRow(ctx, "Ring Tool", "REBUILT"))
        root.addView(UISheetKit.divider(ctx))

        // ── 1. Live preview card ───────────────────────────────────────────────
        root.addView(UISheetKit.sectionLabel(ctx, "LIVE PREVIEW"))
        val previewCard = UISheetKit.card(ctx, marginTopDp = 0).apply {
            addView(RingPreviewView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, UISheetKit.dp(ctx, 150))
            }.also { preview = it })
            addView(UISheetKit.infoText(ctx, "Top view — updates live as you resize.",
                UISheetKit.TEXT_MUTED, 10f))
        }
        root.addView(previewCard)

        // ── 2. Detection card ──────────────────────────────────────────────────
        root.addView(UISheetKit.sectionLabel(ctx, "DETECT RING"))
        val detectCard = UISheetKit.card(ctx, marginTopDp = 0).apply {
            // Mesh target row
            val meshRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(UISheetKit.subText(ctx, "Mesh", UISheetKit.TEXT_SUB, 12f).apply {
                    setPadding(0, 0, UISheetKit.dp(ctx, 10), 0)
                })
                addView(UISheetKit.inputField(ctx, "", "0", numeric = true).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        UISheetKit.dp(ctx, 84), LinearLayout.LayoutParams.WRAP_CONTENT)
                    addTextChangedListener(watcher {
                        targetMeshIdx = text.toString().toIntOrNull() ?: 0
                    })
                }.also { etMeshIdx = it })
                addView(UISheetKit.infoText(ctx, "0 = whole model · long-press a mesh to target it",
                    UISheetKit.TEXT_MUTED, 10f).apply {
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            addView(meshRow)
            addView(UISheetKit.spacer(ctx, 10))

            // Detect button
            addView(Button(ctx).apply {
                text = "▶  Detect Ring Geometry"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#0B1320"))
                background = ctx.getDrawable(R.drawable.bg_btn_accent)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, UISheetKit.dp(ctx, 50))
                setOnClickListener { detect() }
            })
            // Status line
            addView(TextView(ctx).apply {
                text = "No ring detected yet"
                textSize = 11f
                setTextColor(Color.parseColor("#7A8BA3"))
                setPadding(0, UISheetKit.dp(ctx, 10), 0, 0)
            }.also { tvStatus = it })
        }
        root.addView(detectCard)

        // ── 3. Summary card (hidden until detection) ───────────────────────────
        tvSummary = TextView(ctx).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#4DD8FF"))
            background = ctx.getDrawable(R.drawable.bg_hint_card)
            setPadding(UISheetKit.dp(ctx, 14), UISheetKit.dp(ctx, 12),
                UISheetKit.dp(ctx, 14), UISheetKit.dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(UISheetKit.dp(ctx, 16), UISheetKit.dp(ctx, 12),
                UISheetKit.dp(ctx, 16), 0) }
            visibility = View.GONE
        }
        root.addView(tvSummary)

        // ── 4. Proportional toggle ─────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UISheetKit.dp(ctx, 16), UISheetKit.dp(ctx, 10),
                UISheetKit.dp(ctx, 16), 0)
            addView(TextView(ctx).apply {
                text = "🔗 Proportional resize (scale everything together)"
                textSize = 12f
                setTextColor(Color.parseColor("#A9B8CC"))
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Switch(ctx).apply {
                isChecked = false
                thumbTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#4DD8FF"))
                trackTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#1C3A4E"))
                setOnCheckedChangeListener { _, v -> proportional = v }
            })
        })

        // ── 5. Measurement cards ───────────────────────────────────────────────
        root.addView(UISheetKit.sectionLabel(ctx, "MEASUREMENTS"))

        val bwCard = measureCard(
            ctx, "BAND WIDTH", "Outer wall thickness · bore stays fixed",
            "#4DD8FF", 0.25f,
            onSbInit = { sbBW = it }, onEtInit = { etBW = it }, onInfoInit = { tvBW = it },
            onChange = { onBandChanged(it) })
        bwCard.visibility = View.GONE; root.addView(bwCard); cardBW = bwCard

        val idCard = measureCard(
            ctx, "INNER DIAMETER", "Ring size — hole resizes, wall stays",
            "#FFC46B", 0.5f,
            onSbInit = { sbID = it }, onEtInit = { etID = it }, onInfoInit = { tvID = it },
            onChange = { onInnerChanged(it) })
        idCard.visibility = View.GONE; root.addView(idCard); cardID = idCard

        val hCard = measureCard(
            ctx, "HEIGHT", "Stretch / squash along the ring axis",
            "#A78BFA", 0.5f,
            onSbInit = { sbH = it }, onEtInit = { etH = it }, onInfoInit = { tvH = it },
            onChange = { onHeightChanged(it) })
        hCard.visibility = View.GONE; root.addView(hCard); cardH = hCard

        // ── 6. US size presets ─────────────────────────────────────────────────
        root.addView(UISheetKit.sectionLabel(ctx, "QUICK US SIZES"))
        val presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(UISheetKit.dp(ctx, 16), 0, UISheetKit.dp(ctx, 16), 0)
        }
        presetScroll = presetRow
        presetRow.visibility = View.GONE
        root.addView(presetRow)

        // ── 7. Action row ──────────────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(UISheetKit.dp(ctx, 16), UISheetKit.dp(ctx, 14),
                UISheetKit.dp(ctx, 16), 0)
            addView(UISheetKit.secondaryButton(ctx, "↺  Reset", "#FF7A72", 46).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, UISheetKit.dp(ctx, 46)
                ).apply { setMargins(0, 0, UISheetKit.dp(ctx, 10), 0) }
                setOnClickListener {
                    glRun {
                        NativeLib.nativeResetRingDeformation()
                        activity?.runOnUiThread {
                            tvStatus?.text = "✓ Deformation reset"
                            tvStatus?.setTextColor(Color.parseColor("#4CAF82"))
                        }
                    }
                }
            })
            addView(UISheetKit.secondaryButton(ctx, "↻  Re-Detect", "#A9B8CC", 46).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, UISheetKit.dp(ctx, 46))
                setOnClickListener { detect() }
            })
        })

        // ── AI visual ring fitting ────────────────────────────────────────────
        // Gemini receives the native screenshot plus the measured ring values,
        // returns strict JSON, and the validated values are applied through the
        // same native async setters used by the manual controls.
        val builtAiCard = UISheetKit.card(ctx, marginTopDp = 12).apply {
            addView(UISheetKit.cardTitle(ctx, "AI RING FIT", "#A78BFA"))
            addView(UISheetKit.subText(ctx,
                "Gemini visually inspects the ring preview, reasons about the requested size, " +
                    "then applies a safe inner diameter, band width and height.",
                "#A9B8CC", 10f))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, UISheetKit.dp(ctx, 10), 0, 0)
                addView(UISheetKit.inputField(ctx, "Target inner diameter", "", numeric = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }.also { aiTargetInput = it })
                addView(TextView(ctx).apply {
                    text = "  mm"
                    textSize = 12f
                    setTextColor(Color.parseColor("#7A8BA3"))
                })
            })
            addView(UISheetKit.primaryButton(ctx, "✨  Analyze & Fit with Gemini", 48).apply {
                setOnClickListener { runAiRingFit() }
            }.also { aiButton = it })
            addView(TextView(ctx).apply {
                text = ""
                textSize = 10f
                setLineSpacing(0f, 1.2f)
                setPadding(0, UISheetKit.dp(ctx, 8), 0, 0)
                setTextColor(Color.parseColor("#7A8BA3"))
            }.also { aiStatus = it })
        }
        // Available immediately: Gemini can visually inspect the preview and
        // native-detect the selected mesh as part of the fit request.
        builtAiCard.visibility = View.VISIBLE
        aiCard = builtAiCard
        root.addView(builtAiCard)

        return scroll
    }

    // ── Measurement card builder ──────────────────────────────────────────────
    private fun measureCard(
        ctx: android.content.Context,
        header: String, desc: String, accentHex: String, step: Float,
        onSbInit: (SeekBar) -> Unit,
        onEtInit: (EditText) -> Unit,
        onInfoInit: (TextView) -> Unit,
        onChange: (Float) -> Unit
    ): LinearLayout {
        val accent = Color.parseColor(accentHex)
        val card = UISheetKit.card(ctx)

        card.addView(UISheetKit.cardTitle(ctx, header, accentHex))
        card.addView(UISheetKit.subText(ctx, desc, UISheetKit.TEXT_MUTED, 10f))

        // Value row: big input + unit + steppers
        val valueRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UISheetKit.dp(ctx, 8), 0, 0)
            addView(EditText(ctx).apply {
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(accentHex))
                background = ctx.getDrawable(R.drawable.bg_input_field)
                setPadding(UISheetKit.dp(ctx, 14), UISheetKit.dp(ctx, 10),
                    UISheetKit.dp(ctx, 14), UISheetKit.dp(ctx, 10))
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText("0.00")
                layoutParams = LinearLayout.LayoutParams(
                    UISheetKit.dp(ctx, 130), LinearLayout.LayoutParams.WRAP_CONTENT)
                addTextChangedListener(watcher {
                    if (!ringAnalyzed) return@watcher
                    val suppressed = when (accentHex) {
                        "#4DD8FF" -> suppressBW
                        "#FFC46B" -> suppressID
                        else      -> suppressH
                    }
                    if (suppressed) return@watcher
                    val v = text.toString().toFloatOrNull() ?: return@watcher
                    val (min, max) = rangeFor(accentHex)
                    if (v < min * 0.5f || v > max * 2f) return@watcher
                    onChange(v.coerceIn(min, max))
                })
            }.also { onEtInit(it) })
            addView(TextView(ctx).apply {
                text = "  mm"
                textSize = 13f
                setTextColor(Color.parseColor("#7A8BA3"))
            })
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            addView(UISheetKit.stepButton(ctx, "−", accentHex) {
                val cur = currentValue(accentHex)
                applyValue(cur - step, accentHex, fromUser = true, onChange = onChange)
            })
            addView(UISheetKit.stepButton(ctx, "+", accentHex) {
                val cur = currentValue(accentHex)
                applyValue(cur + step, accentHex, fromUser = true, onChange = onChange)
            })
        }
        card.addView(valueRow)

        // Slider
        val sb = UISheetKit.seekBar(ctx, accentHex)
        onSbInit(sb)
        card.addView(sb)

        // Live info line
        val tv = TextView(ctx).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#7A8BA3"))
            setPadding(0, 0, 0, 0)
        }
        onInfoInit(tv)
        card.addView(tv)

        // Slider events
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
            override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || !ringAnalyzed) return
                val (min, max) = rangeFor(accentHex)
                val v = min + p.toFloat() / STEPS * (max - min)
                val txt = "%.2f".format(v)
                when (accentHex) {
                    "#4DD8FF" -> { if (!suppressBW) { suppressBW = true; etBW?.setText(txt); suppressBW = false } }
                    "#FFC46B" -> { if (!suppressID) { suppressID = true; etID?.setText(txt); suppressID = false } }
                    else      -> { if (!suppressH)  { suppressH  = true; etH?.setText(txt);  suppressH  = false } }
                }
                onChange(v)
            }
        })

        // Input events
        return card
    }

    // ── Value helpers ─────────────────────────────────────────────────────────
    private fun currentValue(accentHex: String): Float = when (accentHex) {
        "#4DD8FF" -> etBW?.text?.toString()?.toFloatOrNull() ?: 0f
        "#FFC46B" -> etID?.text?.toString()?.toFloatOrNull() ?: 0f
        else      -> etH?.text?.toString()?.toFloatOrNull() ?: 0f
    }

    private fun rangeFor(accentHex: String): Pair<Float, Float> = when (accentHex) {
        "#4DD8FF" -> bwMin to bwMax
        "#FFC46B" -> idMin to idMax
        else      -> hMin to hMax
    }

    private fun applyValue(v: Float, accentHex: String, fromUser: Boolean,
                           onChange: (Float) -> Unit = {}) {
        val (min, max) = rangeFor(accentHex)
        val clamped = v.coerceIn(min, max)
        val txt = "%.2f".format(clamped)
        when (accentHex) {
            "#4DD8FF" -> { suppressBW = true; etBW?.setText(txt); sbBW?.progress = valueToProgress(clamped, min, max); suppressBW = false }
            "#FFC46B" -> { suppressID = true; etID?.setText(txt); sbID?.progress = valueToProgress(clamped, min, max); suppressID = false }
            else      -> { suppressH  = true; etH?.setText(txt);  sbH?.progress  = valueToProgress(clamped, min, max); suppressH  = false }
        }
        if (fromUser) onChange(clamped)
    }

    // ── Change handlers ───────────────────────────────────────────────────────
    private fun onBandChanged(v: Float) {
        if (!ringAnalyzed || v == lastBW) return
        lastBW = v
        if (proportional) {
            val ratio = v / origBandWidthMM
            if (ratio > 0.01f) {
                lastID = origInnerDiaMM * ratio; lastH = origHeightMM * ratio
                applyValue(lastID, "#FFC46B", fromUser = true)
                applyValue(lastH, "#A78BFA", fromUser = true)
                glRun {
                    NativeLib.nativeSetRingBandWidthAsync(v)
                    NativeLib.nativeSetRingInnerDiameterAsync(lastID)
                    NativeLib.nativeSetRingHeightAsync(lastH)
                }
            }
        } else {
            glRun { NativeLib.nativeSetRingBandWidthAsync(v) }
        }
        activity?.runOnUiThread { updateInfo(); updatePreview() }
    }

    private fun onInnerChanged(v: Float) {
        if (!ringAnalyzed || v == lastID) return
        lastID = v
        if (proportional) {
            val ratio = v / origInnerDiaMM
            if (ratio > 0.01f) {
                lastBW = origBandWidthMM * ratio; lastH = origHeightMM * ratio
                applyValue(lastBW, "#4DD8FF", fromUser = true)
                applyValue(lastH, "#A78BFA", fromUser = true)
                glRun {
                    NativeLib.nativeSetRingInnerDiameterAsync(v)
                    NativeLib.nativeSetRingBandWidthAsync(lastBW)
                    NativeLib.nativeSetRingHeightAsync(lastH)
                }
            }
        } else {
            glRun { NativeLib.nativeSetRingInnerDiameterAsync(v) }
        }
        activity?.runOnUiThread { updateInfo(); updatePreview() }
    }

    private fun onHeightChanged(v: Float) {
        if (!ringAnalyzed || v == lastH) return
        lastH = v
        if (proportional) {
            val ratio = v / origHeightMM
            if (ratio > 0.01f) {
                lastBW = origBandWidthMM * ratio; lastID = origInnerDiaMM * ratio
                applyValue(lastBW, "#4DD8FF", fromUser = true)
                applyValue(lastID, "#FFC46B", fromUser = true)
                glRun {
                    NativeLib.nativeSetRingHeightAsync(v)
                    NativeLib.nativeSetRingBandWidthAsync(lastBW)
                    NativeLib.nativeSetRingInnerDiameterAsync(lastID)
                }
            }
        } else {
            glRun { NativeLib.nativeSetRingHeightAsync(v) }
        }
        activity?.runOnUiThread { updateInfo(); updatePreview() }
    }

    // ── Detection ─────────────────────────────────────────────────────────────
    private fun detect() {
        ringAnalyzed = false
        cardBW?.visibility = View.GONE
        cardID?.visibility = View.GONE
        cardH?.visibility = View.GONE
        presetScroll?.visibility = View.GONE
        tvSummary?.visibility = View.GONE
        tvStatus?.text = "Analyzing ring geometry…"
        tvStatus?.setTextColor(Color.parseColor("#FFD54F"))

        glRun {
            val ok = try { NativeLib.nativeAnalyzeRing(targetMeshIdx) } catch (_: Exception) { false }
            if (ok) {
                val p = NativeLib.nativeGetRingParams()
                activity?.runOnUiThread {
                    if (p.size >= 6) applyParams(p)
                    else {
                        tvStatus?.text = "Could not read ring parameters"
                        tvStatus?.setTextColor(Color.parseColor("#FF7A72"))
                    }
                }
            } else {
                activity?.runOnUiThread {
                    tvStatus?.text = "Mesh #$targetMeshIdx is not a ring shape"
                    tvStatus?.setTextColor(Color.parseColor("#FF7A72"))
                }
            }
        }
    }

    /** p = [innerRad, outerRad, bandWidth, innerDia, outerDia, height] */
    private fun applyParams(p: FloatArray) {
        origInnerRadMM0 = p[0]
        origOuterDiaMM  = p[4]
        origInnerDiaMM  = p[3]
        origBandWidthMM = p[2]
        origHeightMM    = p[5]

        bwMin = (origBandWidthMM * 0.1f).coerceAtLeast(0.05f)
        bwMax = (origBandWidthMM * 3.5f).coerceAtMost(50f)
        idMin = (origInnerDiaMM * 0.5f).coerceAtLeast(1f)
        idMax = (origInnerDiaMM * 2.0f).coerceAtMost(80f)
        hMin  = (origHeightMM * 0.3f).coerceAtLeast(0.5f)
        hMax  = (origHeightMM * 3.0f).coerceAtMost(80f)

        lastBW = origBandWidthMM
        lastID = origInnerDiaMM
        lastH  = origHeightMM
        ringAnalyzed = true

        applyValue(origBandWidthMM, "#4DD8FF", fromUser = false)
        applyValue(origInnerDiaMM,  "#FFC46B", fromUser = false)
        applyValue(origHeightMM,    "#A78BFA", fromUser = false)

        tvStatus?.text = "✓ Ring detected"
        tvStatus?.setTextColor(Color.parseColor("#4CAF82"))

        tvSummary?.text = "📋 Inner ⌀ %.2f mm · Outer ⌀ %.2f mm · Band %.2f mm · Height %.2f mm · %s".format(
            origInnerDiaMM, origOuterDiaMM, origBandWidthMM, origHeightMM,
            RingMath.usSizeLabel(origInnerDiaMM))
        tvSummary?.visibility = View.VISIBLE

        cardBW?.visibility = View.VISIBLE
        cardID?.visibility = View.VISIBLE
        cardH?.visibility = View.VISIBLE
        buildPresets()
        presetScroll?.visibility = View.VISIBLE
        aiCard?.visibility = View.VISIBLE
        updateInfo(); updatePreview()
    }

    private var origInnerRadMM0 = 0f // not used directly; kept for clarity

    private fun buildPresets() {
        val ctx = requireContext()
        presetScroll?.removeAllViews()
        presetScroll?.addView(TextView(ctx).apply {
            text = "US:"
            textSize = 11f
            setTextColor(Color.parseColor("#A9B8CC"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, UISheetKit.dp(ctx, 8), 0)
        })
        usPresets.forEach { (us, diaMM) ->
            presetScroll?.addView(UISheetKit.chipButton(ctx, "$us", "#4DD8FF", onClick = {
                if (!ringAnalyzed) return@chipButton
                applyValue(diaMM.coerceIn(idMin, idMax), "#FFC46B", fromUser = true)
                tvStatus?.text = "→ ${RingMath.usSizeLabel(diaMM)} selected"
                tvStatus?.setTextColor(Color.parseColor("#4CAF82"))
            }))
        }
    }

    // ── Info + preview updates ────────────────────────────────────────────────
    private fun updateInfo() {
        val bw = lastBW; val id = lastID; val h = lastH
        if (bw > 0f) {
            tvBW?.text = "Outer ⌀ → %.2f mm · Band %.2f mm".format(
                RingMath.outerDia(id, bw), bw)
        }
        if (id > 0f) {
            tvID?.text = "%s · C %.1f mm".format(
                RingMath.usSizeLabel(id), RingMath.circumferenceMM(id))
        }
        if (h > 0f) {
            tvH?.text = "%.2f mm (%d%% of original)".format(
                h, if (origHeightMM > 0f) (h / origHeightMM * 100).toInt() else 100)
        }
    }

    private fun updatePreview() {
        if (lastID > 0f && lastBW > 0f) {
            preview?.innerDiaMM = lastID
            preview?.outerDiaMM = RingMath.outerDia(lastID, lastBW)
        }
    }

    private fun valueToProgress(v: Float, min: Float, max: Float) =
        ((v - min) / (max - min) * STEPS).toInt().coerceIn(0, STEPS)

    // ── Gemini visual ring fitting ───────────────────────────────────────────
    private fun runAiRingFit() {
        val target = aiTargetInput?.text?.toString()?.toFloatOrNull()
        if (target == null || !target.isFinite() || target <= 0f) {
            setAiStatus("Enter a target inner diameter in millimetres.", "#FF7A72")
            return
        }
        // Range is validated against the detected ring's real limits AFTER
        // auto-detection below (the defaults before detection are too wide).
        val ctx = requireContext()
        val key = AiPrefs.apiKey(ctx)
        val keyError = GeminiClient.validateApiKey(key)
        if (keyError != null) {
            setAiStatus("$keyError Open ⋯ → AI Assistant to save a key.", "#FF7A72")
            return
        }

        aiButton?.isEnabled = false
        setAiStatus("Capturing calibrated top + side ring views…", "#FFD54F")
        lifecycleScope.launch {
            try {
                if (!ringAnalyzed) {
                    val detected = withContext(Dispatchers.IO) {
                        var ok = false
                        val latch = CountDownLatch(1)
                        glRun {
                            ok = try { NativeLib.nativeAnalyzeRing(targetMeshIdx) } catch (_: Exception) { false }
                            latch.countDown()
                        }
                        latch.await()
                        ok
                    }
                    if (!detected) throw IllegalArgumentException("Selected mesh is not recognized as a ring")
                    val params = withContext(Dispatchers.IO) {
                        var p = FloatArray(0)
                        val latch = CountDownLatch(1)
                        glRun {
                            p = try { NativeLib.nativeGetRingParams() } catch (_: Exception) { FloatArray(0) }
                            latch.countDown()
                        }
                        latch.await()
                        p
                    }
                    if (params.size < 6) throw IllegalArgumentException("Ring measurements could not be read")
                    withContext(Dispatchers.Main) { applyParams(params) }
                }
                if (target < idMin || target > idMax) {
                    throw IllegalArgumentException(
                        "Target must be between %.2f and %.2f mm for this ring.".format(idMin, idMax))
                }
                // Multi-angle vision: capture the calibrated TOP view (diameter)
                // plus the SIDE view (height/band) so Gemini can cross-check.
                // The side image is only sent when it is a real inspection shot,
                // so a failed capture can't send two identical fallback images.
                val topImage = withContext(Dispatchers.IO) {
                    captureRingInspectionBase64(0) ?: capturePreviewBase64()
                }
                if (topImage.isNullOrBlank()) throw IllegalStateException("Could not capture the ring preview")
                val sideImage = withContext(Dispatchers.IO) { captureRingInspectionBase64(1) }
                val images = listOfNotNull(topImage, sideImage)

                // Up to 2 attempts: on invalid/unsafe output, retry once with
                // the exact rejection reason so Gemini can correct itself.
                var fit: AiFitResult? = null
                var lastReason = ""
                var replyModel = ""
                for (attempt in 0 until 2) {
                    val feedback = if (attempt > 0 && lastReason.isNotBlank()) {
                        "\nYour previous output was rejected: $lastReason. Return corrected values."
                    } else ""
                    val prompt = aiFitPrompt(target, feedback)
                    val reply = GeminiClient.generate(
                        apiKey = key,
                        systemPrompt = "You are AuraCAD's precise jewelry fitting assistant. Image 1 is a TOP view looking down the ring axis (RED line = inner diameter, CYAN line = band width, WHITE bar = 10 mm). Image 2 is a SIDE view, edge-on (GREEN line = height along the axis, CYAN line = band width, WHITE bar = 10 mm). Use the WHITE bars to convert pixels to mm. Cross-check both views and the reported native measurements, then choose manufacturable values. Never invent unsafe geometry. Return only the requested JSON.",
                        userPrompt = prompt,
                        pngBase64 = images.firstOrNull(),
                        extraImages = images.drop(1),
                        model = AiPrefs.model(ctx)
                    )
                    replyModel = reply.model
                    val json = parseAiFit(reply.text)
                    val candidate = validateAiFit(json, target)
                    if (candidate == null) {
                        lastReason = aiRejectReason(json)
                        continue
                    }
                    fit = candidate
                    lastReason = json.optString("reason", "Fit applied")
                    break
                }
                val f = fit ?: throw IllegalArgumentException("Gemini measurements rejected twice ($lastReason)")

                val safeId = f.innerId.coerceIn(idMin, idMax)
                val safeBw = f.band.coerceIn(bwMin, bwMax)
                val safeH = f.height.coerceIn(hMin, hMax)
                // Apply all three values atomically in ONE native call (a single
                // undo snapshot). Proportional mode is suspended so the setters
                // don't recalculate each other's dimensions and drift the result.
                val wasProportional = proportional
                proportional = false
                lastBW = safeBw; lastID = safeId; lastH = safeH
                glRun {
                    NativeLib.nativePushUndoState()
                    NativeLib.nativeSetRingBandWidthAsync(safeBw)
                    NativeLib.nativeSetRingInnerDiameterAsync(safeId)
                    NativeLib.nativeSetRingHeightAsync(safeH)
                }
                applyValue(safeBw, "#4DD8FF", fromUser = false)
                applyValue(safeId, "#FFC46B", fromUser = false)
                applyValue(safeH, "#A78BFA", fromUser = false)
                proportional = wasProportional
                activity?.runOnUiThread { updateInfo(); updatePreview() }
                val usLabel = RingMath.usSizeLabel(safeId)
                val measured = f.measuredInner.takeIf { it > 0f }
                val check = if (measured != null && origInnerDiaMM > 0f) {
                    val dev = (measured - origInnerDiaMM) / origInnerDiaMM * 100f
                    " · AI saw ${measured}mm (±${"%.0f".format(kotlin.math.abs(dev))}%)"
                } else ""
                setAiStatus("✓ $replyModel: $usLabel · %.2f mm inner · %.2f mm band · %.2f mm height%s\n%s".format(safeId, safeBw, safeH, check, lastReason), "#4CAF82")
            } catch (e: GeminiClient.GeminiException) {
                setAiStatus("Gemini: ${e.message}", "#FF7A72")
            } catch (e: Exception) {
                setAiStatus("AI fit failed: ${e.message ?: "try again"}", "#FF7A72")
            } finally {
                aiButton?.isEnabled = true
            }
        }
    }

    private fun parseAiFit(raw: String?): JSONObject {
        val text = raw.orEmpty().replace("```json", "", true).replace("```", "").trim()
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw IllegalArgumentException("Gemini did not return JSON")
        return JSONObject(text.substring(start, end + 1))
    }

    /** Validated Gemini ring-fit output. */
    private data class AiFitResult(
        val innerId: Float,
        val band: Float,
        val height: Float,
        val measuredInner: Float
    )

    /** Builds the AI prompt with the measured geometry + both-view legend. */
    private fun aiFitPrompt(target: Float, feedback: String): String {
        val currentUs = RingMath.usSizeLabel(origInnerDiaMM)
        return """
            You get TWO calibrated AuraCAD ring images from different angles. In BOTH images a WHITE bar is EXACTLY 10 mm — use it as the pixel-to-mm scale.
            Image 1 (TOP view, looking down the ring axis): RED line = current inner diameter across the opening, CYAN line = band width, WHITE bar = 10 mm.
            Image 2 (SIDE view, edge-on): GREEN line = ring height along its axis, CYAN line = band width, WHITE bar = 10 mm.
            The user wants the ring resized to an inner diameter of $target mm (≈ ${RingMath.usSizeLabel(target)}).
            Current native measurements: innerDiameter=${origInnerDiaMM} mm ($currentUs), bandWidth=${origBandWidthMM} mm, height=${origHeightMM} mm.
            Visually measure the CURRENT inner diameter from Image 1 and report it in measuredInnerDiameterMM; cross-check the height against Image 2.
            Then choose safe manufacturable target values: innerDiameterMM close to $target, bandWidthMM and heightMM sane for a ring (band 0.5–6 mm, height 0.5–20 mm).
            JSON schema exactly:
            {"innerDiameterMM": number, "bandWidthMM": number, "heightMM": number, "measuredInnerDiameterMM": number, "reason": "short explanation"}
            Do not include markdown or extra keys. Keep all values positive.$feedback
        """.trimIndent()
    }

    /**
     * Validates a parsed AI fit. Returns an [AiFitResult] when safe, or null
     * when the values are missing/non-finite/physically impossible. The inner
     * diameter is snapped to the nearest standard US size for manufacturability
     * (kept inside the detected range).
     */
    private fun validateAiFit(json: JSONObject, target: Float): AiFitResult? {
        val id = json.optDouble("innerDiameterMM", Double.NaN)
        val bw = json.optDouble("bandWidthMM", Double.NaN)
        val h  = json.optDouble("heightMM", Double.NaN)
        val measured = json.optDouble("measuredInnerDiameterMM", Double.NaN)
        if (!id.isFinite() || !bw.isFinite() || !h.isFinite()) return null
        val fId = id.toFloat(); val fBw = bw.toFloat(); val fH = h.toFloat()
        if (fId <= 0f || fBw <= 0f || fH <= 0f) return null
        // Physical sanity — a real ring can't have these proportions.
        if (fBw < 0.5f || fBw > 6f) return null
        if (fH < 0.5f || fH > 20f) return null
        if (fBw >= fId * 0.45f) return null
        // The requested target must win — AI should land near it.
        val snapped = RingMath.usSizeToDiam(RingMath.diamToUSSize(fId))
        val finalId = if (kotlin.math.abs(snapped - fId) <= 0.3f) snapped else fId
        if (finalId < idMin || finalId > idMax) return null
        return AiFitResult(
            innerId = finalId,
            band = fBw,
            height = fH,
            measuredInner = if (measured.isFinite() && measured > 0) measured.toFloat() else -1f
        )
    }

    /** Human-readable reason an AI fit was rejected (fed back on retry). */
    private fun aiRejectReason(json: JSONObject): String {
        val id = json.optDouble("innerDiameterMM", Double.NaN)
        val bw = json.optDouble("bandWidthMM", Double.NaN)
        val h  = json.optDouble("heightMM", Double.NaN)
        return when {
            !id.isFinite() || !bw.isFinite() || !h.isFinite() -> "missing or non-numeric values"
            bw <= 0 || bw > 6 -> "band width %.2f mm out of 0.5–6 mm".format(bw)
            h <= 0 || h > 20 -> "height %.2f mm out of 0.5–20 mm".format(h)
            else -> "values outside the detected ring range"
        }
    }

    private suspend fun capturePreviewBase64(): String? {
        val host = activity as? MainActivity ?: return null
        val w = host.glView.width; val h = host.glView.height
        if (w <= 0 || h <= 0) return null
        var rgba: ByteArray? = null
        val latch = CountDownLatch(1)
        host.glView.queueEvent {
            try { rgba = NativeLib.nativeTakeScreenshot() } catch (_: Exception) {}
            latch.countDown()
        }
        withContext(Dispatchers.IO) { latch.await() }
        val bytes = rgba ?: return null
        if (bytes.size < w * h * 4) return null
        return withContext(Dispatchers.Default) {
            val pixels = IntArray(w * h)
            for (i in pixels.indices) {
                val b = i * 4
                pixels[i] = ((bytes[b + 3].toInt() and 255) shl 24) or
                    ((bytes[b].toInt() and 255) shl 16) or
                    ((bytes[b + 1].toInt() and 255) shl 8) or
                    (bytes[b + 2].toInt() and 255)
            }
            // Keep Gemini requests responsive and bounded for large viewports.
            val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            val maxSide = 1024
            val scaled = if (w > maxSide || h > maxSide) {
                val scale = minOf(maxSide.toFloat() / w, maxSide.toFloat() / h)
                Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
            } else bitmap
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    /**
     * Captures one calibrated close-up of the analyzed ring (camera auto-fitted,
     * dimension callouts + 10 mm scale bar). [view] selects the angle: 0 = top
     * (diameter), 1 = side (height/band). Returns null when the shot fails —
     * the caller decides whether a plain full-view screenshot is acceptable.
     */
    private suspend fun captureRingInspectionBase64(view: Int): String? {
        val host = activity as? MainActivity ?: return null
        val w = host.glView.width; val h = host.glView.height
        if (w <= 0 || h <= 0) return null
        var rgba: ByteArray? = null
        val latch = CountDownLatch(1)
        host.glView.queueEvent {
            try { rgba = NativeLib.nativeTakeRingInspectionShot(targetMeshIdx, view) } catch (_: Exception) {}
            latch.countDown()
        }
        withContext(Dispatchers.IO) { latch.await() }
        val bytes = rgba ?: return null
        if (bytes.size < w * h * 4) return null
        return withContext(Dispatchers.Default) {
            val pixels = IntArray(w * h)
            for (i in pixels.indices) {
                val b = i * 4
                pixels[i] = ((bytes[b + 3].toInt() and 255) shl 24) or
                    ((bytes[b].toInt() and 255) shl 16) or
                    ((bytes[b + 1].toInt() and 255) shl 8) or
                    (bytes[b + 2].toInt() and 255)
            }
            val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            val maxSide = 1024
            val scaled = if (w > maxSide || h > maxSide) {
                val scale = minOf(maxSide.toFloat() / w, maxSide.toFloat() / h)
                Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
            } else bitmap
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 92, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun setAiStatus(message: String, color: String) {
        activity?.runOnUiThread {
            aiStatus?.text = message
            aiStatus?.setTextColor(Color.parseColor(color))
        }
    }

    // ── Long-press selection sync ─────────────────────────────────────────────
    private val selectedMeshChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            val newIdx = i.getIntExtra("idx", -1)
            if (newIdx >= 0) {
                targetMeshIdx = newIdx
                etMeshIdx?.setText(newIdx.toString())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val ctx = requireContext()
        val filter = android.content.IntentFilter(MainActivity.ACTION_SELECTED_MESH_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(selectedMeshChangedReceiver, filter,
                android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(selectedMeshChangedReceiver, filter)
        }
        (activity as? MainActivity)?.glView?.queueEvent {
            val idx = try { NativeLib.nativeGetSelectedMesh() } catch (_: Exception) { -1 }
            if (idx >= 0) activity?.runOnUiThread {
                targetMeshIdx = idx
                etMeshIdx?.setText(idx.toString())
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(selectedMeshChangedReceiver) } catch (_: Exception) {}
    }

    companion object {
        const val TAG = "RingTool"
        fun newInstance() = RingToolFragment()
    }
}
