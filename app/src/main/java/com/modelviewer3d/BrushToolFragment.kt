package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Brush Tool — smooth and sculpt brushes applied at a picked 3-D point.
 *
 * Usage
 * ─────
 * 1. Open this sheet.
 * 2. Select mode (Smooth or Sculpt) and adjust Radius / Strength sliders.
 * 3. Tap a point on the mesh in the viewport; the brush is applied there.
 *    The viewport passes screen coordinates via [applyAtScreenPoint].
 *
 * Implementation notes
 * ────────────────────
 * • nativePickPoint   — converts screen tap to world-space 3-D point.
 * • nativeApplySmooth — Laplacian smooth within [radius] mm.
 * • nativeApplySculpt — inflate/deflate vertices within [radius] mm.
 * Both C++ functions rebuild normals and update the VBO; they are safe to
 * call only from the GL thread (via glView.queueEvent).
 */
class BrushToolFragment : BottomSheetDialogFragment() {

    // ── State ─────────────────────────────────────────────────────────────────
    private var brushMode = MODE_SMOOTH   // 0 = smooth, 1 = sculpt
    private var radius    = 1.0f          // mm
    private var strength  = 0.5f          // 0-1 for smooth; mm for sculpt
    private var meshIdx   = -1            // operated-on mesh

    // ── UI refs ───────────────────────────────────────────────────────────────
    private var btnSmooth:   Button?   = null
    private var btnSculpt:   Button?   = null
    private var tvRadius:    TextView? = null
    private var tvStrength:  TextView? = null
    private var tvStatus:    TextView? = null

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
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, 14, 0, 0)
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#404058"))
                layoutParams = LinearLayout.LayoutParams(48, 4)
            })
        })

        // Title row
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(20, 14, 20, 6)
            addView(TextView(ctx).apply {
                text = "🖌  Brush Tool"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = "SCULPT"
                textSize = 8f; letterSpacing = 0.12f
                setTextColor(Color.parseColor("#FF9800"))
                background = ctx.getDrawable(R.drawable.bg_pill)
                setPadding(10, 3, 10, 3)
            })
        })

        root.addView(divider(ctx))

        // ── Mode selector ─────────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "BRUSH MODE"))
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 4, 20, 4)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, 8, 0) }
            btnSmooth = Button(ctx).apply {
                text = "✦  Smooth"
                textSize = 12f
                background = ctx.getDrawable(R.drawable.bg_btn_accent)
                setTextColor(Color.WHITE)
                layoutParams = lp
                setOnClickListener { setMode(MODE_SMOOTH) }
            }
            btnSculpt = Button(ctx).apply {
                text = "⬡  Sculpt"
                textSize = 12f
                background = ctx.getDrawable(R.drawable.bg_btn_secondary)
                setTextColor(Color.parseColor("#9090B0"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { setMode(MODE_SCULPT) }
            }
            addView(btnSmooth!!)
            addView(btnSculpt!!)
        })

        root.addView(divider(ctx))

        // ── Radius slider ─────────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "RADIUS  (mm)"))
        tvRadius = TextView(ctx).apply {
            text = "1.00 mm"; textSize = 10f
            setTextColor(Color.parseColor("#00D4FF")); setPadding(20, 2, 20, 0)
        }
        root.addView(tvRadius!!)
        root.addView(SeekBar(ctx).apply {
            max = 100; progress = 10           // 0.1 – 10 mm range
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
            thumbTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
            setPadding(20, 4, 20, 4)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) {}
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    radius = 0.1f + (p / 100f) * 9.9f
                    tvRadius?.text = "%.2f mm".format(radius)
                }
            })
        })

        root.addView(divider(ctx))

        // ── Strength / Amount slider ──────────────────────────────────────────
        root.addView(sectionLabel(ctx, "STRENGTH  (smooth 0-1 / sculpt ± mm)"))
        tvStrength = TextView(ctx).apply {
            text = "0.50"; textSize = 10f
            setTextColor(Color.parseColor("#00D4FF")); setPadding(20, 2, 20, 0)
        }
        root.addView(tvStrength!!)
        root.addView(SeekBar(ctx).apply {
            max = 1000; progress = 500        // centre = 0 for sculpt, 0.5 for smooth
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF82"))
            thumbTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF82"))
            setPadding(20, 4, 20, 4)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) {}
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    strength = if (brushMode == MODE_SMOOTH) {
                        p / 1000f                          // 0–1
                    } else {
                        -2f + (p / 1000f) * 4f             // ± 2 mm
                    }
                    tvStrength?.text = if (brushMode == MODE_SMOOTH)
                        "%.2f".format(strength)
                    else
                        "%.2f mm".format(strength)
                }
            })
        })

        root.addView(divider(ctx))

        // ── Mesh selector ─────────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "TARGET MESH"))
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(20, 4, 20, 4)
            addView(TextView(ctx).apply {
                text = "Auto (active selection)"; textSize = 11f
                setTextColor(Color.parseColor("#9090B0"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(ctx).apply {
                text = "Use Selected"
                textSize = 9f
                setTextColor(Color.parseColor("#00D4FF"))
                background = ctx.getDrawable(R.drawable.bg_btn_secondary)
                setPadding(16, 0, 16, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 36)
                setOnClickListener {
                    glRun {
                        meshIdx = try { NativeLib.nativeGetSelectedMesh() } catch (_: Exception) { -1 }
                        activity?.runOnUiThread {
                            tvStatus?.text = if (meshIdx >= 0)
                                "✓ Targeting mesh #$meshIdx — tap model to apply brush"
                            else
                                "⚠ No mesh selected — long-press one in the viewport first"
                            tvStatus?.setTextColor(if (meshIdx >= 0)
                                Color.parseColor("#4CAF82") else Color.parseColor("#FF7043"))
                        }
                    }
                }
            })
        })

        root.addView(divider(ctx))

        // ── Status label ──────────────────────────────────────────────────────
        tvStatus = TextView(ctx).apply {
            text = "Tap \"Use Selected\" to choose a mesh, then tap the model to paint."
            textSize = 10f
            setTextColor(Color.parseColor("#9090B0"))
            background = ctx.getDrawable(R.drawable.bg_hint_card)
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 4, 14, 0) }
        }
        root.addView(tvStatus!!)

        return scroll
    }

    // ── Public API called by the touch handler in MainActivity/ModelGLSurfaceView ──
    /**
     * Called from the GL viewport touch handler when the user taps while this
     * fragment is open.  [sx],[sy] are raw touch coords; [sw],[sh] are the
     * surface dimensions.
     */
    fun applyAtScreenPoint(sx: Float, sy: Float, sw: Float, sh: Float) {
        val idx = meshIdx
        if (idx < 0) {
            activity?.runOnUiThread {
                tvStatus?.text = "⚠ Tap \"Use Selected\" first to choose a target mesh."
                tvStatus?.setTextColor(Color.parseColor("#FF7043"))
            }
            return
        }
        val r = radius; val s = strength; val mode = brushMode
        glRun {
            val pt = try { NativeLib.nativePickPoint(sx, sy, sw, sh) } catch (_: Exception) { null }
            if (pt == null || pt.size < 3) {
                activity?.runOnUiThread {
                    tvStatus?.text = "⚠ No surface hit — tap directly on the mesh"
                    tvStatus?.setTextColor(Color.parseColor("#FF7043"))
                }
                return@glRun
            }
            val ok = if (mode == MODE_SMOOTH)
                NativeLib.nativeApplySmooth(idx, pt[0], pt[1], pt[2], r, s)
            else
                NativeLib.nativeApplySculpt(idx, pt[0], pt[1], pt[2], r, s)

            activity?.runOnUiThread {
                tvStatus?.text = if (ok) "✓ Brush applied at (%.2f, %.2f, %.2f)".format(pt[0], pt[1], pt[2])
                                 else "✗ No vertices in range — try a larger radius"
                tvStatus?.setTextColor(if (ok) Color.parseColor("#4CAF82")
                                        else Color.parseColor("#9090B0"))
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun setMode(mode: Int) {
        brushMode = mode
        val accentBg    = requireContext().getDrawable(R.drawable.bg_btn_accent)
        val secondaryBg = requireContext().getDrawable(R.drawable.bg_btn_secondary)
        if (mode == MODE_SMOOTH) {
            btnSmooth?.background = accentBg;   btnSmooth?.setTextColor(Color.WHITE)
            btnSculpt?.background = secondaryBg; btnSculpt?.setTextColor(Color.parseColor("#9090B0"))
            tvStrength?.text = "0.50"
            strength = 0.5f
        } else {
            btnSculpt?.background = accentBg;   btnSculpt?.setTextColor(Color.WHITE)
            btnSmooth?.background = secondaryBg; btnSmooth?.setTextColor(Color.parseColor("#9090B0"))
            tvStrength?.text = "0.00 mm"
            strength = 0f
        }
    }

    private fun glRun(block: () -> Unit) =
        (activity as? MainActivity)?.glView?.queueEvent(block)

    private fun sectionLabel(ctx: android.content.Context, text: String) =
        TextView(ctx).apply {
            this.text = text; textSize = 9f; letterSpacing = 0.14f
            setTextColor(Color.parseColor("#00D4FF")); setPadding(20, 18, 20, 6)
        }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1A1A28"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    companion object {
        const val TAG = "BrushTool"
        const val MODE_SMOOTH = 0
        const val MODE_SCULPT = 1
        fun newInstance() = BrushToolFragment()
    }
}
