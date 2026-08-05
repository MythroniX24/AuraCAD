package com.modelviewer3d

import android.graphics.Color
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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

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
    private var presetScroll: LinearLayout? = null

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
        cardBW = bwCard; bwCard.visibility = View.GONE; root.addView(bwCard)

        val idCard = measureCard(
            ctx, "INNER DIAMETER", "Ring size — hole resizes, wall stays",
            "#FFC46B", 0.5f,
            onSbInit = { sbID = it }, onEtInit = { etID = it }, onInfoInit = { tvID = it },
            onChange = { onInnerChanged(it) })
        cardID = idCard; idCard.visibility = View.GONE; root.addView(idCard)

        val hCard = measureCard(
            ctx, "HEIGHT", "Stretch / squash along the ring axis",
            "#A78BFA", 0.5f,
            onSbInit = { sbH = it }, onEtInit = { etH = it }, onInfoInit = { tvH = it },
            onChange = { onHeightChanged(it) })
        cardH = hCard; cardH.visibility = View.GONE; root.addView(hCard)

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
                applyValue(cur - step, accentHex, fromUser = true)
            })
            addView(UISheetKit.stepButton(ctx, "+", accentHex) {
                val cur = currentValue(accentHex)
                applyValue(cur + step, accentHex, fromUser = true)
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

    private fun applyValue(v: Float, accentHex: String, fromUser: Boolean) {
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
