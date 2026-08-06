package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
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
 * Model & Material Editor — rebuilt from scratch on the AuraCAD UI Kit.
 *
 *  Organized in three clear tabs:
 *   TRANSFORM  → position / rotation / uniform scale (sliders with live values)
 *   MATERIAL   → color presets + custom RGB + per-mesh colour
 *   LIGHTING   → ambient / diffuse + wireframe + bounding box toggles
 *
 *  All native setters are wired through the GL thread; sliders push ONE
 *  undo snapshot at touch-down.
 */
class EditorPanelFragment : BottomSheetDialogFragment() {

    private enum class Tab { TRANSFORM, MATERIAL, LIGHTING }
    private var activeTab = Tab.TRANSFORM

    // Live model dimensions (mm), loaded async
    private var origW = 0f; private var origH = 0f; private var origD = 0f

    private var tvDims: TextView? = null
    private var tvOrigDims: TextView? = null

    // Transform state
    private var posX = 0f; private var posY = 0f; private var posZ = 0f
    private var rotX = 0f; private var rotY = 0f; private var rotZ = 0f
    private var scale = 1f

    // Slider control refs (for reseeding after reset)
    private val sliderRefs = mutableListOf<Pair<SeekBar, TextView>>()

    // Material state
    private var colR = 0.72f; private var colG = 0.72f; private var colB = 0.92f

    // Lighting state
    private var ambient = 0.3f; private var diffuse = 0.8f
    private var wireframe = false; private var bbox = false

    private val colorPresets = listOf(
        "Steel" to 0xFFB8C4D0.toInt(),
        "Silver" to 0xFFE8ECF2.toInt(),
        "Gold" to 0xFFD4AF37.toInt(),
        "Rose" to 0xFFE8A99C.toInt(),
        "Copper" to 0xFFB87333.toInt(),
        "Black" to 0xFF2A2A34.toInt(),
        "Cyan" to 0xFF00D4FF.toInt(),
        "Violet" to 0xFF9C7AFF.toInt()
    )

    private fun glRun(block: () -> Unit) =
        (activity as? MainActivity)?.glView?.queueEvent(block)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000); isFillViewport = true }
        val root = UISheetKit.sheetRoot(ctx)
        scroll.addView(root)

        root.addView(UISheetKit.handle(ctx))
        root.addView(UISheetKit.titleRow(ctx, "Model Editor", "REBUILT"))
        root.addView(UISheetKit.divider(ctx))

        // Dimensions summary card
        val dimsCard = UISheetKit.card(ctx, marginTopDp = 0).apply {
            addView(UISheetKit.cardTitle(ctx, "DIMENSIONS", "#4DD8FF"))
            addView(TextView(ctx).apply {
                text = "Loading dimensions…"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#F2F6FB"))
            }.also { tvDims = it })
            addView(TextView(ctx).apply {
                text = ""
                textSize = 10f
                setTextColor(Color.parseColor("#7A8BA3"))
                setPadding(0, UISheetKit.dp(ctx, 2), 0, 0)
            }.also { tvOrigDims = it })
        }
        root.addView(dimsCard)

        // ── Segmented tab row ─────────────────────────────────────────────────
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(UISheetKit.dp(ctx, 16), UISheetKit.dp(ctx, 14),
                UISheetKit.dp(ctx, 16), 0)
            val tabs = listOf(
                Tab.TRANSFORM to "Transform",
                Tab.MATERIAL to "Material",
                Tab.LIGHTING to "Lighting"
            )
            tabs.forEach { (tab, label) ->
                addView(Button(ctx).apply {
                    text = label
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#F2F6FB"))
                    background = ctx.getDrawable(R.drawable.bg_card_dark)
                    layoutParams = LinearLayout.LayoutParams(0,
                        UISheetKit.dp(ctx, 44), 1f).apply {
                        setMargins(UISheetKit.dp(ctx, 3), 0, UISheetKit.dp(ctx, 3), 0)
                    }
                    setOnClickListener { switchTab(tab) }
                })
            }
        })

        // ── Tab content containers ────────────────────────────────────────────
        val transformCard = UISheetKit.card(ctx, marginTopDp = 10)
        val materialCard = UISheetKit.card(ctx, marginTopDp = 10)
        val lightingCard = UISheetKit.card(ctx, marginTopDp = 10)

        // ── TRANSFORM ─────────────────────────────────────────────────────────
        sliderRefs.clear()
        transformCard.addView(UISheetKit.cardTitle(ctx, "TRANSFORM", "#4DD8FF"))

        // ── SET SIZE (mm) — fully connected to nativeSetScaleMM: typing exact
        //    W×H×D in millimetres rescales the whole model so ruler, exports and
        //    mesh info all report the new size. ───────────────────────────────
        transformCard.addView(UISheetKit.cardTitle(ctx, "SET SIZE (mm)", "#4CAF82"))
        transformCard.addView(UISheetKit.subText(ctx,
            "Enter the exact model size in millimetres — ruler, export and stats all follow.",
            "#7A8BA3", 10f))
        val etW = UISheetKit.inputField(ctx, "Width mm", numeric = true)
        val etH = UISheetKit.inputField(ctx, "Height mm", numeric = true)
        val etD = UISheetKit.inputField(ctx, "Depth mm", numeric = true)
        transformCard.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, UISheetKit.dp(ctx, 8), 0, 0)
            listOf(etW, etH, etD).forEachIndexed { i, et ->
                addView(et.apply {
                    layoutParams = LinearLayout.LayoutParams(0,
                        UISheetKit.dp(ctx, 46), 1f).apply {
                        setMargins(0, 0, if (i < 2) UISheetKit.dp(ctx, 6) else 0, 0)
                    }
                })
            }
        })
        transformCard.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, UISheetKit.dp(ctx, 8), 0, 0)
            addView(UISheetKit.secondaryButton(ctx, "↺  Original", "#FFC46B", 42).apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    UISheetKit.dp(ctx, 42), 1f).apply {
                    setMargins(0, 0, UISheetKit.dp(ctx, 6), 0)
                }
                setOnClickListener {
                    glRun {
                        NativeLib.nativePushUndoState()
                        NativeLib.nativeSetScaleMM(origW, origH, origD)
                        activity?.runOnUiThread {
                            etW.setText("%.2f".format(origW))
                            etH.setText("%.2f".format(origH))
                            etD.setText("%.2f".format(origD))
                            refreshDimsText()
                            notifyDimsChanged()
                        }
                    }
                }
            })
            addView(UISheetKit.primaryButton(ctx, "✓  Apply Size", 42).apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    UISheetKit.dp(ctx, 42), 1f).apply {
                    setMargins(UISheetKit.dp(ctx, 6), 0, 0, 0)
                }
                setOnClickListener { applyMmSize(etW, etH, etD) }
            })
        })
        transformCard.addView(axisRow(ctx, "Position  X", -2f, 2f, 0f, "#FFC46B") { v ->
            posX = v; pushAndSet { NativeLib.nativeSetTranslation(posX, posY, posZ) }
        })
        transformCard.addView(axisRow(ctx, "Position  Y", -2f, 2f, 0f, "#FFC46B") { v ->
            posY = v; pushAndSet { NativeLib.nativeSetTranslation(posX, posY, posZ) }
        })
        transformCard.addView(axisRow(ctx, "Position  Z", -2f, 2f, 0f, "#FFC46B") { v ->
            posZ = v; pushAndSet { NativeLib.nativeSetTranslation(posX, posY, posZ) }
        })
        transformCard.addView(axisRow(ctx, "Rotation  X", -180f, 180f, 0f, "#4DD8FF") { v ->
            rotX = v; pushAndSet { NativeLib.nativeSetRotation(rotX, rotY, rotZ) }
        })
        transformCard.addView(axisRow(ctx, "Rotation  Y", -180f, 180f, 0f, "#4DD8FF") { v ->
            rotY = v; pushAndSet { NativeLib.nativeSetRotation(rotX, rotY, rotZ) }
        })
        transformCard.addView(axisRow(ctx, "Rotation  Z", -180f, 180f, 0f, "#4DD8FF") { v ->
            rotZ = v; pushAndSet { NativeLib.nativeSetRotation(rotX, rotY, rotZ) }
        })
        transformCard.addView(axisRow(ctx, "Scale", 0.1f, 3f, 1f, "#A78BFA") { v ->
            scale = v
            glRun {
                val s = try { NativeLib.nativeGetModelSizeMM() } catch (_: Exception) { null }
                if (s != null && s.size >= 6) {
                    NativeLib.nativeSetScaleMM(s[0] * v, s[1] * v, s[2] * v)
                }
            }
        })
        transformCard.addView(Button(ctx).apply {
            text = "↺  Reset Transform"
            textSize = 12f
            setTextColor(Color.parseColor("#FF7A72"))
            background = ctx.getDrawable(R.drawable.bg_btn_danger)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, UISheetKit.dp(ctx, 46)
            ).apply { setMargins(0, UISheetKit.dp(ctx, 12), 0, 0) }
            setOnClickListener {
                posX = 0f; posY = 0f; posZ = 0f
                rotX = 0f; rotY = 0f; rotZ = 0f
                scale = 1f
                glRun {
                    NativeLib.nativeResetTransform()
                    NativeLib.nativePushUndoState()
                    activity?.runOnUiThread { reseedTransformSliders() }
                }
            }
        })
        root.addView(transformCard)

        // ── MATERIAL ──────────────────────────────────────────────────────────
        materialCard.addView(UISheetKit.cardTitle(ctx, "MATERIAL", "#FFC46B"))
        materialCard.addView(UISheetKit.subText(ctx,
            "Pick a preset or mix your own colour.", "#7A8BA3", 10f))
        // Preset swatches — two rows of four
        colorPresets.chunked(4).forEach { rowPresets ->
            materialCard.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, UISheetKit.dp(ctx, 8), 0, 0)
                rowPresets.forEach { (label, argb) ->
                    addView(Button(ctx).apply {
                        text = label
                        textSize = 10f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(if (android.graphics.Color.luminance(argb) > 0.5f)
                            Color.parseColor("#0B1320") else Color.parseColor("#F2F6FB"))
                        setBackgroundColor(argb)
                        layoutParams = LinearLayout.LayoutParams(0,
                            UISheetKit.dp(ctx, 42), 1f).apply {
                            setMargins(UISheetKit.dp(ctx, 3), 0,
                                UISheetKit.dp(ctx, 3), 0)
                        }
                        setOnClickListener {
                            colR = ((argb shr 16) and 0xFF) / 255f
                            colG = ((argb shr 8) and 0xFF) / 255f
                            colB = (argb and 0xFF) / 255f
                            glRun { NativeLib.nativeSetColor(colR, colG, colB) }
                        }
                    })
                }
            })
        }
        materialCard.addView(UISheetKit.spacer(ctx, 10))
        materialCard.addView(UISheetKit.subText(ctx, "Custom RGB", "#A9B8CC", 11f))
        materialCard.addView(channelRow(ctx, "R", 0f, 1f, colR, "#FF7A72") { v ->
            colR = v; glRun { NativeLib.nativeSetColor(colR, colG, colB) }
        })
        materialCard.addView(channelRow(ctx, "G", 0f, 1f, colG, "#4CAF82") { v ->
            colG = v; glRun { NativeLib.nativeSetColor(colR, colG, colB) }
        })
        materialCard.addView(channelRow(ctx, "B", 0f, 1f, colB, "#4DD8FF") { v ->
            colB = v; glRun { NativeLib.nativeSetColor(colR, colG, colB) }
        })
        root.addView(materialCard)

        // ── LIGHTING ──────────────────────────────────────────────────────────
        lightingCard.addView(UISheetKit.cardTitle(ctx, "LIGHTING", "#A78BFA"))
        lightingCard.addView(axisRow(ctx, "Ambient", 0f, 1f, 0.3f, "#A78BFA", pushUndo = false) { v ->
            ambient = v; glRun { NativeLib.nativeSetAmbient(ambient) }
        })
        lightingCard.addView(axisRow(ctx, "Diffuse", 0f, 1f, 0.8f, "#A78BFA", pushUndo = false) { v ->
            diffuse = v; glRun { NativeLib.nativeSetDiffuse(diffuse) }
        })
        lightingCard.addView(toggleRow(ctx, "Wireframe view", wireframe) { v ->
            wireframe = v; glRun { NativeLib.nativeSetWireframe(wireframe) }
        })
        lightingCard.addView(toggleRow(ctx, "Bounding box", bbox) { v ->
            bbox = v; glRun { NativeLib.nativeSetBoundingBox(bbox) }
        })
        root.addView(lightingCard)

        // Default tab content
        materialCard.visibility = View.GONE
        lightingCard.visibility = View.GONE
        tabContent = mapOf(
            Tab.TRANSFORM to transformCard,
            Tab.MATERIAL to materialCard,
            Tab.LIGHTING to lightingCard
        )

        // Async dimension load — prefill the mm fields with the CURRENT size
        (activity as? MainActivity)?.glView?.queueEvent {
            try {
                val s = NativeLib.nativeGetModelSizeMM()
                // nativeGetModelSizeMM returns [origW,origH,origD, curW,curH,curD]
                val ow = s[0]; val oh = s[1]; val od = s[2]
                val cw = s[3]; val ch = s[4]; val cd = s[5]
                activity?.runOnUiThread {
                    origW = ow; origH = oh; origD = od
                    tvDims?.text = "Current  %.1f × %.1f × %.1f mm".format(cw, ch, cd)
                    tvOrigDims?.text = "Original %.1f × %.1f × %.1f mm".format(ow, oh, od)
                    etW.setText("%.2f".format(cw))
                    etH.setText("%.2f".format(ch))
                    etD.setText("%.2f".format(cd))
                }
            } catch (_: Exception) {}
        }

        return scroll
    }

    /** Applies the typed W×H×D (mm) to the whole model via nativeSetScaleMM. */
    private fun applyMmSize(etW: EditText, etH: EditText, etD: EditText) {
        val w = etW.text.toString().toFloatOrNull()
        val h = etH.text.toString().toFloatOrNull()
        val d = etD.text.toString().toFloatOrNull()
        if (w == null || h == null || d == null || w <= 0f || h <= 0f || d <= 0f) {
            (activity as? MainActivity)?.toast("Enter valid positive sizes in mm")
            return
        }
        glRun {
            NativeLib.nativePushUndoState()
            NativeLib.nativeSetScaleMM(w, h, d)
            activity?.runOnUiThread {
                refreshDimsText()
                notifyDimsChanged()
            }
        }
    }

    /** Re-reads current/original size and refreshes the summary + mm fields. */
    private fun refreshDimsText() {
        (activity as? MainActivity)?.glView?.queueEvent {
            try {
                val s = NativeLib.nativeGetModelSizeMM()
                val cw = s[3]; val ch = s[4]; val cd = s[5]
                activity?.runOnUiThread {
                    tvDims?.text = "Current  %.1f × %.1f × %.1f mm".format(cw, ch, cd)
                    tvOrigDims?.text = "Original %.1f × %.1f × %.1f mm".format(s[0], s[1], s[2])
                }
            } catch (_: Exception) {}
        }
    }

    /** Lets every open tool (ruler, ring, mesh list) re-read the new size. */
    private fun notifyDimsChanged() {
        requireActivity().sendBroadcast(
            android.content.Intent(ACTION_DIMS_CHANGED).setPackage(requireActivity().packageName))
    }

    private var tabContent: Map<Tab, LinearLayout>? = null

    private fun switchTab(tab: Tab) {
        activeTab = tab
        tabContent?.forEach { (key, card) ->
            card.visibility = if (key == tab) View.VISIBLE else View.GONE
        }
    }

    /** Slider row with label, live value text and undo-on-touch. */
    private fun axisRow(ctx: android.content.Context, label: String,
                        min: Float, max: Float, init: Float, accentHex: String,
                        pushUndo: Boolean = true,
                        onChange: (Float) -> Unit): LinearLayout {
        val valueTv = TextView(ctx).apply {
            text = "%.2f".format(init)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(accentHex))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                UISheetKit.dp(ctx, 64), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UISheetKit.dp(ctx, 8), 0, 0)
            addView(UISheetKit.subText(ctx, label, "#A9B8CC", 12f).apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(valueTv)
        }
        val sb = UISheetKit.seekBar(ctx, accentHex)
        sb.progress = ((init - min) / (max - min) * 1000).toInt().coerceIn(0, 1000)
        sliderRefs.add(sb to valueTv)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(b: SeekBar) {
                if (pushUndo) glRun { NativeLib.nativePushUndoState() }
            }
            override fun onStopTrackingTouch(b: SeekBar) {}
            override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + p.toFloat() / 1000f * (max - min)
                valueTv.text = "%.2f".format(v)
                onChange(v)
            }
        })
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(sb)
        }
    }

    /** Reset slider visuals back to identity values.
     *  Sliders were added in order: posX,posY,posZ,rotX,rotY,rotZ,scale. */
    private fun reseedTransformSliders() {
        val identity = listOf(0f, 0f, 0f, 0f, 0f, 0f, 1f)
        sliderRefs.forEachIndexed { i, (sb, tv) ->
            if (i >= identity.size) return@forEachIndexed
            val v = identity[i]
            tv.text = "%.2f".format(v)
            val (min, max) = when (i) {
                6 -> 0.1f to 3f
                else -> if (i < 3) -2f to 2f else -180f to 180f
            }
            sb.progress = ((v - min) / (max - min) * 1000).toInt().coerceIn(0, 1000)
        }
    }

    /** Compact RGB channel row. */
    private fun channelRow(ctx: android.content.Context, label: String,
                           min: Float, max: Float, init: Float, accentHex: String,
                           onChange: (Float) -> Unit): LinearLayout {
        val valueTv = TextView(ctx).apply {
            text = "%.2f".format(init)
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(accentHex))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                UISheetKit.dp(ctx, 52), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val sb = UISheetKit.seekBar(ctx, accentHex)
        sb.progress = ((init - min) / (max - min) * 1000).toInt().coerceIn(0, 1000)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
            override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + p.toFloat() / 1000f * (max - min)
                valueTv.text = "%.2f".format(v)
                onChange(v)
            }
        })
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UISheetKit.dp(ctx, 6), 0, 0)
            addView(UISheetKit.subText(ctx, label, "#A9B8CC", 11f).apply {
                layoutParams = LinearLayout.LayoutParams(
                    UISheetKit.dp(ctx, 20), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(valueTv)
            addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(
                UISheetKit.dp(ctx, 6), 1) })
            addView(sb.apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        return row
    }

    private fun toggleRow(ctx: android.content.Context, label: String,
                          init: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UISheetKit.dp(ctx, 8), 0, 0)
            addView(UISheetKit.subText(ctx, label, "#A9B8CC", 12f).apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Switch(ctx).apply {
                isChecked = init
                thumbTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#4DD8FF"))
                trackTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#1C3A4E"))
                setOnCheckedChangeListener { _, v -> onChange(v) }
            })
        }

    /** Push undo once then run the setter. */
    private fun pushAndSet(block: () -> Unit) {
        glRun { NativeLib.nativePushUndoState(); block() }
    }

    companion object {
        const val TAG = "EditorPanel"
        const val ACTION_DIMS_CHANGED = "com.modelviewer3d.DIMS_CHANGED"
        fun newInstance() = EditorPanelFragment()
    }
}
