package com.modelviewer3d

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
 * Model Editor v2 — clean card-based redesign.
 *
 * All native transform logic is preserved 1:1 (slider undo snapshot on touch
 * down, uniform-scale mm math, colour/lighting/display setters, dimension
 * refresh via ACTION_DIMS_CHANGED). Only the visual layout changed and the
 * redundant field labels / loose rows were removed.
 */
class EditorPanelFragment : BottomSheetDialogFragment() {

    private var rotX = 0f; private var rotY = 0f; private var rotZ = 0f
    private var posX = 0f; private var posY = 0f; private var posZ = 0f

    // Real dimensions loaded async from GL thread (0 = not yet loaded)
    private var origWmm = 0f; private var origHmm = 0f; private var origDmm = 0f
    private var curWmm  = 0f; private var curHmm  = 0f; private var curDmm  = 0f

    private var colR = 0.72f; private var colG = 0.72f; private var colB = 0.92f
    private var ambient = 0.3f; private var diffuse = 0.8f
    private var uniformScale = false

    private var etW: EditText? = null
    private var etH: EditText? = null
    private var etD: EditText? = null
    private var tvOrigDims: TextView? = null
    private var rgbR: SeekBar? = null
    private var rgbG: SeekBar? = null
    private var rgbB: SeekBar? = null

    private var suppressTextChange = false

    /** Preset material colours: [label, argb] */
    private val colorPresets = listOf(
        "Steel"  to 0xFFB8C4D0.toInt(),
        "Silver" to 0xFFE8ECF2.toInt(),
        "Gold"   to 0xFFD4AF37.toInt(),
        "Rose"   to 0xFFE8A99C.toInt(),
        "Copper" to 0xFFB87333.toInt(),
        "Black"  to 0xFF2A2A34.toInt(),
        "Cyan"   to 0xFF00D4FF.toInt(),
        "Purple" to 0xFF9C7AFF.toInt()
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()

        // Async fetch of real dimensions from GL thread
        (activity as? MainActivity)?.glView?.queueEvent {
            try {
                val s = NativeLib.nativeGetModelSizeMM()
                val ow = s[0]; val oh = s[1]; val od = s[2]
                val cw = s[3]; val ch = s[4]; val cd = s[5]
                activity?.runOnUiThread {
                    origWmm = ow; origHmm = oh; origDmm = od
                    curWmm  = cw; curHmm  = ch; curDmm  = cd
                    tvOrigDims?.text = "Original: %.1f × %.1f × %.1f mm".format(ow, oh, od)
                    silentSet(etW, cw); silentSet(etH, ch); silentSet(etD, cd)
                }
            } catch (_: Exception) {}
        }

        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 56)
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        scroll.addView(root)

        // Handle bar
        root.addView(LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, 14, 0, 0)
            addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#607286"))
                layoutParams = LinearLayout.LayoutParams(48, 4)
            })
        })

        // Header
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 14, 20, 6)
            addView(TextView(ctx).apply {
                text = "🎨  Model Editor"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = "v2"
                textSize = 9f; letterSpacing = 0.12f
                setTextColor(Color.parseColor("#62E6FF"))
                background = ctx.getDrawable(R.drawable.bg_pill); setPadding(10, 3, 10, 3)
            })
        })
        root.addView(divider(ctx))

        // ── TRANSFORM card ────────────────────────────────────────────────────
        root.addView(card(ctx) { card ->
            card.addView(cardTitle(ctx, "TRANSFORM", "#62E6FF"))

            fun sliderRow(
                label: String, unit: String, min: Float, max: Float,
                init: Float, accent: Int,
                assign: (Float) -> Unit,
                apply: (Float) -> Unit
            ) {
                val valueTv = TextView(ctx).apply {
                    text = "%.1f".format(init); textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(accent)
                    gravity = android.view.Gravity.END
                }
                val steps = 1000
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(16, 10, 16, 0)
                    addView(TextView(ctx).apply {
                        text = label; textSize = 12f
                        setTextColor(Color.parseColor("#A8B6C7"))
                        layoutParams = LinearLayout.LayoutParams(20, LinearLayout.LayoutParams.WRAP_CONTENT)
                    })
                    addView(TextView(ctx).apply {
                        text = unit; textSize = 10f
                        setTextColor(Color.parseColor("#607286"))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setPadding(6,0,0,0) }
                    })
                    addView(valueTv)
                }
                card.addView(row)
                card.addView(SeekBar(ctx).apply {
                    this.max = steps
                    progress = ((init - min) / (max - min) * steps).toInt().coerceIn(0, steps)
                    progressTintList = android.content.res.ColorStateList.valueOf(accent)
                    thumbTintList    = android.content.res.ColorStateList.valueOf(accent)
                    setPadding(16, 4, 16, 0)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            val v = min + p.toFloat() / steps * (max - min)
                            assign(v)
                            valueTv.text = "%.1f".format(v)
                            apply(v)
                        }
                        override fun onStartTrackingTouch(b: SeekBar) {
                            glRun { NativeLib.nativePushUndoState() }
                        }
                        override fun onStopTrackingTouch(b: SeekBar) {}
                    })
                })
            }

            sliderRow("X", "°", -180f, 180f, rotX, Color.parseColor("#FF5252"),
                { rotX = it }, { glRun { NativeLib.nativeSetRotation(rotX, rotY, rotZ) } })
            sliderRow("Y", "°", -180f, 180f, rotY, Color.parseColor("#4CAF82"),
                { rotY = it }, { glRun { NativeLib.nativeSetRotation(rotX, rotY, rotZ) } })
            sliderRow("Z", "°", -180f, 180f, rotZ, Color.parseColor("#4FC3F7"),
                { rotZ = it }, { glRun { NativeLib.nativeSetRotation(rotX, rotY, rotZ) } })

            card.addView(space(ctx, 6))
            card.addView(cardSubTitle(ctx, "POSITION  (world units)"))

            sliderRow("X", "", -5f, 5f, posX, Color.parseColor("#FF5252"),
                { posX = it }, { glRun { NativeLib.nativeSetTranslation(posX, posY, posZ) } })
            sliderRow("Y", "", -5f, 5f, posY, Color.parseColor("#4CAF82"),
                { posY = it }, { glRun { NativeLib.nativeSetTranslation(posX, posY, posZ) } })
            sliderRow("Z", "", -5f, 5f, posZ, Color.parseColor("#4FC3F7"),
                { posZ = it }, { glRun { NativeLib.nativeSetTranslation(posX, posY, posZ) } })
        })
        root.addView(space(ctx, 8))

        // ── GEOMETRY row (flip + reset all) ───────────────────────────────────
        root.addView(card(ctx) { card ->
            card.addView(cardTitle(ctx, "GEOMETRY", "#62E6FF"))
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(16, 8, 16, 8)
            }
            listOf(
                "Flip X" to { NativeLib.nativeMirrorX() },
                "Flip Y" to { NativeLib.nativeMirrorY() },
                "Flip Z" to { NativeLib.nativeMirrorZ() }
            ).forEach { (lbl, action) ->
                row.addView(Button(ctx).apply {
                    text = lbl; textSize = 11f
                    setTextColor(Color.parseColor("#A8B6C7"))
                    background = ctx.getDrawable(R.drawable.bg_card_dark)
                    setOnClickListener { glRun { action() } }
                    layoutParams = LinearLayout.LayoutParams(0, 40, 1f).apply { setMargins(0, 0, 8, 0) }
                })
            }
            card.addView(row)
            card.addView(Button(ctx).apply {
                text = "↺  Reset All Transforms"
                textSize = 11f; setTextColor(Color.parseColor("#FF9B71"))
                background = ctx.getDrawable(R.drawable.bg_btn_danger)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 42
                ).apply { setMargins(16, 0, 16, 14) }
                setOnClickListener {
                    rotX = 0f; rotY = 0f; rotZ = 0f
                    posX = 0f; posY = 0f; posZ = 0f
                    glRun { NativeLib.nativeResetAllTransforms() }
                    (activity as? MainActivity)?.glView?.queueEvent {
                        try {
                            val s = NativeLib.nativeGetModelSizeMM()
                            val cw = s[3]; val ch = s[4]; val cd = s[5]
                            activity?.runOnUiThread {
                                curWmm = cw; curHmm = ch; curDmm = cd
                                silentSet(etW, cw); silentSet(etH, ch); silentSet(etD, cd)
                            }
                        } catch (_: Exception) {}
                    }
                }
            })
        })
        root.addView(space(ctx, 8))

        // ── SIZE card ────────────────────────────────────────────────────────
        root.addView(card(ctx) { card ->
            card.addView(cardTitle(ctx, "SIZE  (mm)", "#FFB86B"))

            val tvOrig = TextView(ctx).apply {
                text = "Original: loading…"
                textSize = 10f; setTextColor(Color.parseColor("#607286")); setPadding(16, 0, 16, 0)
            }
            tvOrigDims = tvOrig
            card.addView(tvOrig)

            card.addView(Switch(ctx).apply {
                text = "Uniform Scale (lock ratio)"; isChecked = false
                setTextColor(Color.WHITE); textSize = 12f
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#62E6FF"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A3D50"))
                setPadding(16, 8, 16, 8)
                setOnCheckedChangeListener { _, v -> uniformScale = v }
            })

            fun mmInputRow(axLabel: String): EditText {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(16, 5, 16, 0)
                }
                row.addView(TextView(ctx).apply {
                    text = axLabel; textSize = 12f
                    setTextColor(Color.parseColor("#A8B6C7"))
                    layoutParams = LinearLayout.LayoutParams(26, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                val et = EditText(ctx).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText("…")
                    setTextColor(Color.WHITE); textSize = 14f
                    background = ctx.getDrawable(R.drawable.bg_input_field)
                    setPadding(14, 10, 14, 10)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(et)
                row.addView(TextView(ctx).apply {
                    text = " mm"; textSize = 11f; setTextColor(Color.parseColor("#607286"))
                })
                card.addView(row)

                et.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (suppressTextChange) return
                        if (origWmm < 0.001f || origHmm < 0.001f || origDmm < 0.001f) return
                        val v = s?.toString()?.toFloatOrNull() ?: return
                        if (v < 0.001f) return

                        if (uniformScale && origWmm > 0.001f && origHmm > 0.001f && origDmm > 0.001f) {
                            val origForAxis = when (axLabel) {
                                "W" -> origWmm
                                "H" -> origHmm
                                else -> origDmm
                            }
                            if (origForAxis < 0.001f) return
                            val ratio = v / origForAxis
                            val nw = origWmm * ratio
                            val nh = origHmm * ratio
                            val nd = origDmm * ratio
                            when (axLabel) {
                                "W" -> { silentSet(etH, nh); silentSet(etD, nd) }
                                "H" -> { silentSet(etW, nw); silentSet(etD, nd) }
                                "D" -> { silentSet(etW, nw); silentSet(etH, nh) }
                            }
                            glRun { NativeLib.nativeSetScaleMM(nw, nh, nd) }
                        } else {
                            val wv = etW?.text?.toString()?.toFloatOrNull().takeIf { it != null && it > 0.001f } ?: origWmm
                            val hv = etH?.text?.toString()?.toFloatOrNull().takeIf { it != null && it > 0.001f } ?: origHmm
                            val dv = etD?.text?.toString()?.toFloatOrNull().takeIf { it != null && it > 0.001f } ?: origDmm
                            glRun { NativeLib.nativeSetScaleMM(wv, hv, dv) }
                        }
                    }
                })
                return et
            }

            etW = mmInputRow("W")
            etH = mmInputRow("H")
            etD = mmInputRow("D")

            card.addView(Button(ctx).apply {
                text = "Reset to Original"
                textSize = 11f; setTextColor(Color.parseColor("#62E6FF"))
                background = ctx.getDrawable(R.drawable.bg_btn_accent)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 40
                ).apply { setMargins(16, 12, 16, 14) }
                setOnClickListener {
                    if (origWmm > 0.001f) {
                        silentSet(etW, origWmm); silentSet(etH, origHmm); silentSet(etD, origDmm)
                        glRun { NativeLib.nativeSetScaleMM(origWmm, origHmm, origDmm) }
                    }
                }
            })
        })
        root.addView(space(ctx, 8))

        // ── APPEARANCE card ───────────────────────────────────────────────────
        root.addView(card(ctx) { card ->
            card.addView(cardTitle(ctx, "APPEARANCE", "#A78BFA"))

            // Colour presets
            val swatchRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 6, 16, 0)
            }
            fun updateSwatches(selected: Int) {
                for (i in 0 until swatchRow.childCount) {
                    val sw = swatchRow.getChildAt(i) as View
                    sw.alpha = if (i == selected) 1f else 0.45f
                    sw.scaleX = if (i == selected) 1.15f else 1f
                    sw.scaleY = if (i == selected) 1.15f else 1f
                }
            }
            colorPresets.forEachIndexed { idx, (_, argb) ->
                swatchRow.addView(View(ctx).apply {
                    setBackgroundColor(argb)
                    setOnClickListener {
                        colR = Color.red(argb) / 255f
                        colG = Color.green(argb) / 255f
                        colB = Color.blue(argb) / 255f
                        glRun { NativeLib.nativeSetColor(colR, colG, colB) }
                        updateSwatches(idx)
                        setRgbSliders()
                    }
                    layoutParams = LinearLayout.LayoutParams(0, 40, 1f).apply { setMargins(0, 0, 8, 0) }
                })
            }
            card.addView(swatchRow)
            card.addView(cardSubTitle(ctx, "RGB"))

            fun rgbSlider(accent: Int, get: () -> Float, set: (Float) -> Unit): SeekBar {
                val steps = 1000
                return SeekBar(ctx).apply {
                    this.max = steps
                    progress = (get() * steps).toInt().coerceIn(0, steps)
                    progressTintList = android.content.res.ColorStateList.valueOf(accent)
                    thumbTintList    = android.content.res.ColorStateList.valueOf(accent)
                    setPadding(16, 2, 16, 0)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                            if (fromUser) { set(p / steps.toFloat()); glRun { NativeLib.nativeSetColor(colR, colG, colB) } }
                        }
                        override fun onStartTrackingTouch(b: SeekBar) {}
                        override fun onStopTrackingTouch(b: SeekBar) {}
                    })
                }
            }
            val sr = rgbSlider(Color.parseColor("#FF5252"), { colR }, { colR = it })
            rgbR = sr; card.addView(sr)
            val sg = rgbSlider(Color.parseColor("#4CAF82"), { colG }, { colG = it })
            rgbG = sg; card.addView(sg)
            val sb2 = rgbSlider(Color.parseColor("#4FC3F7"), { colB }, { colB = it })
            rgbB = sb2; card.addView(sb2)

            card.addView(cardSubTitle(ctx, "LIGHTING"))
            fun lightSlider(accent: Int, get: () -> Float, set: (Float) -> Unit, apply: (Float) -> Unit) {
                val steps = 1000
                card.addView(SeekBar(ctx).apply {
                    this.max = steps
                    progress = (get() * steps).toInt().coerceIn(0, steps)
                    progressTintList = android.content.res.ColorStateList.valueOf(accent)
                    thumbTintList    = android.content.res.ColorStateList.valueOf(accent)
                    setPadding(16, 2, 16, 0)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                            if (fromUser) { val v = p / steps.toFloat(); set(v); apply(v) }
                        }
                        override fun onStartTrackingTouch(b: SeekBar) {}
                        override fun onStopTrackingTouch(b: SeekBar) {}
                    })
                })
            }
            lightSlider(Color.parseColor("#FFD54F"), { ambient }, { ambient = it }, { glRun { NativeLib.nativeSetAmbient(it) } })
            lightSlider(Color.parseColor("#FFD54F"), { diffuse }, { diffuse = it }, { glRun { NativeLib.nativeSetDiffuse(it) } })
        })
        root.addView(space(ctx, 8))

        // ── DISPLAY card ──────────────────────────────────────────────────────
        root.addView(card(ctx) { card ->
            card.addView(cardTitle(ctx, "DISPLAY", "#4CAF82"))
            card.addView(Switch(ctx).apply {
                text = "Wireframe Mode"; isChecked = false
                setTextColor(Color.WHITE); textSize = 12f
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#62E6FF"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A3D50"))
                setPadding(16, 6, 16, 4)
                setOnCheckedChangeListener { _, on -> glRun { NativeLib.nativeSetWireframe(on) } }
            })
            card.addView(Switch(ctx).apply {
                text = "Bounding Box"; isChecked = false
                setTextColor(Color.WHITE); textSize = 12f
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#62E6FF"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A3D50"))
                setPadding(16, 6, 16, 14)
                setOnCheckedChangeListener { _, on -> glRun { NativeLib.nativeSetBoundingBox(on) } }
            })
        })
        root.addView(space(ctx, 10))

        return scroll
    }

    /** Push current colR/G/B into the three RGB sliders. */
    private fun setRgbSliders() {
        val steps = 1000
        rgbR?.progress = (colR * steps).toInt().coerceIn(0, steps)
        rgbG?.progress = (colG * steps).toInt().coerceIn(0, steps)
        rgbB?.progress = (colB * steps).toInt().coerceIn(0, steps)
    }

    // ── Shared UI helpers ─────────────────────────────────────────────────────
    private fun card(ctx: android.content.Context, build: (LinearLayout) -> Unit): LinearLayout {
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_dark)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 0, 14, 0) }
        }
        build(c)
        return c
    }

    private fun cardTitle(ctx: android.content.Context, t: String, accent: String) = TextView(ctx).apply {
        text = t; textSize = 9f; letterSpacing = 0.14f
        setTextColor(Color.parseColor(accent)); setPadding(16, 14, 16, 2)
    }

    private fun cardSubTitle(ctx: android.content.Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 10f
        setTextColor(Color.parseColor("#74869A")); setPadding(16, 4, 16, 0)
    }

    private fun space(ctx: android.content.Context, dp: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp)
    }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#243445"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun glRun(block: () -> Unit) =
        (activity as? MainActivity)?.glView?.queueEvent(block)

    private fun silentSet(et: EditText?, value: Float) {
        et ?: return
        val txt = "%.2f".format(value)
        if (et.text?.toString() != txt) {
            suppressTextChange = true
            et.setText(txt)
            suppressTextChange = false
        }
    }

    // ── Refresh dimensions whenever ring tool or any other tool changes model geometry
    private val dimsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            refreshDimensions()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_DIMS_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(dimsChangedReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(dimsChangedReceiver, filter)
        }
        refreshDimensions()
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(dimsChangedReceiver) } catch (_: Exception) {}
    }

    /** Re-fetch current model dimensions from GL thread and update fields */
    fun refreshDimensions() {
        (activity as? MainActivity)?.glView?.queueEvent {
            try {
                val s = NativeLib.nativeGetModelSizeMM()
                val ow = s[0]; val oh = s[1]; val od = s[2]
                val cw = s[3]; val ch = s[4]; val cd = s[5]
                activity?.runOnUiThread {
                    origWmm = ow; origHmm = oh; origDmm = od
                    curWmm  = cw; curHmm  = ch; curDmm  = cd
                    tvOrigDims?.text = "Original: %.1f × %.1f × %.1f mm".format(ow, oh, od)
                    silentSet(etW, cw); silentSet(etH, ch); silentSet(etD, cd)
                }
            } catch (_: Exception) {}
        }
    }

    companion object {
        const val TAG = "EditorPanel"
        const val ACTION_DIMS_CHANGED = "com.modelviewer3d.DIMS_CHANGED"
        fun newInstance() = EditorPanelFragment()
    }
}
