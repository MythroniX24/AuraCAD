package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Brush Sculpting Tool
 *
 * Smooth brush: Laplacian relaxation — polishes/smooths surface bumps.
 *   Math: v_new = v_old + intensity × weight × (centroid(neighbors) − v_old)
 *
 * Sculpt brush: Normal displacement — raises or lowers surface area.
 *   Math: v_new = v_old + sign × intensity × weight × vertex_normal
 *
 * Weight: Gaussian quintic falloff = 1 − 6t⁵ + 15t⁴ − 10t³, t = (dist/radius)²
 *
 * Usage: Tap canvas while tool is active to apply brush at that point.
 * The touch → world ray intersection picks the hit point on the mesh surface.
 */
class BrushToolFragment : BottomSheetDialogFragment() {

    var brushRadius    = 0.06f
    var brushIntensity = 0.4f
    var isSmooth       = true    // true=smooth brush, false=sculpt brush
    var sculptSign     = 1f      // +1=raise, -1=lower
    var targetMeshIdx  = 0

    private var tvStatus: TextView? = null
    private var sbRadius: SeekBar? = null
    private var sbIntensity: SeekBar? = null
    private var tvRadiusVal: TextView? = null
    private var tvIntensityVal: TextView? = null
    private var btnSmooth: Button? = null
    private var btnSculptUp: Button? = null
    private var btnSculptDown: Button? = null
    private var tvInfo: TextView? = null

    // ── Brush is applied by ModelGLSurfaceView calling this ──────────────────
    fun applyBrushAt(wx: Float, wy: Float, wz: Float) {
        if (!isAdded) return
        val glv = (activity as? MainActivity)?.glView ?: return
        val idx = targetMeshIdx
        val r = brushRadius
        val i = brushIntensity

        glv.queueEvent {
            if (isSmooth) {
                NativeLib.nativeApplySmooth(idx, wx, wy, wz, r, i)
            } else {
                NativeLib.nativeApplySculpt(idx, wx, wy, wz, r, i, sculptSign)
            }
        }
        glv.requestRender()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(0, 0, 0, 48)
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

        // Title + mesh index
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 14, 16, 6)
            addView(TextView(ctx).apply {
                text = "🖌️  Brush Tool"; textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply { text = "Mesh #"; textSize = 11f; setTextColor(Color.parseColor("#9090B0")) })
            addView(android.widget.EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText("0"); setTextColor(Color.WHITE); textSize = 13f
                background = ctx.getDrawable(R.drawable.bg_input_field)
                setPadding(10, 4, 10, 4)
                layoutParams = LinearLayout.LayoutParams(64, LinearLayout.LayoutParams.WRAP_CONTENT)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        targetMeshIdx = s?.toString()?.toIntOrNull() ?: 0
                    }
                })
            })
        })

        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1A1A28"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // How to use tip
        root.addView(TextView(ctx).apply {
            text = "Select a brush type below, then tap anywhere on the model surface to apply. Long-press applies continuously."
            textSize = 10f; setTextColor(Color.parseColor("#404060")); setPadding(20, 10, 20, 6)
        })

        // ── Brush type selector ────────────────────────────────────────────────
        root.addView(secLabel(ctx, "🔘  BRUSH TYPE"))
        val typeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(14, 6, 14, 4)
        }

        val bSmooth = Button(ctx).apply {
            text = "✨ Smooth / Polish"
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#050508"))
            background = ctx.getDrawable(R.drawable.bg_btn_accent)
            layoutParams = LinearLayout.LayoutParams(0, 44, 1f).apply { setMargins(0,0,6,0) }
            setOnClickListener { selectBrush(true, 1f) }
        }
        btnSmooth = bSmooth; typeRow.addView(bSmooth)

        val bUp = Button(ctx).apply {
            text = "▲ Raise"
            textSize = 11f; setTextColor(Color.parseColor("#9090B0"))
            background = ctx.getDrawable(R.drawable.bg_card_dark)
            layoutParams = LinearLayout.LayoutParams(0, 44, 0.7f).apply { setMargins(0,0,6,0) }
            setOnClickListener { selectBrush(false, 1f) }
        }
        btnSculptUp = bUp; typeRow.addView(bUp)

        val bDown = Button(ctx).apply {
            text = "▼ Lower"
            textSize = 11f; setTextColor(Color.parseColor("#9090B0"))
            background = ctx.getDrawable(R.drawable.bg_card_dark)
            layoutParams = LinearLayout.LayoutParams(0, 44, 0.7f)
            setOnClickListener { selectBrush(false, -1f) }
        }
        btnSculptDown = bDown; typeRow.addView(bDown)
        root.addView(typeRow)

        // Description
        val tvI = TextView(ctx).apply {
            text = "✨ Smooth: Polishes surface by relaxing vertices toward neighbors"
            textSize = 9f; setTextColor(Color.parseColor("#505070")); setPadding(20, 4, 20, 8)
        }
        tvInfo = tvI; root.addView(tvI)

        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1A1A28"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // ── Radius ────────────────────────────────────────────────────────────
        root.addView(secLabel(ctx, "📏  BRUSH RADIUS"))
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(20, 4, 20, 0)
            addView(TextView(ctx).apply { text = "Tiny"; textSize = 9f; setTextColor(Color.parseColor("#505070")) })
            val tvRV = TextView(ctx).apply {
                text = "%.3f".format(brushRadius); textSize = 11f
                setTextColor(Color.parseColor("#00D4FF"))
                setPadding(0, 0, 0, 0)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvRadiusVal = tvRV; addView(tvRV)
            addView(TextView(ctx).apply { text = "Large"; textSize = 9f; setTextColor(Color.parseColor("#505070")) })
        })
        val sbR = SeekBar(ctx).apply {
            setMax(100); progress = (brushRadius / 0.3f * 100).toInt().coerceIn(1, 100)
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
            thumbTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
            setPadding(20, 4, 20, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) {}
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    brushRadius = 0.003f + p / 100f * 0.297f
                    tvRadiusVal?.text = "%.3f".format(brushRadius)
                }
            })
        }
        sbRadius = sbR; root.addView(sbR)

        // ── Intensity ─────────────────────────────────────────────────────────
        root.addView(secLabel(ctx, "💪  INTENSITY"))
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(20, 4, 20, 0)
            addView(TextView(ctx).apply { text = "Light"; textSize = 9f; setTextColor(Color.parseColor("#505070")) })
            val tvIV = TextView(ctx).apply {
                text = "${(brushIntensity*100).toInt()}%"; textSize = 11f
                setTextColor(Color.parseColor("#FF9800"))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvIntensityVal = tvIV; addView(tvIV)
            addView(TextView(ctx).apply { text = "Strong"; textSize = 9f; setTextColor(Color.parseColor("#505070")) })
        })
        val sbI = SeekBar(ctx).apply {
            setMax(100); progress = (brushIntensity * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
            thumbTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
            setPadding(20, 4, 20, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) {}
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    brushIntensity = p / 100f
                    tvIntensityVal?.text = "${p}%"
                }
            })
        }
        sbIntensity = sbI; root.addView(sbI)

        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1A1A28"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { topMargin=12 }
        })

        // Status + tip
        val tvSt = TextView(ctx).apply {
            text = "✨ Smooth brush active — tap model surface"
            textSize = 11f; setTextColor(Color.parseColor("#4CAF82"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(20, 12, 20, 4)
        }
        tvStatus = tvSt; root.addView(tvSt)

        root.addView(TextView(ctx).apply {
            text = "⚠ Brush modifies vertex positions permanently.\nUse Undo (↩) to revert."
            textSize = 9f; setTextColor(Color.parseColor("#604020")); setPadding(20, 0, 20, 16)
        })

        return scroll
    }

    private fun selectBrush(smooth: Boolean, sign: Float) {
        isSmooth = smooth
        sculptSign = sign
        val ctx = context ?: return
        val accent = Color.parseColor("#00D4FF")
        val dark   = Color.parseColor("#9090B0")

        btnSmooth?.setTextColor(if (smooth) Color.parseColor("#050508") else dark)
        btnSmooth?.background = ctx.getDrawable(if (smooth) R.drawable.bg_btn_accent else R.drawable.bg_card_dark)

        val upSel   = !smooth && sign > 0
        val downSel = !smooth && sign < 0
        btnSculptUp?.setTextColor(if (upSel) Color.parseColor("#050508") else dark)
        btnSculptUp?.background = ctx.getDrawable(if (upSel) R.drawable.bg_btn_accent else R.drawable.bg_card_dark)
        btnSculptDown?.setTextColor(if (downSel) Color.parseColor("#050508") else dark)
        btnSculptDown?.background = ctx.getDrawable(if (downSel) R.drawable.bg_btn_accent else R.drawable.bg_card_dark)

        tvInfo?.text = when {
            smooth    -> "✨ Smooth: Polishes surface by relaxing vertices toward neighbors"
            sign > 0  -> "▲ Raise: Pushes surface outward along vertex normals"
            else      -> "▼ Lower: Pushes surface inward along vertex normals"
        }
        tvStatus?.text = when {
            smooth   -> "✨ Smooth brush active — tap model surface"
            sign > 0 -> "▲ Raise brush active — tap to raise area"
            else     -> "▼ Lower brush active — tap to lower area"
        }
    }

    private fun secLabel(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 11f; letterSpacing = 0.05f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(Color.parseColor("#00D4FF")); setPadding(20, 14, 20, 4)
    }

    companion object {
        const val TAG = "BrushTool"
        fun newInstance() = BrushToolFragment()
    }
}
