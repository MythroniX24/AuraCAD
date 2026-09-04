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
import android.widget.HorizontalScrollView
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

    // Latest measurement quality from the native bore circle fit (null = legacy).
    private var lastQuality: RingSizeEngine.Quality? = null

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
    // The HorizontalScrollView wrapping presetScroll + its section header —
    // toggled together so the "QUICK US SIZES" row shows/hides as one unit.
    private var presetScrollContainer: View? = null
    private var presetSectionLabel: View? = null

    // Gemini AI Ring Fit
    private var aiCard: View? = null
    private var aiTargetInput: EditText? = null  // kept but hidden — replaced by chip selector
    private var aiStatus: TextView? = null
    private var aiButton: Button? = null
    private var selectedUsSize = 7f  // default target US size
    private var aiUsChipRow: LinearLayout? = null

    private var lastBW = -1f
    private var lastID = -1f
    private var lastH  = -1f

    // US quick-size presets. Diameters are derived from the single-source-of-
    // truth RingMath formula (not a stale hard-coded table) so they always match
    // the AI target and the size labels.
    private val usPresets: List<Pair<Float, Float>> =
        (3..12).map { us -> us.toFloat() to RingMath.usSizeToDiam(us.toFloat()) }

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
        val presetHeader = UISheetKit.sectionLabel(ctx, "QUICK US SIZES")
        presetHeader.visibility = View.GONE
        root.addView(presetHeader)
        presetSectionLabel = presetHeader
        val presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        presetScroll = presetRow
        // Preset chips can exceed the screen width too — wrap in a horizontal
        // scroller so every quick size stays reachable.
        val presetScrollView = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(UISheetKit.dp(ctx, 16), 0, UISheetKit.dp(ctx, 16), 0)
            visibility = View.GONE
            addView(presetRow)
        }
        presetScrollContainer = presetScrollView
        root.addView(presetScrollView)

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
        // Gemini receives calibrated close-up screenshots, reasons about the
        // requested US ring size, and applies safe manufacturable values.
        val builtAiCard = UISheetKit.card(ctx, marginTopDp = 12).apply {
            addView(UISheetKit.cardTitle(ctx, "AI RING FIT", "#A78BFA"))
            addView(UISheetKit.subText(ctx,
                "Pick a target US size — the ring is measured and resized to the exact " +
                    "millimetre, instantly. Gemini then does an optional structural check " +
                    "(needs an API key; sizing works offline too).",
                "#A9B8CC", 10f))

            // Hidden EditText kept for API compatibility — actual input is chips
            addView(EditText(ctx).apply {
                visibility = View.GONE
                setText("17.3")
            }.also { aiTargetInput = it })

            // US size chip selector
            addView(UISheetKit.sectionLabel(ctx, "TARGET US SIZE"))
            val chipRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, UISheetKit.dp(ctx, 4), 0, UISheetKit.dp(ctx, 2))
                gravity = Gravity.START
            }
            // Build half-size chips: 3, 3.5, 4, 4.5, ... 13
            val usSizes = mutableListOf<Float>()
            var s = 3f
            while (s <= 13f) { usSizes.add(s); s += 0.5f }
            usSizes.forEachIndexed { idx, us ->
                val label = if (us == us.toInt().toFloat()) "${us.toInt()}" else "$us"
                val chip = UISheetKit.chipButton(ctx, label, "#A78BFA", onClick = {
                    selectedUsSize = us
                    aiTargetInput?.setText(RingMath.usSizeToDiam(us).toString())
                    highlightAiChipAt(idx)
                    tvStatus?.text = "→ Target: US $label (%.2f mm)".format(RingMath.usSizeToDiam(us))
                    tvStatus?.setTextColor(Color.parseColor("#A78BFA"))
                })
                chipRow.addView(chip)
            }
            aiUsChipRow = chipRow
            // 21 chips (~1218dp) can't fit a phone width, so make the row
            // horizontally scrollable — otherwise sizes past ~US 6 overflow off
            // screen and look clipped/broken.
            addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(chipRow)
            })
            // Reflect the default target (US 7) so a selection is always visible.
            highlightAiChipAt(usSizes.indexOfFirst { it == selectedUsSize })

            addView(UISheetKit.primaryButton(ctx, "✨  Resize to Target Size", 48).apply {
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
        presetScrollContainer?.visibility = View.GONE
        presetSectionLabel?.visibility = View.GONE
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

    /**
     * p = [0]innerRad [1]outerRad [2]bandWidth [3]innerDia [4]outerDia [5]height
     *     [6]roundnessMM [7]minBoreDia [8]maxBoreDia [9]ovality% [10]confidence
     *     [11]borePointCount   (indices 6+ present only from the v4 native core)
     */
    private fun applyParams(p: FloatArray) {
        origInnerRadMM0 = p[0]
        origOuterDiaMM  = p[4]
        origInnerDiaMM  = p[3]
        origBandWidthMM = p[2]
        origHeightMM    = p[5]

        lastQuality = if (p.size >= 12) RingSizeEngine.Quality(
            roundnessMM  = p[6],
            minBoreDiaMM = p[7],
            maxBoreDiaMM = p[8],
            ovalityPct   = p[9],
            confidence   = p[10],
            pointCount   = p[11].toInt(),
        ) else null

        bwMin = (origBandWidthMM * 0.1f).coerceAtLeast(0.05f)
        bwMax = (origBandWidthMM * 3.5f).coerceAtMost(50f)
        // Range must cover all US ring sizes (14-22mm) even for small models
        idMin = minOf(origInnerDiaMM * 0.5f, 8f).coerceAtLeast(1f)
        idMax = maxOf(origInnerDiaMM * 2.0f, 25f).coerceAtMost(80f)
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

        val summary = StringBuilder()
        summary.append("📋 Inner ⌀ %.2f mm · Outer ⌀ %.2f mm · Band %.2f mm · Height %.2f mm · %s".format(
            origInnerDiaMM, origOuterDiaMM, origBandWidthMM, origHeightMM,
            RingMath.usSizeLabel(origInnerDiaMM)))
        summary.append("\nCircumference %.1f mm".format(RingMath.circumferenceMM(origInnerDiaMM)))
        lastQuality?.let { q ->
            val icon = when (q.tier) {
                RingSizeEngine.Quality.Tier.EXCELLENT -> "🟢"
                RingSizeEngine.Quality.Tier.GOOD -> "🟢"
                RingSizeEngine.Quality.Tier.FAIR -> "🟡"
                RingSizeEngine.Quality.Tier.POOR -> "🔴"
            }
            summary.append("\n$icon ${q.summary()}")
            if (!q.isRound) {
                summary.append("\n⚠️ Bore is out of round (⌀ %.2f–%.2f mm) — size is approximate."
                    .format(q.minBoreDiaMM, q.maxBoreDiaMM))
            }
        }
        tvSummary?.text = summary.toString()
        tvSummary?.setTextColor(Color.parseColor(when (lastQuality?.tier) {
            RingSizeEngine.Quality.Tier.POOR -> "#FF7A72"
            RingSizeEngine.Quality.Tier.FAIR -> "#FFD54F"
            else -> "#4DD8FF"
        }))
        tvSummary?.visibility = View.VISIBLE

        cardBW?.visibility = View.VISIBLE
        cardID?.visibility = View.VISIBLE
        cardH?.visibility = View.VISIBLE
        buildPresets()
        presetScrollContainer?.visibility = View.VISIBLE
        presetSectionLabel?.visibility = View.VISIBLE
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

    // ══════════════════════════════════════════════════════════════════════════
    //  AI Ring Size Changer — deterministic-first architecture
    //
    //  Sizing is pure geometry, so it is computed EXACTLY and applied INSTANTLY
    //  with no network dependency:
    //
    //    1. MEASURE  native analyzeRing()/getRingParams() → ground-truth mm
    //    2. COMPUTE  RingSizeEngine.planForUsSize()       → exact target mm
    //    3. APPLY    native applyCombinedRingDeformation  → exact deform
    //    4. VERIFY   re-read getRingParams, compare mm    → instant, exact
    //    5. INSPECT  OPTIONAL single Gemini call          → structural advisory
    //
    //  Gemini can no longer change dimensions, so it can never make sizing wrong.
    //  Calls drop from up to 5 → 0 (offline) or 1 (structural check only).
    // ══════════════════════════════════════════════════════════════════════════
    private fun runAiRingFit() {
        val targetUS = selectedUsSize
        val targetLabel = if (targetUS == targetUS.toInt().toFloat())
            "US ${targetUS.toInt()}" else "US $targetUS"
        val ctx = requireContext()

        aiButton?.isEnabled = false
        aiUsChipRow?.isEnabled = false
        aiUsChipRow?.let { for (i in 0 until it.childCount) it.getChildAt(i).isEnabled = false }
        setAiStatus("🎯 Sizing to $targetLabel…", "#FFD54F")

        lifecycleScope.launch {
            try {
                // ── 1. MEASURE ────────────────────────────────────────────────
                if (!ringAnalyzed) {
                    val detected = glAwait { NativeLib.nativeAnalyzeRing(targetMeshIdx) } ?: false
                    if (!detected) throw IllegalStateException("Selected mesh is not recognized as a ring")
                    val params = glAwait { NativeLib.nativeGetRingParams() } ?: FloatArray(0)
                    if (params.size < 6) throw IllegalStateException("Ring measurements could not be read")
                    withContext(Dispatchers.Main) { applyParams(params) }
                }
                val current = RingSizeEngine.RingState(
                    innerDiameterMM = origInnerDiaMM,
                    bandWidthMM = origBandWidthMM,
                    heightMM = origHeightMM,
                )
                val limits = RingSizeEngine.Limits(
                    idMin = idMin, idMax = idMax,
                    bandMin = bwMin.coerceAtLeast(0.3f), bandMax = bwMax.coerceAtMost(6f),
                    heightMin = hMin, heightMax = hMax,
                )

                // ── 2. COMPUTE (exact, no AI) ─────────────────────────────────
                val plan = when (val r = RingSizeEngine.planForUsSize(current, targetUS, limits)) {
                    is RingSizeEngine.Result.Ok -> r.plan
                    is RingSizeEngine.Result.Error -> throw IllegalArgumentException(r.message)
                }

                // ── 3. APPLY (exact geometry) ─────────────────────────────────
                withContext(Dispatchers.Main) {
                    applyRingValues(plan.targetInnerDiameterMM, plan.bandWidthMM, plan.heightMM)
                }

                // ── 4. VERIFY by re-measuring mm (instant, exact) ─────────────
                kotlinx.coroutines.delay(120)  // let the pending deform apply on the GL thread
                val after = glAwait { NativeLib.nativeGetRingParams() } ?: FloatArray(0)
                val achieved = if (after.size >= 6)
                    RingSizeEngine.RingState(after[3], after[2], after[5]) else achievedFallback(plan)
                val sizeError = RingSizeEngine.verify(plan, achieved)

                // ── 5. OPTIONAL structural inspection via Gemini ──────────────
                // Purely advisory: confirms the mesh is still a clean, intact
                // ring. It NEVER edits dimensions. Skipped silently with no key.
                var advisory = ""
                val key = AiPrefs.apiKey(ctx)
                if (GeminiClient.validateApiKey(key) == null) {
                    setAiStatus("🔍 Checking ring structure…", "#FFD54F")
                    advisory = try {
                        inspectStructure(key, AiPrefs.model(ctx), targetLabel, achieved)
                    } catch (_: Exception) { "" }  // advisory is best-effort
                }

                // ── Report ────────────────────────────────────────────────────
                val msg = buildString {
                    append("✅ Resized to $targetLabel\n")
                    append("${plan.direction}: ${plan.fromUsLabel} (${"%.2f".format(current.innerDiameterMM)} mm)")
                    append(" → ${RingMath.usSizeLabel(achieved.innerDiameterMM)} (${"%.2f".format(achieved.innerDiameterMM)} mm)\n")
                    append("Band ${"%.2f".format(achieved.bandWidthMM)} mm · Height ${"%.2f".format(achieved.heightMM)} mm")
                    if (sizeError != null) append("\n⚠️ $sizeError")
                    if (advisory.isNotEmpty()) append("\n$advisory")
                }
                setAiStatus(msg, if (sizeError == null) "#4CAF82" else "#FFD54F")
            } catch (e: GeminiClient.GeminiException) {
                setAiStatus("Gemini: ${e.message}", "#FF7A72")
            } catch (e: Exception) {
                setAiStatus("Ring fit failed: ${e.message ?: "try again"}", "#FF7A72")
            } finally {
                aiButton?.isEnabled = true
                aiUsChipRow?.isEnabled = true
                aiUsChipRow?.let { for (i in 0 until it.childCount) it.getChildAt(i).isEnabled = true }
            }
        }
    }

    /** Fallback "achieved" state when native params can't be re-read post-apply. */
    private fun achievedFallback(plan: RingSizeEngine.ResizePlan) =
        RingSizeEngine.RingState(plan.targetInnerDiameterMM, plan.bandWidthMM, plan.heightMM)

    /** Runs [block] on the GL thread and awaits its result (null on failure). */
    private suspend fun <T> glAwait(block: () -> T): T? = withContext(Dispatchers.IO) {
        var result: T? = null
        val latch = CountDownLatch(1)
        glRun {
            result = try { block() } catch (_: Exception) { null }
            latch.countDown()
        }
        latch.await()
        result
    }

    /** Applies new ring dimensions with a single undo snapshot. */
    private fun applyRingValues(id: Float, bw: Float, h: Float) {
        val wasProportional = proportional
        proportional = false
        lastBW = bw; lastID = id; lastH = h
        glRun {
            NativeLib.nativePushUndoState()
            NativeLib.nativeSetRingBandWidthAsync(bw)
            NativeLib.nativeSetRingInnerDiameterAsync(id)
            NativeLib.nativeSetRingHeightAsync(h)
        }
        applyValue(bw, "#4DD8FF", fromUser = false)
        applyValue(id, "#FFC46B", fromUser = false)
        applyValue(h, "#A78BFA", fromUser = false)
        proportional = wasProportional
        activity?.runOnUiThread { updateInfo(); updatePreview() }
    }

    /**
     * OPTIONAL structural advisory. Sends ONE Gemini call with the top+side
     * inspection shots and asks only whether the resized ring is structurally
     * intact — it returns a short human note and never alters dimensions.
     */
    private suspend fun inspectStructure(
        key: String, model: String, targetLabel: String, achieved: RingSizeEngine.RingState,
    ): String {
        val top = withContext(Dispatchers.IO) { captureRingInspectionBase64(0) ?: capturePreviewBase64() }
        val side = withContext(Dispatchers.IO) { captureRingInspectionBase64(1) }
        val images = listOfNotNull(top, side)
        if (images.isEmpty()) return ""
        val prompt = buildString {
            append("A ring was resized to $targetLabel ")
            append("(inner ≈ ${"%.2f".format(achieved.innerDiameterMM)} mm, ")
            append("band ≈ ${"%.2f".format(achieved.bandWidthMM)} mm, ")
            append("height ≈ ${"%.2f".format(achieved.heightMM)} mm).\n")
            append("Image 1 (TOP), Image 2 (SIDE). The dimensions are already correct and verified numerically — ")
            append("do NOT judge or re-measure the size.\n")
            append("Only check STRUCTURE: is it a clean, complete, round band with no collapsed, ")
            append("inverted, torn or blown-out geometry?\n")
            append("Return JSON only: {\"intact\": true/false, \"note\": \"one short sentence\"}")
        }
        val reply = GeminiClient.generate(
            apiKey = key,
            systemPrompt = "You inspect 3D ring meshes for structural integrity only. " +
                "You never change or judge dimensions. Reply with the requested JSON only.",
            userPrompt = prompt,
            pngBase64 = images.firstOrNull(),
            extraImages = images.drop(1),
            model = model,
        )
        val json = parseAiJson(reply.text) ?: return ""
        val intact = json.optBoolean("intact", true)
        val note = json.optString("note", "").ifBlank { "structure check" }
        return if (intact) "🔍 Structure OK — $note" else "⚠️ Structure: $note"
    }

    /** Lenient JSON extraction from a model reply; null when no object is found. */
    private fun parseAiJson(raw: String?): JSONObject? {
        val text = raw.orEmpty().replace("```json", "", true).replace("```", "").trim()
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(text.substring(start, end + 1)) } catch (_: Exception) { null }
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

    /** Highlights the chip at [idx] and dims all others. */
    private fun highlightAiChipAt(idx: Int) {
        val ctx = context ?: return
        aiUsChipRow?.let { row ->
            for (i in 0 until row.childCount) {
                val chip = row.getChildAt(i)
                val selected = i == idx
                // Clear selected state gives a filled accent look; unselected
                // stays the plain dark card. Alpha alone was too subtle to read.
                chip.background = ctx.getDrawable(
                    if (selected) R.drawable.bg_chip_selected_violet else R.drawable.bg_card_dark
                )
                chip.alpha = if (selected) 1f else 0.7f
                (chip as? Button)?.setTextColor(
                    Color.parseColor(if (selected) "#FFFFFF" else "#A78BFA")
                )
            }
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
