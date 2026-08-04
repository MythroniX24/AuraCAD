package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Ring Deformation Tool  v4 — Ultra Edition
 *
 * Mathematically correct resize — no texture distortion.
 *
 * BAND WIDTH:
 *   r_new = origInner + (r_orig − origInner) × (newBand / origBand)
 *   → linear wall scaling, inner bore fixed, texture fraction preserved
 *
 * INNER DIAMETER:
 *   r_new = r_orig + (newInner − origInner)
 *   → uniform radial shift, wall thickness preserved, zero texture distortion
 *
 * HEIGHT (axial):
 *   h_new = h_orig × (newHeight / origHeight)
 *   → stretch/squash along the ring hole axis, radial map untouched
 *
 * All three are applied together from origVerts → zero cumulative error.
 */
class RingToolFragment : BottomSheetDialogFragment() {

    // Ring params from analysis (all in mm, normalized, from native)
    private var origInnerRadMM  = 0f
    private var origBandWidthMM = 0f
    private var origInnerDiaMM  = 0f
    private var origHeightMM    = 0f

    // Dynamic slider ranges (set after analysis)
    private var bwMin = 0.1f;  private var bwMax = 20f   // band width mm
    private var idMin = 1.0f;  private var idMax = 50f   // inner diameter mm
    private var hMin  = 0.5f;  private var hMax  = 50f   // height mm
    private val STEPS = 3000

    private var targetMeshIdx = 0
    private var ringAnalyzed  = false

    // ── Long-press selection sync ─────────────────────────────────────────────
    private var etMeshIdx: android.widget.EditText? = null
    private val selectedMeshChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            val newIdx = i.getIntExtra("idx", -1)
            if (newIdx >= 0) {
                targetMeshIdx = newIdx
                etMeshIdx?.setText(newIdx.toString())
            }
        }
    }

    // UI refs
    private var tvStatus:    TextView?  = null
    private var tvInfo:      TextView?  = null
    private var tvBwCurrent: TextView?  = null
    private var tvIdCurrent: TextView?  = null
    private var tvHCurrent:  TextView?  = null
    private var tvSummary:   TextView?  = null

    private var sbBandWidth: SeekBar?   = null
    private var etBandWidth: EditText?  = null
    private var sbInnerDia:  SeekBar?   = null
    private var etInnerDia:  EditText?  = null
    private var sbHeight:    SeekBar?   = null
    private var etHeight:    EditText?  = null

    private var cardBW: View? = null
    private var cardID: View? = null
    private var cardH:  View? = null
    private var presetRow: LinearLayout? = null

    @Volatile private var suppressBW = false
    @Volatile private var suppressID = false
    @Volatile private var suppressH  = false
    private var proportional = false

    // Debounce: don't spam GL thread on every pixel of slider drag
    private var lastBWMM = -1f
    private var lastIDMM = -1f
    private var lastHMM  = -1f

    /** Common US ring sizes (diameter mm) for the quick-preset row. */
    private val usPresets = listOf(
        3f to 14.1f, 4f to 14.9f, 5f to 15.7f, 6f to 16.5f, 7f to 17.3f,
        8f to 18.1f, 9f to 18.9f, 10f to 19.8f, 11f to 20.6f, 12f to 21.4f
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 80)
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

        // Title row
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 14, 20, 6)
            addView(TextView(ctx).apply {
                text = "💍  Ring Tool"; textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = "v4"; textSize = 9f; letterSpacing = 0.12f
                setTextColor(Color.parseColor("#00D4FF"))
                background = ctx.getDrawable(R.drawable.bg_pill); setPadding(10, 3, 10, 3)
            })
        })
        root.addView(divider(ctx))

        // ── Detection panel ───────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "RING DETECTION"))
        root.addView(infoCard(ctx,
            "Open a ring STL/OBJ → tap Detect → adjust Band Width (wall), " +
            "Inner Diameter (ring size) or Height. Texture is fully preserved."))

        // Mesh index row
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 10, 20, 0)
            addView(TextView(ctx).apply {
                text = "Mesh index:"; textSize = 11f
                setTextColor(Color.parseColor("#9090B0")); setPadding(0,0,12,0)
            })
            val etIdx = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(targetMeshIdx.toString())
                setTextColor(Color.WHITE); textSize = 13f
                background = ctx.getDrawable(R.drawable.bg_input_field); setPadding(10,8,10,8)
                layoutParams = LinearLayout.LayoutParams(80, LinearLayout.LayoutParams.WRAP_CONTENT)
                addTextChangedListener(simpleWatcher { targetMeshIdx = text.toString().toIntOrNull() ?: 0 })
            }
            etMeshIdx = etIdx
            addView(etIdx)
            addView(TextView(ctx).apply {
                text = "  (0 = whole model)"; textSize = 9f
                setTextColor(Color.parseColor("#404060"))
            })
        })

        // Detect button
        val btnDetect = Button(ctx).apply {
            text = "▶  Detect Ring Geometry"
            textSize = 12f; setTextColor(Color.parseColor("#050508"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = ctx.getDrawable(R.drawable.bg_btn_accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 52
            ).apply { setMargins(20,12,20,0) }
        }
        root.addView(btnDetect)

        // Status
        tvStatus = TextView(ctx).apply {
            text = "⚠  No ring detected yet"; textSize = 10f
            setTextColor(Color.parseColor("#FF7043")); setPadding(20,10,20,2)
        }
        root.addView(tvStatus)
        tvInfo = TextView(ctx).apply {
            text = ""; textSize = 10f
            setTextColor(Color.parseColor("#606080")); setPadding(20,0,20,6)
        }
        root.addView(tvInfo)
        root.addView(divider(ctx))

        // ── Live summary card (shown after detection) ─────────────────────────
        tvSummary = TextView(ctx).apply {
            text = ""; textSize = 10f
            setTextColor(Color.parseColor("#00D4FF"))
            background = ctx.getDrawable(R.drawable.bg_hint_card)
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 4, 14, 0) }
        }
        root.addView(tvSummary)

        // ── Proportional toggle ───────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 8, 20, 0)
            addView(TextView(ctx).apply {
                text = "🔗 Proportional resize (scale all)"; textSize = 11f
                setTextColor(Color.parseColor("#9090B0"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Switch(ctx).apply {
                isChecked = false
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A3D50"))
                setOnCheckedChangeListener { _, v -> proportional = v }
            })
        })

        // ── Band Width card ───────────────────────────────────────────────────
        val bwCard = buildSliderCard(ctx, root,
            header    = "BAND WIDTH  (Wall Thickness)",
            desc      = "Outer wall expands/contracts · Inner bore stays fixed",
            unit      = "mm",
            accentHex = "#00D4FF",
            step      = 0.25f,
            onSbInit  = { sb -> sbBandWidth = sb },
            onEtInit  = { et -> etBandWidth = et },
            onInfoInit= { tv -> tvBwCurrent = tv },
            onChange  = { v -> onBandWidthChanged(v) }
        )
        cardBW = bwCard; bwCard.visibility = View.GONE; root.addView(bwCard)
        root.addView(spacer(ctx, 6))

        // ── Inner Diameter card ───────────────────────────────────────────────
        val idCard = buildSliderCard(ctx, root,
            header    = "INNER DIAMETER  (Ring Size)",
            desc      = "Hole expands/contracts · Wall thickness stays the same",
            unit      = "mm",
            accentHex = "#FF9800",
            step      = 0.5f,
            onSbInit  = { sb -> sbInnerDia = sb },
            onEtInit  = { et -> etInnerDia = et },
            onInfoInit= { tv -> tvIdCurrent = tv },
            onChange  = { v -> onInnerDiaChanged(v) }
        )
        cardID = idCard; idCard.visibility = View.GONE; root.addView(idCard)

        // ── US size quick presets (only meaningful after ID known) ────────────
        presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(20, 10, 20, 0)
        }
        presetRow?.visibility = View.GONE
        root.addView(presetRow)
        root.addView(spacer(ctx, 6))

        // ── Height card ───────────────────────────────────────────────────────
        val hCard = buildSliderCard(ctx, root,
            header    = "HEIGHT  (Axial Extent)",
            desc      = "Stretches/squashes along the ring hole axis",
            unit      = "mm",
            accentHex = "#7C4DFF",
            step      = 0.5f,
            onSbInit  = { sb -> sbHeight = sb },
            onEtInit  = { et -> etHeight = et },
            onInfoInit= { tv -> tvHCurrent = tv },
            onChange  = { v -> onHeightChanged(v) }
        )
        cardH = hCard; hCard.visibility = View.GONE; root.addView(hCard)
        root.addView(divider(ctx))

        // ── Action row ────────────────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(20,12,20,0)
            addView(Button(ctx).apply {
                text = "↺ Reset"; textSize = 11f
                setTextColor(Color.parseColor("#FF7043"))
                background = ctx.getDrawable(R.drawable.bg_btn_danger); setPadding(20,0,20,0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, 44).apply { setMargins(0,0,12,0) }
                setOnClickListener {
                    glRun {
                        NativeLib.nativeResetRingDeformation()
                        val p = NativeLib.nativeGetRingParams()
                        activity?.runOnUiThread { if (p.size >= 6) applyRingParams(p, resetSliders = true) }
                    }
                }
            })
            addView(Button(ctx).apply {
                text = "Re-Detect"; textSize = 11f
                setTextColor(Color.parseColor("#9090B0"))
                background = ctx.getDrawable(R.drawable.bg_card_dark); setPadding(20,0,20,0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44)
                setOnClickListener { btnDetect.performClick() }
            })
        })

        // ── Wire detect button ────────────────────────────────────────────────
        btnDetect.setOnClickListener {
            ringAnalyzed = false
            cardBW?.visibility = View.GONE
            cardID?.visibility = View.GONE
            cardH?.visibility = View.GONE
            presetRow?.visibility = View.GONE
            tvStatus?.text = "⏳  Detecting ring…"; tvStatus?.setTextColor(Color.parseColor("#FFD54F"))
            tvInfo?.text = ""
            tvSummary?.text = ""

            glRun {
                val ok = NativeLib.nativeAnalyzeRing(targetMeshIdx)
                if (ok) {
                    val p = NativeLib.nativeGetRingParams()
                    activity?.runOnUiThread {
                        if (p.size >= 6) applyRingParams(p, resetSliders = true)
                        else { tvStatus?.text = "✗ Param read failed"; tvStatus?.setTextColor(Color.parseColor("#FF5252")) }
                    }
                } else {
                    activity?.runOnUiThread {
                        tvStatus?.text = "✗ Mesh #$targetMeshIdx is not a ring shape"
                        tvStatus?.setTextColor(Color.parseColor("#FF5252"))
                    }
                }
            }
        }

        return scroll
    }

    // ── Slider change handlers (shared by slider + EditText + presets) ────────
    private fun onBandWidthChanged(v: Float) {
        if (!ringAnalyzed || v == lastBWMM) return
        lastBWMM = v
        if (proportional) {
            val ratio = v / origBandWidthMM
            if (ratio > 0.01f) {
                lastIDMM = origInnerDiaMM * ratio
                lastHMM  = origHeightMM * ratio
                setSliderTo(sbInnerDia, etInnerDia, lastIDMM, idMin, idMax, "ID", fromUser = true)
                setSliderTo(sbHeight, etHeight, lastHMM, hMin, hMax, "H", fromUser = true)
                glRun {
                    NativeLib.nativeSetRingBandWidthAsync(v)
                    NativeLib.nativeSetRingInnerDiameterAsync(lastIDMM)
                    NativeLib.nativeSetRingHeightAsync(lastHMM)
                }
            }
        } else {
            glRun { NativeLib.nativeSetRingBandWidthAsync(v) }
        }
        activity?.runOnUiThread {
            updateBwInfo(v); updateSummary()
            activity?.sendBroadcast(
                android.content.Intent(EditorPanelFragment.ACTION_DIMS_CHANGED))
        }
    }

    private fun onInnerDiaChanged(v: Float) {
        if (!ringAnalyzed || v == lastIDMM) return
        lastIDMM = v
        if (proportional) {
            val ratio = v / origInnerDiaMM
            if (ratio > 0.01f) {
                lastBWMM = origBandWidthMM * ratio
                lastHMM  = origHeightMM * ratio
                setSliderTo(sbBandWidth, etBandWidth, lastBWMM, bwMin, bwMax, "BW", fromUser = true)
                setSliderTo(sbHeight, etHeight, lastHMM, hMin, hMax, "H", fromUser = true)
                glRun {
                    NativeLib.nativeSetRingInnerDiameterAsync(v)
                    NativeLib.nativeSetRingBandWidthAsync(lastBWMM)
                    NativeLib.nativeSetRingHeightAsync(lastHMM)
                }
            }
        } else {
            glRun { NativeLib.nativeSetRingInnerDiameterAsync(v) }
        }
        activity?.runOnUiThread {
            updateIdInfo(v); updateSummary()
            activity?.sendBroadcast(
                android.content.Intent(EditorPanelFragment.ACTION_DIMS_CHANGED))
        }
    }

    private fun onHeightChanged(v: Float) {
        if (!ringAnalyzed || v == lastHMM) return
        lastHMM = v
        if (proportional) {
            val ratio = v / origHeightMM
            if (ratio > 0.01f) {
                lastBWMM = origBandWidthMM * ratio
                lastIDMM = origInnerDiaMM * ratio
                setSliderTo(sbBandWidth, etBandWidth, lastBWMM, bwMin, bwMax, "BW", fromUser = true)
                setSliderTo(sbInnerDia, etInnerDia, lastIDMM, idMin, idMax, "ID", fromUser = true)
                glRun {
                    NativeLib.nativeSetRingHeightAsync(v)
                    NativeLib.nativeSetRingBandWidthAsync(lastBWMM)
                    NativeLib.nativeSetRingInnerDiameterAsync(lastIDMM)
                }
            }
        } else {
            glRun { NativeLib.nativeSetRingHeightAsync(v) }
        }
        activity?.runOnUiThread {
            updateHInfo(v); updateSummary()
            activity?.sendBroadcast(
                android.content.Intent(EditorPanelFragment.ACTION_DIMS_CHANGED))
        }
    }

    // ── Apply detected ring parameters to all UI ──────────────────────────────
    private fun applyRingParams(p: FloatArray, resetSliders: Boolean) {
        // p: [innerRadMM, outerRadMM, bandWidthMM, innerDiaMM, outerDiaMM, heightMM]
        origInnerRadMM  = p[0]
        origBandWidthMM = p[2]
        origInnerDiaMM  = p[3]
        origHeightMM    = p[5]

        // Dynamic ranges: 10%–350% of original band, 50%–200% of original inner dia
        bwMin = (origBandWidthMM * 0.1f).coerceAtLeast(0.05f)
        bwMax = (origBandWidthMM * 3.5f).coerceAtMost(50f)
        idMin = (origInnerDiaMM  * 0.5f).coerceAtLeast(0.5f)
        idMax = (origInnerDiaMM  * 2.0f).coerceAtMost(80f)
        hMin  = (origHeightMM    * 0.3f).coerceAtLeast(0.5f)
        hMax  = (origHeightMM    * 3.0f).coerceAtMost(80f)

        lastBWMM = origBandWidthMM
        lastIDMM = origInnerDiaMM
        lastHMM  = origHeightMM
        ringAnalyzed = true

        tvStatus?.text = "✓ Ring detected"
        tvStatus?.setTextColor(Color.parseColor("#4CAF82"))
        tvInfo?.text = "Inner ⌀ %.2f mm  •  Outer ⌀ %.2f mm  •  Band %.2f mm  •  H %.2f mm  •  %s"
            .format(origInnerDiaMM, p[4], origBandWidthMM, origHeightMM,
                    RingMath.usSizeLabel(origInnerDiaMM))

        if (resetSliders) {
            setSliderTo(sbBandWidth, etBandWidth, origBandWidthMM, bwMin, bwMax, "BW", fromUser = false)
            setSliderTo(sbInnerDia,  etInnerDia,  origInnerDiaMM,  idMin, idMax, "ID", fromUser = false)
            setSliderTo(sbHeight,    etHeight,    origHeightMM,    hMin,  hMax,  "H",  fromUser = false)
        }
        updateBwInfo(origBandWidthMM)
        updateIdInfo(origInnerDiaMM)
        updateHInfo(origHeightMM)
        updateSummary()

        cardBW?.visibility = View.VISIBLE
        cardID?.visibility = View.VISIBLE
        cardH?.visibility = View.VISIBLE
        buildPresetRow()
        presetRow?.visibility = View.VISIBLE
    }

    private fun buildPresetRow() {
        val ctx = requireContext()
        presetRow?.removeAllViews()
        presetRow?.addView(TextView(ctx).apply {
            text = "US size: "; textSize = 10f
            setTextColor(Color.parseColor("#9090B0"))
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 6, 0)
        })
        usPresets.forEach { (us, diaMM) ->
            presetRow?.addView(Button(ctx).apply {
                text = "$us"; textSize = 10f
                setTextColor(Color.parseColor("#00D4FF"))
                background = ctx.getDrawable(R.drawable.bg_card_dark)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(52, 36).apply { setMargins(0, 0, 6, 0) }
                setOnClickListener { setInnerDiaPreset(diaMM, us) }
            })
        }
    }

    /** Set inner diameter from a US-size preset (and sync slider + edit text). */
    private fun setInnerDiaPreset(diaMM: Float, usSize: Float) {
        if (!ringAnalyzed) return
        val clamped = diaMM.coerceIn(idMin, idMax)
        lastIDMM = clamped
        setSliderTo(sbInnerDia, etInnerDia, clamped, idMin, idMax, "ID", fromUser = true)
        glRun { NativeLib.nativeSetRingInnerDiameterAsync(clamped) }
        activity?.runOnUiThread {
            updateIdInfo(clamped); updateSummary()
            tvInfo?.text = "→ %s selected".format(RingMath.usSizeLabel(clamped))
            activity?.sendBroadcast(
                android.content.Intent(EditorPanelFragment.ACTION_DIMS_CHANGED))
        }
    }

    private fun updateBwInfo(bwMM: Float) {
        val newOuterDia = RingMath.outerDia(lastIDMM.takeIf { it > 0f } ?: origInnerDiaMM, bwMM)
        tvBwCurrent?.text = "Band: %.2f mm  →  Outer ⌀ %.2f mm".format(bwMM, newOuterDia)
    }

    private fun updateIdInfo(idMM: Float) {
        tvIdCurrent?.text = "Inner ⌀: %.2f mm  →  %s".format(idMM, RingMath.usSizeLabel(idMM))
    }

    private fun updateHInfo(hMM: Float) {
        tvHCurrent?.text = "Height: %.2f mm  (%s of original)"
            .format(hMM, if (origHeightMM > 0f) "%.0f%%".format(hMM / origHeightMM * 100f) else "—")
    }

    private fun updateSummary() {
        val bw = lastBWMM; val id = lastIDMM; val h = lastHMM
        if (bw <= 0f || id <= 0f || h <= 0f) return
        val outer = RingMath.outerDia(id, bw)
        tvSummary?.text = "📋 Inner ⌀ %.2f mm · Outer ⌀ %.2f mm · Band %.2f mm · H %.2f mm · %s"
            .format(id, outer, bw, h, RingMath.usSizeLabel(id))
    }

    // ── Set slider + EditText to a specific mm value ──────────────────────────
    private fun setSliderTo(sb: SeekBar?, et: EditText?, value: Float,
                            min: Float, max: Float, token: String, fromUser: Boolean) {
        val prog = valueToProgress(value, min, max)
        val txt  = "%.2f".format(value)
        when (token) {
            "BW" -> { suppressBW = true; sb?.progress = prog; et?.setText(txt); suppressBW = false }
            "ID" -> { suppressID = true; sb?.progress = prog; et?.setText(txt); suppressID = false }
            "H"  -> { suppressH  = true; sb?.progress = prog; et?.setText(txt); suppressH  = false }
        }
        // Re-fire the shared handler so sliders/summaries stay consistent
        if (fromUser) {
            when (token) {
                "BW" -> onBandWidthChanged(value)
                "ID" -> onInnerDiaChanged(value)
                "H"  -> onHeightChanged(value)
            }
        }
    }

    private fun valueToProgress(v: Float, min: Float, max: Float) =
        ((v - min) / (max - min) * STEPS).toInt().coerceIn(0, STEPS)
    private fun progressToValue(p: Int, min: Float, max: Float) =
        min + p.toFloat() / STEPS * (max - min)

    // ── Build a slider control card with quick ± step buttons ─────────────────
    private fun buildSliderCard(
        ctx: android.content.Context,
        @Suppress("UNUSED_PARAMETER") root: LinearLayout,
        header: String, desc: String, unit: String, accentHex: String,
        step: Float,
        onSbInit:   (SeekBar) -> Unit,
        onEtInit:   (EditText) -> Unit,
        onInfoInit: (TextView) -> Unit,
        onChange:   (Float) -> Unit
    ): LinearLayout {
        val accent = Color.parseColor(accentHex)
        val isOuter = accentHex == "#00D4FF"
        val isH     = accentHex == "#7C4DFF"

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_dark)
            setPadding(0, 0, 0, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 4, 14, 0) }
        }

        card.addView(TextView(ctx).apply {
            text = header; textSize = 9f; letterSpacing = 0.14f
            setTextColor(Color.parseColor(accentHex)); setPadding(16, 14, 16, 0)
        })
        card.addView(TextView(ctx).apply {
            text = desc; textSize = 9f; setTextColor(Color.parseColor("#505070"))
            setPadding(16, 3, 16, 8)
        })

        // SeekBar (declared first so step buttons can reference it)
        val sb = SeekBar(ctx).apply {
            max = STEPS; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList    = android.content.res.ColorStateList.valueOf(accent)
            setPadding(16, 8, 16, 0)
        }
        onSbInit(sb)

        // EditText + unit in a row
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(16, 0, 16, 0)
        }
        val et = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("0.00"); setTextColor(Color.WHITE); textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = ctx.getDrawable(R.drawable.bg_input_field); setPadding(14, 10, 14, 10)
            layoutParams = LinearLayout.LayoutParams(150, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        onEtInit(et); inputRow.addView(et)
        inputRow.addView(TextView(ctx).apply {
            text = " $unit"; textSize = 12f; setTextColor(Color.parseColor("#505070"))
        })
        // Shared apply helper: updates EditText + SeekBar + fires onChange.
        fun applyValue(v: Float) {
            val txt = "%.2f".format(v)
            when {
                isOuter -> { suppressBW = true; et.setText(txt); sb.progress = valueToProgress(v, bwMin, bwMax); suppressBW = false }
                isH     -> { suppressH  = true; et.setText(txt); sb.progress = valueToProgress(v, hMin,  hMax);  suppressH  = false }
                else    -> { suppressID = true; et.setText(txt); sb.progress = valueToProgress(v, idMin, idMax); suppressID = false }
            }
            onChange(v)
        }
        // Quick − / + step buttons
        inputRow.addView(makeStepBtn(ctx, "−", accent) {
            val cur = et.text.toString().toFloatOrNull() ?: return@makeStepBtn
            applyValue((cur - step).coerceAtLeast(0.05f))
        })
        inputRow.addView(makeStepBtn(ctx, "+", accent) {
            val cur = et.text.toString().toFloatOrNull() ?: return@makeStepBtn
            applyValue(cur + step)
        })
        card.addView(inputRow)
        card.addView(sb)

        // Live info label
        val tvInfo = TextView(ctx).apply {
            text = ""; textSize = 10f
            setTextColor(Color.parseColor("#606080")); setPadding(16, 6, 16, 0)
        }
        onInfoInit(tvInfo); card.addView(tvInfo)

        // ── Wire SeekBar ──────────────────────────────────────────────────────
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
            override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || !ringAnalyzed) return
                val min = when { isOuter -> bwMin; isH -> hMin; else -> idMin }
                val max = when { isOuter -> bwMax; isH -> hMax; else -> idMax }
                val v = progressToValue(p, min, max)
                val txt = "%.2f".format(v)
                when {
                    isOuter -> { if (!suppressBW) { suppressBW=true; et.setText(txt); suppressBW=false } }
                    isH     -> { if (!suppressH)  { suppressH =true; et.setText(txt); suppressH =false } }
                    else    -> { if (!suppressID) { suppressID=true; et.setText(txt); suppressID=false } }
                }
                onChange(v)
            }
        })

        // ── Wire EditText ─────────────────────────────────────────────────────
        et.addTextChangedListener(simpleWatcher {
            if (!ringAnalyzed) return@simpleWatcher
            if (isOuter && suppressBW) return@simpleWatcher
            if (isH     && suppressH)  return@simpleWatcher
            if (!isOuter && !isH && suppressID) return@simpleWatcher
            val v = et.text.toString().toFloatOrNull() ?: return@simpleWatcher
            val min = when { isOuter -> bwMin; isH -> hMin; else -> idMin }
            val max = when { isOuter -> bwMax; isH -> hMax; else -> idMax }
            if (v < min * 0.5f || v > max * 2f) return@simpleWatcher  // ignore out-of-range
            val prog = valueToProgress(v.coerceIn(min, max), min, max)
            when {
                isOuter -> { suppressBW=true; sb.progress=prog; suppressBW=false }
                isH     -> { suppressH =true; sb.progress=prog; suppressH =false }
                else    -> { suppressID=true; sb.progress=prog; suppressID=false }
            }
            onChange(v.coerceIn(min, max))
        })

        return card
    }

    private fun makeStepBtn(ctx: android.content.Context, label: String,
                            accent: Int, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label; textSize = 14f
            setTextColor(accent)
            background = ctx.getDrawable(R.drawable.bg_card_dark)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(44, 40).apply { setMargins(8, 0, 0, 0) }
            setOnClickListener { onClick() }
        }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun simpleWatcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { action() }
    }

    private fun infoCard(ctx: android.content.Context, msg: String) = TextView(ctx).apply {
        text = msg; textSize = 10f; setTextColor(Color.parseColor("#505070"))
        background = ctx.getDrawable(R.drawable.bg_hint_card)
        setPadding(16, 12, 16, 12)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(14, 4, 14, 0) }
    }

    private fun sectionLabel(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 9f; letterSpacing = 0.14f
        setTextColor(Color.parseColor("#00D4FF")); setPadding(20, 18, 20, 6)
    }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1A1A28"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun spacer(ctx: android.content.Context, dp: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp)
    }

    private fun glRun(block: () -> Unit) =
        (activity as? MainActivity)?.glView?.queueEvent(block)

    // ── Lifecycle: pre-fill target from native selection and listen for changes
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
        // Pull whatever's currently selected at open time
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
