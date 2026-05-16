package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

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

    // Prevents TextWatcher re-entry when programmatically updating sibling fields
    private var suppressTextChange = false

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
                // s[0..2] = original mm, s[3..5] = current (scaled) mm
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
                setBackgroundColor(Color.parseColor("#404058"))
                layoutParams = LinearLayout.LayoutParams(48, 4)
            })
        })

        // Title
        root.addView(TextView(ctx).apply {
            text = "✏️  Model Editor"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(20, 14, 20, 14)
        })
        root.addView(divider(ctx))

        fun sectionTitle(t: String) = TextView(ctx).apply {
            text = t; textSize = 9f; letterSpacing = 0.14f
            setTextColor(Color.parseColor("#00D4FF"))
            setPadding(20, 22, 20, 8)
        }
        fun fieldLabel(t: String) = TextView(ctx).apply {
            text = t; textSize = 11f
            setTextColor(Color.parseColor("#9090B0"))
            setPadding(20, 8, 20, 2)
        }
        // Sliders snapshot the current transform ONCE on touch DOWN (not every
        // tick).  Continuous drags now produce exactly one undoable entry,
        // instead of ~60 entries/second flooding the 50-deep stack and
        // dropping older history within the first second of any drag.
        fun slider(min: Float, max: Float, init: Float, cb: (Float) -> Unit): SeekBar {
            val steps = 1000
            return SeekBar(ctx).apply {
                this.max = steps
                progress = ((init - min) / (max - min) * steps).toInt().coerceIn(0, steps)
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
                thumbTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
                setPadding(20, 4, 20, 4)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) cb(min + p.toFloat() / steps * (max - min))
                    }
                    override fun onStartTrackingTouch(b: SeekBar) {
                        glRun { NativeLib.nativePushUndoState() }
                    }
                    override fun onStopTrackingTouch(b: SeekBar) {}
                })
            }
        }
        fun toggle(label: String, checked: Boolean, cb: (Boolean) -> Unit) =
            Switch(ctx).apply {
                text = label; isChecked = checked
                setTextColor(Color.WHITE); textSize = 12f
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
                trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A3D50"))
                setPadding(20, 10, 20, 10)
                setOnCheckedChangeListener { _, v -> cb(v) }
            }

        // ── ROTATION ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("ROTATION  (degrees)"))
        listOf(
            Triple("X", { v: Float -> rotX = v }, rotX),
            Triple("Y", { v: Float -> rotY = v }, rotY),
            Triple("Z", { v: Float -> rotZ = v }, rotZ)
        ).forEach { (ax, assign, init) ->
            root.addView(fieldLabel(ax))
            root.addView(slider(-180f, 180f, init) { v ->
                assign(v); glRun { NativeLib.nativeSetRotation(rotX, rotY, rotZ) }
            })
        }
        root.addView(divider(ctx))

        // ── POSITION ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("POSITION"))
        listOf(
            Triple("X", { v: Float -> posX = v }, posX),
            Triple("Y", { v: Float -> posY = v }, posY),
            Triple("Z", { v: Float -> posZ = v }, posZ)
        ).forEach { (ax, assign, init) ->
            root.addView(fieldLabel(ax))
            root.addView(slider(-5f, 5f, init) { v ->
                assign(v); glRun { NativeLib.nativeSetTranslation(posX, posY, posZ) }
            })
        }
        root.addView(divider(ctx))

        // ── DIMENSIONS ───────────────────────────────────────────────────────
        root.addView(sectionTitle("DIMENSIONS  (mm)"))
        val tvOrig = TextView(ctx).apply {
            text = "Original: loading…"
            textSize = 10f; setTextColor(Color.parseColor("#505070")); setPadding(20, 0, 20, 0)
        }
        tvOrigDims = tvOrig
        root.addView(tvOrig)
        root.addView(toggle("Uniform Scale (lock ratio)", false) { checked -> uniformScale = checked })

        // Build one dimension input row
        fun mmInputRow(axLabel: String): EditText {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(20, 6, 20, 0)
            }
            row.addView(TextView(ctx).apply {
                text = axLabel; textSize = 12f
                setTextColor(Color.parseColor("#9090B0"))
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
                text = " mm"; textSize = 11f; setTextColor(Color.parseColor("#505070"))
            })
            root.addView(row)

            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // Bail out if we triggered this change ourselves
                    if (suppressTextChange) return
                    // Bail out if model dimensions haven't loaded yet
                    // (prevents setScaleMM(value, 0, 0) which collapses the model)
                    if (origWmm < 0.001f || origHmm < 0.001f || origDmm < 0.001f) return
                    val v = s?.toString()?.toFloatOrNull() ?: return
                    if (v < 0.001f) return

                    if (uniformScale && origWmm > 0.001f && origHmm > 0.001f && origDmm > 0.001f) {
                        // Ratio based on the axis that was actually edited
                        val origForAxis = when (axLabel) {
                            "W" -> origWmm
                            "H" -> origHmm
                            else -> origDmm   // "D"
                        }
                        if (origForAxis < 0.001f) return
                        val ratio = v / origForAxis
                        val nw = origWmm * ratio
                        val nh = origHmm * ratio
                        val nd = origDmm * ratio
                        // Update the OTHER two fields silently
                        when (axLabel) {
                            "W" -> { silentSet(etH, nh); silentSet(etD, nd) }
                            "H" -> { silentSet(etW, nw); silentSet(etD, nd) }
                            "D" -> { silentSet(etW, nw); silentSet(etH, nh) }
                        }
                        glRun { NativeLib.nativeSetScaleMM(nw, nh, nd) }
                    } else {
                        // Non-uniform: read each axis independently
                        // Use origMM fallback (safe because guard above ensures origWmm>0)
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

        // Reset-to-original button
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(20, 10, 20, 0)
            addView(Button(ctx).apply {
                text = "Reset to Original"
                textSize = 11f; setTextColor(Color.parseColor("#00D4FF"))
                background = ctx.getDrawable(R.drawable.bg_btn_accent)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 40)
                setPadding(20, 0, 20, 0)
                setOnClickListener {
                    if (origWmm > 0.001f) {
                        silentSet(etW, origWmm); silentSet(etH, origHmm); silentSet(etD, origDmm)
                        glRun { NativeLib.nativeSetScaleMM(origWmm, origHmm, origDmm) }
                    }
                }
            })
        })
        root.addView(divider(ctx))

        // ── GEOMETRY ─────────────────────────────────────────────────────────
        root.addView(sectionTitle("GEOMETRY"))
        val mirrorRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(20, 8, 20, 0)
        }
        listOf(
            "Flip X" to { NativeLib.nativeMirrorX() },
            "Flip Y" to { NativeLib.nativeMirrorY() },
            "Flip Z" to { NativeLib.nativeMirrorZ() }
        ).forEach { (lbl, action) ->
            mirrorRow.addView(Button(ctx).apply {
                text = lbl; textSize = 11f
                setTextColor(Color.parseColor("#9090B0"))
                background = ctx.getDrawable(R.drawable.bg_card_dark)
                setOnClickListener { glRun { action() } }
                layoutParams = LinearLayout.LayoutParams(0, 40, 1f).apply { setMargins(0, 0, 8, 0) }
            })
        }
        root.addView(mirrorRow)
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(20, 10, 20, 0)
            addView(Button(ctx).apply {
                text = "↺  Reset All Transforms"
                textSize = 11f; setTextColor(Color.parseColor("#FF7043"))
                background = ctx.getDrawable(R.drawable.bg_btn_danger)
                setPadding(20, 0, 20, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 40)
                setOnClickListener {
                    rotX = 0f; rotY = 0f; rotZ = 0f
                    posX = 0f; posY = 0f; posZ = 0f
                    // Reset BOTH global AND per-mesh transforms (one undo entry)
                    glRun { NativeLib.nativeResetAllTransforms() }
                    // Reload the dimension fields after transform reset
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
        root.addView(divider(ctx))

        // ── COLOR ─────────────────────────────────────────────────────────────
        root.addView(sectionTitle("MODEL COLOR"))
        root.addView(fieldLabel("Red"))
        root.addView(slider(0f, 1f, colR) { v ->
            colR = v; glRun { NativeLib.nativeSetColor(colR, colG, colB) }
        }.apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252"))
            thumbTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252"))
        })
        root.addView(fieldLabel("Green"))
        root.addView(slider(0f, 1f, colG) { v ->
            colG = v; glRun { NativeLib.nativeSetColor(colR, colG, colB) }
        }.apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF82"))
            thumbTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF82"))
        })
        root.addView(fieldLabel("Blue"))
        root.addView(slider(0f, 1f, colB) { v ->
            colB = v; glRun { NativeLib.nativeSetColor(colR, colG, colB) }
        }.apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4FC3F7"))
            thumbTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor("#4FC3F7"))
        })
        root.addView(divider(ctx))

        // ── LIGHTING ──────────────────────────────────────────────────────────
        root.addView(sectionTitle("LIGHTING"))
        root.addView(fieldLabel("Ambient"))
        root.addView(slider(0f, 1f, ambient) { v -> ambient = v; glRun { NativeLib.nativeSetAmbient(v) } })
        root.addView(fieldLabel("Diffuse"))
        root.addView(slider(0f, 1f, diffuse) { v -> diffuse = v; glRun { NativeLib.nativeSetDiffuse(v) } })
        root.addView(divider(ctx))

        // ── DISPLAY ───────────────────────────────────────────────────────────
        root.addView(sectionTitle("DISPLAY"))
        root.addView(toggle("Wireframe Mode", false) { on -> glRun { NativeLib.nativeSetWireframe(on) } })
        root.addView(toggle("Bounding Box",   false) { on -> glRun { NativeLib.nativeSetBoundingBox(on) } })

        return scroll
    }

    /**
     * Sets [et]'s text to [value] without triggering its TextWatcher.
     * Uses a re-entrancy flag rather than remove/add watcher approach.
     */
    private fun silentSet(et: EditText?, value: Float) {
        et ?: return
        val txt = "%.2f".format(value)
        if (et.text?.toString() != txt) {
            suppressTextChange = true
            et.setText(txt)
            suppressTextChange = false
        }
    }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1A1A28"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun glRun(block: () -> Unit) =
        (activity as? MainActivity)?.glView?.queueEvent(block)

    companion object {
        const val TAG = "EditorPanel"
        const val ACTION_DIMS_CHANGED = "com.modelviewer3d.DIMS_CHANGED"
        fun newInstance() = EditorPanelFragment()
    }

    // Refresh dimensions whenever ring tool or any other tool changes model geometry
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
}
