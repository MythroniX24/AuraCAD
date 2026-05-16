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
 * Ring Deformation Tool  v3
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
 * Both always start from origVerts → zero cumulative error.
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
    private val STEPS = 3000

    private var targetMeshIdx = 0
    private var ringAnalyzed  = false

    // ── Long-press selection sync ─────────────────────────────────────────────
    // Updated whenever the user long-presses a mesh in the viewport — keeps
    // the Ring Tool aimed at the most recently picked mesh instead of the old
    // hard-coded #0.  EditText below also writes to targetMeshIdx for manual
    // override.
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

    private var sbBandWidth: SeekBar?   = null
    private var etBandWidth: EditText?  = null
    private var sbInnerDia:  SeekBar?   = null
    private var etInnerDia:  EditText?  = null

    private var cardBW: View? = null
    private var cardID: View? = null

    @Volatile private var suppressBW = false
    @Volatile private var suppressID = false

    // Debounce: don't spam GL thread on every pixel of slider drag
    private var lastBWMM = -1f
    private var lastIDMM = -1f

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
                text = "v3"; textSize = 9f; letterSpacing = 0.12f
                setTextColor(Color.parseColor("#00D4FF"))
                background = ctx.getDrawable(R.drawable.bg_pill); setPadding(10, 3, 10, 3)
            })
        })
        root.addView(divider(ctx))

        // ── Detection panel ───────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "RING DETECTION"))
        root.addView(infoCard(ctx,
            "How to use: Open a ring STL/OBJ → tap Detect → " +
            "adjust Band Width (wall thickness) or Inner Diameter (ring size). " +
            "Texture is fully preserved during resize."))

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
                // Pre-fill with current selection so long-press → open Ring
                // Tool "just works" without typing the index.
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

        // ── Band Width card ───────────────────────────────────────────────────
        val bwCard = buildSliderCard(ctx, root,
            header    = "BAND WIDTH  (Wall Thickness)",
            desc      = "Outer wall expands/contracts · Inner bore stays fixed",
            unit      = "mm",
            accentHex = "#00D4FF",
            onSbInit  = { sb -> sbBandWidth = sb },
            onEtInit  = { et -> etBandWidth = et },
            onInfoInit= { tv -> tvBwCurrent = tv },
            onChange  = { v ->
                if (ringAnalyzed && v != lastBWMM) {
                    lastBWMM = v
                    glRun { NativeLib.nativeSetRingBandWidth(v) }
                    activity?.runOnUiThread {
                        updateBwInfo(v)
                        // Notify EditorPanel to refresh its dimension display
                        activity?.sendBroadcast(
                            android.content.Intent(EditorPanelFragment.ACTION_DIMS_CHANGED))
                    }
                }
            }
        )
        cardBW = bwCard; bwCard.visibility = View.GONE; root.addView(bwCard)
        root.addView(spacer(ctx, 6))

        // ── Inner Diameter card ───────────────────────────────────────────────
        val idCard = buildSliderCard(ctx, root,
            header    = "INNER DIAMETER  (Ring Size)",
            desc      = "Hole expands/contracts · Wall thickness stays the same",
            unit      = "mm",
            accentHex = "#FF9800",
            onSbInit  = { sb -> sbInnerDia = sb },
            onEtInit  = { et -> etInnerDia = et },
            onInfoInit= { tv -> tvIdCurrent = tv },
            onChange  = { v ->
                if (ringAnalyzed && v != lastIDMM) {
                    lastIDMM = v
                    glRun { NativeLib.nativeSetRingInnerDiameter(v) }
                    activity?.runOnUiThread {
                        updateIdInfo(v)
                        activity?.sendBroadcast(
                            android.content.Intent(EditorPanelFragment.ACTION_DIMS_CHANGED))
                    }
                }
            }
        )
        cardID = idCard; idCard.visibility = View.GONE; root.addView(idCard)
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
            tvStatus?.text = "⏳  Detecting ring…"; tvStatus?.setTextColor(Color.parseColor("#FFD54F"))
            tvInfo?.text = ""

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

        lastBWMM = origBandWidthMM
        lastIDMM = origInnerDiaMM
        ringAnalyzed = true

        tvStatus?.text = "✓ Ring detected"
        tvStatus?.setTextColor(Color.parseColor("#4CAF82"))
        tvInfo?.text = "Inner ⌀ %.2f mm  •  Outer ⌀ %.2f mm  •  Band %.2f mm  •  H %.2f mm  •  US ~%.1f"
            .format(origInnerDiaMM, p[4], origBandWidthMM, origHeightMM,
                    diamToUSSize(origInnerDiaMM))

        if (resetSliders) {
            setSliderTo(sbBandWidth, etBandWidth, origBandWidthMM, bwMin, bwMax, "BW")
            setSliderTo(sbInnerDia,  etInnerDia,  origInnerDiaMM,  idMin, idMax, "ID")
        }
        updateBwInfo(origBandWidthMM)
        updateIdInfo(origInnerDiaMM)

        cardBW?.visibility = View.VISIBLE
        cardID?.visibility = View.VISIBLE
    }

    private fun updateBwInfo(bwMM: Float) {
        val newOuterDia = (origInnerDiaMM + bwMM * 2f)
        tvBwCurrent?.text = "Band: %.2f mm  →  Outer ⌀ %.2f mm".format(bwMM, newOuterDia)
    }

    private fun updateIdInfo(idMM: Float) {
        tvIdCurrent?.text = "Inner ⌀: %.2f mm  →  US ring size ~%.1f".format(idMM, diamToUSSize(idMM))
    }

    /** US ring size from inner circumference: size = (circ_mm − 36.5) / 2.55 */
    private fun diamToUSSize(diamMM: Float): Float {
        val circ = diamMM * Math.PI.toFloat()
        return ((circ - 36.5f) / 2.55f).coerceAtLeast(0f)
    }

    // ── Set slider + EditText to a specific mm value ──────────────────────────
    private fun setSliderTo(sb: SeekBar?, et: EditText?, value: Float, min: Float, max: Float, token: String) {
        val prog = valueToProgress(value, min, max)
        val txt  = "%.2f".format(value)
        when (token) {
            "BW" -> { suppressBW = true; sb?.progress = prog; et?.setText(txt); suppressBW = false }
            "ID" -> { suppressID = true; sb?.progress = prog; et?.setText(txt); suppressID = false }
        }
    }

    private fun valueToProgress(v: Float, min: Float, max: Float) =
        ((v - min) / (max - min) * STEPS).toInt().coerceIn(0, STEPS)
    private fun progressToValue(p: Int, min: Float, max: Float) =
        min + p.toFloat() / STEPS * (max - min)

    // ── Build a slider control card ───────────────────────────────────────────
    private fun buildSliderCard(
        ctx: android.content.Context,
        @Suppress("UNUSED_PARAMETER") root: LinearLayout,
        header: String, desc: String, unit: String, accentHex: String,
        onSbInit:   (SeekBar) -> Unit,
        onEtInit:   (EditText) -> Unit,
        onInfoInit: (TextView) -> Unit,
        onChange:   (Float) -> Unit
    ): LinearLayout {
        val accent = Color.parseColor(accentHex)

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
        card.addView(inputRow)

        // SeekBar
        val sb = SeekBar(ctx).apply {
            max = STEPS; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList    = android.content.res.ColorStateList.valueOf(accent)
            setPadding(16, 8, 16, 0)
        }
        onSbInit(sb); card.addView(sb)

        // Live info label
        val tvInfo = TextView(ctx).apply {
            text = ""; textSize = 10f
            setTextColor(Color.parseColor("#606080")); setPadding(16, 6, 16, 0)
        }
        onInfoInit(tvInfo); card.addView(tvInfo)

        // ── Wire SeekBar ──────────────────────────────────────────────────────
        val isOuter = accentHex == "#00D4FF"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
            override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || !ringAnalyzed) return
                val min = if (isOuter) bwMin else idMin
                val max = if (isOuter) bwMax else idMax
                val v = progressToValue(p, min, max)
                val txt = "%.2f".format(v)
                if (isOuter) { if (!suppressBW) { suppressBW=true; et.setText(txt); suppressBW=false } }
                else          { if (!suppressID) { suppressID=true; et.setText(txt); suppressID=false } }
                onChange(v)
            }
        })

        // ── Wire EditText ─────────────────────────────────────────────────────
        et.addTextChangedListener(simpleWatcher {
            if (!ringAnalyzed) return@simpleWatcher
            if (isOuter && suppressBW) return@simpleWatcher
            if (!isOuter && suppressID) return@simpleWatcher
            val v = et.text.toString().toFloatOrNull() ?: return@simpleWatcher
            val min = if (isOuter) bwMin else idMin
            val max = if (isOuter) bwMax else idMax
            if (v < min * 0.5f || v > max * 2f) return@simpleWatcher  // ignore out-of-range
            val prog = valueToProgress(v.coerceIn(min, max), min, max)
            if (isOuter) { suppressBW=true; sb.progress=prog; suppressBW=false }
            else          { suppressID=true; sb.progress=prog; suppressID=false }
            onChange(v.coerceIn(min, max))
        })

        return card
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
