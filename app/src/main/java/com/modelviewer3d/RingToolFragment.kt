package com.modelviewer3d

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import kotlin.math.max

/**
 * Ring Studio v5 — redesigned UI + Gemini AI Ring Fit.
 *
 * Native deformation logic is unchanged (and unit-verified):
 *  BAND WIDTH : r_new = origInner + (r_orig − origInner) × (newBand / origBand)
 *  INNER DIAM : r_new = r_orig + (newInner − origInner)
 *  HEIGHT     : h_new = h_orig × (newHeight / origHeight)
 *
 * NEW: live top-view ring preview, step-free slider cards, and an AI section
 * that lets Gemini look at the ring (screenshot) and suggest exact mm values.
 */
class RingToolFragment : BottomSheetDialogFragment() {

    // Ring params from native analysis (mm)
    private var origInnerRadMM  = 0f
    private var origBandWidthMM = 0f
    private var origInnerDiaMM  = 0f
    private var origHeightMM    = 0f

    // Dynamic slider ranges (set after analysis)
    private var bwMin = 0.1f;  private var bwMax = 20f
    private var idMin = 1.0f;  private var idMax = 50f
    private var hMin  = 0.5f;  private var hMax  = 50f
    private val STEPS = 3000

    private var targetMeshIdx = 0
    private var ringAnalyzed  = false

    // Long-press selection sync
    private var etMeshIdx: EditText? = null
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
    private var tvStatus:   TextView? = null
    private var tvInfo:     TextView? = null
    private var tvSummary:  TextView? = null
    private var tvBwCurrent: TextView? = null
    private var tvIdCurrent: TextView? = null
    private var tvHCurrent:  TextView? = null
    private var ringPreview: RingPreviewView? = null

    private var sbBandWidth: SeekBar? = null
    private var etBandWidth: EditText? = null
    private var sbInnerDia:  SeekBar? = null
    private var etInnerDia:  EditText? = null
    private var sbHeight:    SeekBar? = null
    private var etHeight:    EditText? = null

    private var cardBW: View? = null
    private var cardID: View? = null
    private var cardH:  View? = null
    private var presetRow: LinearLayout? = null

    // AI section refs
    private var etAiTarget: EditText? = null
    private var aiStatus:   TextView? = null
    private var aiNote:     TextView? = null
    private var btnApplyAi: Button? = null

    @Volatile private var suppressBW = false
    @Volatile private var suppressID = false
    @Volatile private var suppressH  = false
    private var proportional = false

    private var lastBWMM = -1f
    private var lastIDMM = -1f
    private var lastHMM  = -1f

    /** Common US ring sizes (diameter mm) for the quick-preset row. */
    private val usPresets = listOf(
        3f to 14.1f, 4f to 14.9f, 5f to 15.7f, 6f to 16.5f, 7f to 17.3f,
        8f to 18.1f, 9f to 18.9f, 10f to 19.8f, 11f to 20.6f, 12f to 21.4f
    )

    private fun simpleWatcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { action() }
    }

    private fun sectionLabel(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 9f; letterSpacing = 0.14f
        setTextColor(Color.parseColor("#62E6FF")); setPadding(20, 18, 20, 6)
    }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#243445"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun spacer(ctx: android.content.Context, dp: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp)
    }

    private fun glRun(block: () -> Unit) =
        (activity as? MainActivity)?.glView?.queueEvent(block)

    private fun infoCard(ctx: android.content.Context, msg: String) = TextView(ctx).apply {
        text = msg; textSize = 10f; setTextColor(Color.parseColor("#607286"))
        background = ctx.getDrawable(R.drawable.bg_hint_card)
        setPadding(16, 12, 16, 12)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(14, 4, 14, 0) }
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
                setBackgroundColor(Color.parseColor("#607286"))
                layoutParams = LinearLayout.LayoutParams(48, 4)
            })
        })

        // ── Header ────────────────────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 14, 20, 6)
            addView(TextView(ctx).apply {
                text = "💍  Ring Studio"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = "AI"
                textSize = 9f; letterSpacing = 0.12f
                setTextColor(Color.parseColor("#061018"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = ctx.getDrawable(R.drawable.bg_btn_accent); setPadding(10, 3, 10, 3)
            })
            addView(TextView(ctx).apply {
                text = "v5"
                textSize = 9f; letterSpacing = 0.12f
                setTextColor(Color.parseColor("#62E6FF"))
                background = ctx.getDrawable(R.drawable.bg_pill)
                setPadding(10, 3, 10, 3)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(8, 0, 0, 0) }
            })
        })
        root.addView(divider(ctx))

        // ── Live ring preview (top view, to scale) ────────────────────────────
        val preview = RingPreviewView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 150
            ).apply { setMargins(14, 10, 14, 4) }
        }
        ringPreview = preview
        root.addView(preview)

        // Detection status + info
        tvStatus = TextView(ctx).apply {
            text = "⚠  No ring detected yet — tap Detect below"; textSize = 10f
            setTextColor(Color.parseColor("#FF9B71")); setPadding(20, 8, 20, 2)
        }
        root.addView(tvStatus)
        tvInfo = TextView(ctx).apply {
            text = ""; textSize = 10f
            setTextColor(Color.parseColor("#74869A")); setPadding(20, 0, 20, 6)
        }
        root.addView(tvInfo)

        // Mesh index row
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 6, 20, 0)
            addView(TextView(ctx).apply {
                text = "Mesh:"; textSize = 11f
                setTextColor(Color.parseColor("#A8B6C7")); setPadding(0, 0, 12, 0)
            })
            val etIdx = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(targetMeshIdx.toString())
                setTextColor(Color.WHITE); textSize = 13f
                background = ctx.getDrawable(R.drawable.bg_input_field); setPadding(10, 8, 10, 8)
                layoutParams = LinearLayout.LayoutParams(80, LinearLayout.LayoutParams.WRAP_CONTENT)
                addTextChangedListener(simpleWatcher {
                    targetMeshIdx = text.toString().toIntOrNull() ?: 0
                })
            }
            etMeshIdx = etIdx
            addView(etIdx)
            addView(TextView(ctx).apply {
                text = "  (0 = whole model) · long-press a mesh on canvas to target it"
                textSize = 9f; setTextColor(Color.parseColor("#404060"))
            })
        })

        // Detect button
        val btnDetect = Button(ctx).apply {
            text = "▶  Detect Ring Geometry"
            textSize = 12f; setTextColor(Color.parseColor("#061018"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = ctx.getDrawable(R.drawable.bg_btn_accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 52
            ).apply { setMargins(20, 12, 20, 0) }
        }
        root.addView(btnDetect)
        root.addView(divider(ctx))

        // ── Live summary card (after detection) ───────────────────────────────
        tvSummary = TextView(ctx).apply {
            text = ""; textSize = 10f
            setTextColor(Color.parseColor("#62E6FF"))
            background = ctx.getDrawable(R.drawable.bg_hint_card)
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 4, 14, 0) }
        }
        root.addView(tvSummary)

        // Proportional toggle
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 8, 20, 0)
            addView(TextView(ctx).apply {
                text = "🔗 Proportional resize (scale all)"; textSize = 11f
                setTextColor(Color.parseColor("#A8B6C7"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Switch(ctx).apply {
                isChecked = false
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#62E6FF"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A3D50"))
                setOnCheckedChangeListener { _, v -> proportional = v }
            })
        })

        // ── Control cards (hidden until detection) ────────────────────────────
        val bwCard = buildSliderCard(ctx,
            header    = "BAND WIDTH  (Wall)",
            desc      = "Outer wall expands · inner bore fixed",
            unit      = "mm",
            accentHex = "#62E6FF",
            step      = 0.25f,
            onSbInit  = { sb -> sbBandWidth = sb },
            onEtInit  = { et -> etBandWidth = et },
            onInfoInit= { tv -> tvBwCurrent = tv },
            onChange  = { v -> onBandWidthChanged(v) }
        )
        cardBW = bwCard; bwCard.visibility = View.GONE; root.addView(bwCard)
        root.addView(spacer(ctx, 6))

        val idCard = buildSliderCard(ctx,
            header    = "INNER DIAMETER  (Ring Size)",
            desc      = "Hole resizes · wall thickness stays",
            unit      = "mm",
            accentHex = "#FFB86B",
            step      = 0.5f,
            onSbInit  = { sb -> sbInnerDia = sb },
            onEtInit  = { et -> etInnerDia = et },
            onInfoInit= { tv -> tvIdCurrent = tv },
            onChange  = { v -> onInnerDiaChanged(v) }
        )
        cardID = idCard; idCard.visibility = View.GONE; root.addView(idCard)

        presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(20, 10, 20, 0)
        }
        presetRow?.visibility = View.GONE
        root.addView(presetRow)
        root.addView(spacer(ctx, 6))

        val hCard = buildSliderCard(ctx,
            header    = "HEIGHT  (Axial)",
            desc      = "Stretch / squash along ring axis",
            unit      = "mm",
            accentHex = "#A78BFA",
            step      = 0.5f,
            onSbInit  = { sb -> sbHeight = sb },
            onEtInit  = { et -> etHeight = et },
            onInfoInit= { tv -> tvHCurrent = tv },
            onChange  = { v -> onHeightChanged(v) }
        )
        cardH = hCard; hCard.visibility = View.GONE; root.addView(hCard)
        root.addView(divider(ctx))

        // ── AI Ring Fit section ───────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "✨ AI RING FIT  ·  GEMINI"))
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 4, 20, 0)
            addView(TextView(ctx).apply {
                text = "Target size:"; textSize = 11f
                setTextColor(Color.parseColor("#A8B6C7")); setPadding(0, 0, 10, 0)
            })
            val et = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                hint = "e.g. US 6 · 17.5mm · size 7 · comfortable"
                setHintTextColor(Color.parseColor("#404060"))
                setTextColor(Color.WHITE); textSize = 13f
                background = ctx.getDrawable(R.drawable.bg_input_field)
                setPadding(12, 10, 12, 10)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            etAiTarget = et
            addView(et)
            addView(Button(ctx).apply {
                text = "⚙"; textSize = 14f; setTextColor(Color.parseColor("#62E6FF"))
                background = ctx.getDrawable(R.drawable.bg_card_dark)
                layoutParams = LinearLayout.LayoutParams(48, 44).apply { setMargins(8, 0, 0, 0) }
                setOnClickListener { openAiSettings() }
            })
        })
        val btnAiFit = Button(ctx).apply {
            text = "✨  AI Fit My Ring"
            textSize = 12f; setTextColor(Color.parseColor("#061018"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = ctx.getDrawable(R.drawable.bg_btn_accent)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 50
            ).apply { setMargins(20, 10, 20, 0) }
        }
        root.addView(btnAiFit)
        aiStatus = TextView(ctx).apply {
            text = "💡 Type a size then tap AI Fit — Gemini detects the ring from the preview and suggests exact mm."
            textSize = 10f; setTextColor(Color.parseColor("#8080A0"))
            setLineSpacing(0f, 1.25f); setPadding(20, 8, 20, 0)
        }
        root.addView(aiStatus)
        aiNote = TextView(ctx).apply {
            text = ""; textSize = 10f; setTextColor(Color.parseColor("#A8B6C7"))
            setLineSpacing(0f, 1.25f); setPadding(20, 0, 20, 0)
            background = ctx.getDrawable(R.drawable.bg_hint_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 8, 14, 0) }
        }
        root.addView(aiNote)
        btnApplyAi = Button(ctx).apply {
            text = "✓  Apply AI Sizing"
            textSize = 12f; setTextColor(Color.parseColor("#061018"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = ctx.getDrawable(R.drawable.bg_btn_accent)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 50
            ).apply { setMargins(20, 10, 20, 0) }
        }
        root.addView(btnApplyAi)
        root.addView(divider(ctx))

        // ── Action row ────────────────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(20, 12, 20, 0)
            addView(Button(ctx).apply {
                text = "↺ Reset"; textSize = 11f
                setTextColor(Color.parseColor("#FF9B71"))
                background = ctx.getDrawable(R.drawable.bg_btn_danger); setPadding(20, 0, 20, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, 44).apply { setMargins(0, 0, 12, 0) }
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
                setTextColor(Color.parseColor("#A8B6C7"))
                background = ctx.getDrawable(R.drawable.bg_card_dark); setPadding(20, 0, 20, 0)
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

        // ── Wire AI buttons ───────────────────────────────────────────────────
        btnAiFit.setOnClickListener { runAiFit() }
        btnApplyAi?.setOnClickListener { applyPendingAiSuggestion() }

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

        ringPreview?.innerDiaMM = origInnerDiaMM
        ringPreview?.outerDiaMM = p[4]

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
            text = "US: "; textSize = 10f
            setTextColor(Color.parseColor("#A8B6C7"))
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 6, 0)
        })
        usPresets.forEach { (us, diaMM) ->
            presetRow?.addView(Button(ctx).apply {
                text = "$us"; textSize = 10f
                setTextColor(Color.parseColor("#62E6FF"))
                background = ctx.getDrawable(R.drawable.bg_card_dark)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(52, 36).apply { setMargins(0, 0, 6, 0) }
                setOnClickListener { setInnerDiaPreset(diaMM, us) }
            })
        }
    }

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
        tvIdCurrent?.text = "Inner ⌀: %.2f mm  →  %s  ·  C %.1f mm".format(
            idMM, RingMath.usSizeLabel(idMM), RingMath.circumferenceMM(idMM))
    }

    private fun updateHInfo(hMM: Float) {
        tvHCurrent?.text = "Height: %.2f mm  (%s of original)"
            .format(hMM, if (origHeightMM > 0f) "%.0f%%".format(hMM / origHeightMM * 100f) else "—")
    }

    private fun updateSummary() {
        val bw = lastBWMM; val id = lastIDMM; val h = lastHMM
        if (bw <= 0f || id <= 0f || h <= 0f) return
        val outer = RingMath.outerDia(id, bw)
        tvSummary?.text = "📋 Inner ⌀ %.2f mm · Outer ⌀ %.2f mm · Band %.2f mm · H %.2f mm · %s · C %.1f mm"
            .format(id, outer, bw, h, RingMath.usSizeLabel(id), RingMath.circumferenceMM(id))
        ringPreview?.innerDiaMM = id
        ringPreview?.outerDiaMM = outer
    }

    private fun setSliderTo(sb: SeekBar?, et: EditText?, value: Float,
                            min: Float, max: Float, token: String, fromUser: Boolean) {
        val prog = valueToProgress(value, min, max)
        val txt  = "%.2f".format(value)
        when (token) {
            "BW" -> { suppressBW = true; sb?.progress = prog; et?.setText(txt); suppressBW = false }
            "ID" -> { suppressID = true; sb?.progress = prog; et?.setText(txt); suppressID = false }
            "H"  -> { suppressH  = true; sb?.progress = prog; et?.setText(txt); suppressH  = false }
        }
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

    // ── Slider control card ───────────────────────────────────────────────────
    private fun buildSliderCard(
        ctx: android.content.Context,
        header: String, desc: String, unit: String, accentHex: String,
        step: Float,
        onSbInit:   (SeekBar) -> Unit,
        onEtInit:   (EditText) -> Unit,
        onInfoInit: (TextView) -> Unit,
        onChange:   (Float) -> Unit
    ): LinearLayout {
        val accent = Color.parseColor(accentHex)
        val isOuter = accentHex == "#62E6FF"
        val isH     = accentHex == "#A78BFA"

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
            text = desc; textSize = 9f; setTextColor(Color.parseColor("#607286"))
            setPadding(16, 3, 16, 8)
        })

        val sb = SeekBar(ctx).apply {
            max = STEPS; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList    = android.content.res.ColorStateList.valueOf(accent)
            setPadding(16, 8, 16, 0)
        }
        onSbInit(sb)

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
            text = " $unit"; textSize = 12f; setTextColor(Color.parseColor("#607286"))
        })
        fun applyValue(v: Float) {
            val txt = "%.2f".format(v)
            when {
                isOuter -> { suppressBW = true; et.setText(txt); sb.progress = valueToProgress(v, bwMin, bwMax); suppressBW = false }
                isH     -> { suppressH  = true; et.setText(txt); sb.progress = valueToProgress(v, hMin,  hMax);  suppressH  = false }
                else    -> { suppressID = true; et.setText(txt); sb.progress = valueToProgress(v, idMin, idMax); suppressID = false }
            }
            onChange(v)
        }
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

        val tvInfo = TextView(ctx).apply {
            text = ""; textSize = 10f
            setTextColor(Color.parseColor("#74869A")); setPadding(16, 6, 16, 0)
        }
        onInfoInit(tvInfo); card.addView(tvInfo)

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

        et.addTextChangedListener(simpleWatcher {
            if (!ringAnalyzed) return@simpleWatcher
            if (isOuter && suppressBW) return@simpleWatcher
            if (isH     && suppressH)  return@simpleWatcher
            if (!isOuter && !isH && suppressID) return@simpleWatcher
            val v = et.text.toString().toFloatOrNull() ?: return@simpleWatcher
            val min = when { isOuter -> bwMin; isH -> hMin; else -> idMin }
            val max = when { isOuter -> bwMax; isH -> hMax; else -> idMax }
            if (v < min * 0.5f || v > max * 2f) return@simpleWatcher
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

    // ── AI Ring Fit ───────────────────────────────────────────────────────────
    private fun openAiSettings() {
        val act = activity as? MainActivity ?: return
        if (act.supportFragmentManager.findFragmentByTag(AiSettingsFragment.TAG) != null) return
        AiSettingsFragment.newInstance().show(act.supportFragmentManager, AiSettingsFragment.TAG)
    }

    private var pendingAiInner = -1f
    private var pendingAiBand  = -1f
    private var pendingAiH     = -1f

    private fun runAiFit() {
        val target = etAiTarget?.text?.toString()?.trim().orEmpty()
        if (target.isEmpty()) {
            aiStatus?.text = "✋ Pehle target size likho — e.g. US 6 · 17.5mm · size 7"
            aiStatus?.setTextColor(Color.parseColor("#FFD54F"))
            return
        }
        aiStatus?.text = "✨ Analyzing ring…"; aiStatus?.setTextColor(Color.parseColor("#FFD54F"))
        aiNote?.text = ""; btnApplyAi?.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            // 1) Make sure the ring geometry is known (fast native analysis)
            if (!ringAnalyzed) {
                val ok = ensureAnalyzedOnIO()
                if (!ok) {
                    withContext(Dispatchers.Main) {
                        aiStatus?.text = "✗ Ring detect nahi hua — pehle Detect dabao"
                        aiStatus?.setTextColor(Color.parseColor("#FF5252"))
                    }
                    return@launch
                }
            }

            val inner  = origInnerDiaMM
            val outer  = RingMath.outerDia(origInnerDiaMM, origBandWidthMM)
            val band   = origBandWidthMM
            val height = origHeightMM
            val parsed = RingMath.parseUserSize(target)
            val apiKey = AiPrefs.apiKey(requireContext())

            var suggestion: RingAi.Suggestion? = null
            if (apiKey.isNotBlank()) {
                val png = capturePngBase64()
                try {
                    val raw = GeminiClient.generate(
                        apiKey, RingAi.SYSTEM_PROMPT,
                        RingAi.buildFitPrompt(inner, outer, band, height,
                            (activity as? MainActivity)?.currentModelName() ?: "ring model", target),
                        png
                    )
                    suggestion = RingAi.parseSuggestion(raw ?: "")
                } catch (_: Exception) { suggestion = null }
            }

            val base = if (suggestion != null)
                RingAi.validate(suggestion, inner, band, height,
                    idMin, idMax, bwMin, bwMax, hMin, hMax)
            else RingAi.fallback(parsed, inner, band, height, noKey = apiKey.isBlank())

            val finalInner = RingAi.finalInnerDia(base.innerDiaMM, parsed) ?: inner
            val finalBand  = base.bandWidthMM?.coerceIn(bwMin, bwMax) ?: band
            val finalH     = base.heightMM?.coerceIn(hMin, hMax) ?: height

            pendingAiInner = finalInner
            pendingAiBand  = finalBand
            pendingAiH     = finalH

            withContext(Dispatchers.Main) {
                btnApplyAi?.visibility = View.VISIBLE
                aiStatus?.text = "✓ Ready: Inner ⌀ %.2f mm (%s) · Band %.2f mm · H %.2f mm".format(
                    finalInner, RingMath.usSizeLabel(finalInner), finalBand, finalH)
                aiStatus?.setTextColor(Color.parseColor("#4CAF82"))
                aiNote?.text = "💬 ${base.note.ifEmpty { "Tap Apply to resize the ring." }}"
            }
        }
    }

    private fun applyPendingAiSuggestion() {
        if (pendingAiInner < 0f || !ringAnalyzed) return
        setSliderTo(sbInnerDia, etInnerDia, pendingAiInner, idMin, idMax, "ID", fromUser = true)
        setSliderTo(sbBandWidth, etBandWidth, pendingAiBand, bwMin, bwMax, "BW", fromUser = true)
        setSliderTo(sbHeight, etHeight, pendingAiH, hMin, hMax, "H", fromUser = true)
        updateSummary()
        tvStatus?.text = "✓ AI applied — %s".format(RingMath.usSizeLabel(pendingAiInner))
        tvStatus?.setTextColor(Color.parseColor("#4CAF82"))
        aiStatus?.text = "✓ Applied! Ring ab fit hai."
        aiStatus?.setTextColor(Color.parseColor("#4CAF82"))
        btnApplyAi?.visibility = View.GONE
        activity?.sendBroadcast(android.content.Intent(EditorPanelFragment.ACTION_DIMS_CHANGED))
    }

    /** Runs nativeAnalyzeRing on the GL thread and applies params on the UI thread. */
    private fun ensureAnalyzedOnIO(): Boolean {
        val act = activity as? MainActivity ?: return false
        var ok = false
        var p = FloatArray(6)
        val latch = CountDownLatch(1)
        act.glView.queueEvent {
            try {
                ok = NativeLib.nativeAnalyzeRing(targetMeshIdx)
                if (ok) p = NativeLib.nativeGetRingParams()
            } catch (_: Exception) {}
            latch.countDown()
        }
        latch.await()
        if (ok && p.size >= 6) {
            val pp = p
            // Wait for the UI-thread applyRingParams to finish so the fields
            // (origInnerDiaMM, idMin.., etc.) are valid before runAiFit reads them.
            val uiDone = CountDownLatch(1)
            activity?.runOnUiThread {
                applyRingParams(pp, resetSliders = true)
                uiDone.countDown()
            }
            uiDone.await()
        }
        return ok
    }

    /** Capture the GL framebuffer, downscale, and return a base64 PNG (or null). */
    private fun capturePngBase64(maxDim: Int = 512): String? {
        val act = activity as? MainActivity ?: return null
        val gl = act.glView
        val w = gl.width; val h = gl.height
        if (w == 0 || h == 0) return null
        var bytes: ByteArray? = null
        val latch = CountDownLatch(1)
        gl.queueEvent {
            try { bytes = NativeLib.nativeTakeScreenshot() } catch (_: Exception) {}
            latch.countDown()
        }
        latch.await()
        val raw = bytes ?: return null
        val argb = IntArray(w * h)
        for (i in argb.indices) {
            val b = i * 4
            argb[i] = (raw[b + 3].toInt() and 0xFF shl 24) or
                      (raw[b + 0].toInt() and 0xFF shl 16) or
                      (raw[b + 1].toInt() and 0xFF shl 8)  or
                      (raw[b + 2].toInt() and 0xFF)
        }
        var bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        if (max(w, h) > maxDim) {
            val scale = maxDim.toFloat() / max(w, h)
            bmp = Bitmap.createScaledBitmap(
                bmp, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

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
