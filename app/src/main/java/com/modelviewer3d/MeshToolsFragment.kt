package com.modelviewer3d

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Transform Tool — replaces the old useless wrench panel.
 *
 * What this panel does
 * ────────────────────
 * Operates on the LONG-PRESS-SELECTED mesh (long-press anywhere on a mesh in
 * the viewport to pick it).  All controls here mutate ONE mesh in isolation:
 * the global scene transform, every other mesh, and the camera are untouched.
 *
 * Sections
 * ────────
 *   • Header        — shows current selection and refreshes on long-press
 *   • Move/Rotate   — per-mesh sliders, native push-undo on slider DOWN
 *   • Scale         — uniform scale multiplier
 *   • Reset         — restore identity for this mesh only
 *   • Mesh Stats    — area / volume / vert+tri counts (MeshLab inspired)
 *   • Decimation    — Garland-Heckbert QEM on selected mesh
 *   • Vertex Weld   — coalesce duplicates on selected mesh
 *   • Cleanup       — strip zero-area triangles on selected mesh
 *
 * Listens to MainActivity.ACTION_SELECTED_MESH_CHANGED so when the user
 * long-presses a different mesh while this sheet is open, the sliders snap
 * to the new mesh's current transform.
 */
class MeshToolsFragment : BottomSheetDialogFragment() {

    private var selectedIdx = -1

    // UI refs that need updating when selection changes
    private var tvSelected: TextView? = null
    private var tvStats:    TextView? = null

    private var sbRotX: SeekBar? = null
    private var sbRotY: SeekBar? = null
    private var sbRotZ: SeekBar? = null
    private var sbPosX: SeekBar? = null
    private var sbPosY: SeekBar? = null
    private var sbPosZ: SeekBar? = null
    private var sbScale: SeekBar? = null
    private var tvScaleVal: TextView? = null

    // ── Slider ranges (mirror EditorPanelFragment for consistency) ───────────
    private val ROT_MIN = -180f; private val ROT_MAX = 180f
    private val POS_MIN = -2f;   private val POS_MAX = 2f
    private val SCA_MIN = 0.1f;  private val SCA_MAX = 3.0f
    private val STEPS   = 1000

    // Suppress slider→native callbacks while we programmatically reseed sliders
    @Volatile private var suppressSliderCallbacks = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 64)
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        scroll.addView(root)

        // Handle bar
        root.addView(handle(ctx))
        // Title
        root.addView(titleRow(ctx, "🛠  Transform Tool", "PER-MESH"))
        root.addView(divider(ctx))

        // ── Selection indicator ───────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "SELECTED MESH"))
        tvSelected = TextView(ctx).apply {
            text = "Long-press a mesh in the viewport to select it."
            textSize = 11f
            setTextColor(Color.parseColor("#FF9B71"))
            background = ctx.getDrawable(R.drawable.bg_hint_card)
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 4, 14, 0) }
        }
        root.addView(tvSelected!!)
        root.addView(divider(ctx))

        // ── ROTATION (per-mesh) ───────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "ROTATION  (degrees, per-mesh)"))
        sbRotX = addAxisSlider(ctx, root, "X", ROT_MIN, ROT_MAX, 0f) { rx ->
            applyRotation(rx = rx)
        }
        sbRotY = addAxisSlider(ctx, root, "Y", ROT_MIN, ROT_MAX, 0f) { ry ->
            applyRotation(ry = ry)
        }
        sbRotZ = addAxisSlider(ctx, root, "Z", ROT_MIN, ROT_MAX, 0f) { rz ->
            applyRotation(rz = rz)
        }
        root.addView(divider(ctx))

        // ── POSITION (per-mesh) ───────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "POSITION  (per-mesh offset)"))
        sbPosX = addAxisSlider(ctx, root, "X", POS_MIN, POS_MAX, 0f) { px ->
            applyTranslation(px = px)
        }
        sbPosY = addAxisSlider(ctx, root, "Y", POS_MIN, POS_MAX, 0f) { py ->
            applyTranslation(py = py)
        }
        sbPosZ = addAxisSlider(ctx, root, "Z", POS_MIN, POS_MAX, 0f) { pz ->
            applyTranslation(pz = pz)
        }
        root.addView(divider(ctx))

        // ── SCALE (uniform, per-mesh) ─────────────────────────────────────────
        root.addView(sectionLabel(ctx, "SCALE  (uniform multiplier)"))
        tvScaleVal = TextView(ctx).apply {
            text = "× 1.00"; textSize = 10f
            setTextColor(Color.parseColor("#74869A"))
            setPadding(20, 2, 20, 0)
        }
        root.addView(tvScaleVal!!)
        sbScale = makeSlider(ctx, SCA_MIN, SCA_MAX, 1f) { factor ->
            applyUniformScale(factor)
            tvScaleVal?.text = "× %.2f".format(factor)
        }
        root.addView(sbScale!!)
        root.addView(divider(ctx))

        // ── RESET BUTTON ──────────────────────────────────────────────────────
        root.addView(actionButton(ctx, "↺  Reset This Mesh's Transform", "#FF9B71") {
            val idx = selectedIdx
            if (idx < 0) { toastNoSelection(); return@actionButton }
            val glv = (activity as? MainActivity)?.glView ?: return@actionButton
            glv.queueEvent {
                NativeLib.nativeResetMeshTransform(idx)
                activity?.runOnUiThread { reseedSlidersFromNative() }
            }
        })
        root.addView(divider(ctx))

        // ── MESH STATISTICS (MeshLab inspired) ────────────────────────────────
        root.addView(sectionLabel(ctx, "MESH STATISTICS"))
        tvStats = TextView(ctx).apply {
            text = "Tap Analyze to compute mesh statistics"
            textSize = 10f; setTextColor(Color.parseColor("#74869A"))
            background = ctx.getDrawable(R.drawable.bg_hint_card)
            setPadding(16, 14, 16, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 4, 14, 0) }
        }
        root.addView(tvStats!!)
        root.addView(actionButton(ctx, "📊  Analyze Selected Mesh", "#62E6FF") {
            val idx = selectedIdx
            if (idx < 0) { toastNoSelection(); return@actionButton }
            loadStats(idx)
        })
        root.addView(divider(ctx))

        // ── QEM DECIMATION (Garland-Heckbert) ─────────────────────────────────
        root.addView(sectionLabel(ctx, "QEM DECIMATION  (Garland-Heckbert)"))
        root.addView(infoLabel(ctx,
            "Reduces triangle count using Quadric Error Metrics. " +
            "Preserves shape — collapses low-cost edges first."))

        var decimPct = 0.5f
        val tvDecimInfo = TextView(ctx).apply {
            text = "Target: 50% of original faces"; textSize = 10f
            setTextColor(Color.parseColor("#74869A")); setPadding(20, 2, 20, 0)
        }
        val decimSlider = SeekBar(ctx).apply {
            this.max = 100
            progress = 50
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFB86B"))
            thumbTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFB86B"))
            setPadding(20, 8, 20, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) {}
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    decimPct = 0.05f + p / 100f * 0.90f
                    tvDecimInfo.text = "Target: ${(decimPct * 100).toInt()}% of original faces"
                }
            })
        }
        root.addView(decimSlider)
        root.addView(tvDecimInfo)

        root.addView(actionButton(ctx, "▶  Run Decimation on Selected Mesh", "#FFB86B") {
            val idx = selectedIdx
            if (idx < 0) { toastNoSelection(); return@actionButton }
            val glv = (activity as? MainActivity)?.glView ?: return@actionButton
            val pct = decimPct
            tvDecimInfo.text = "⏳ Running QEM decimation on mesh #$idx…"
            glv.queueEvent {
                val ok = NativeLib.nativeDecimateMesh(idx, pct)
                activity?.runOnUiThread {
                    tvDecimInfo.text = if (ok) "✓ Done — retap Analyze for new stats"
                                       else "✗ Decimation failed (too few faces?)"
                    if (ok) loadStats(idx)
                    activity?.sendBroadcast(
                        Intent("com.modelviewer3d.DIMS_CHANGED"))
                }
            }
        })
        root.addView(divider(ctx))

        // ── VERTEX WELD ───────────────────────────────────────────────────────
        root.addView(sectionLabel(ctx, "VERTEX WELD  (Duplicate Removal)"))
        root.addView(infoLabel(ctx,
            "Merges duplicate vertices within tolerance. " +
            "Fixes cracked seams and reduces vertex count."))

        val tvWeldResult = TextView(ctx).apply {
            text = ""; textSize = 10f; setTextColor(Color.parseColor("#4CAF82"))
            setPadding(20, 4, 20, 0)
        }
        root.addView(actionButton(ctx, "⊕  Weld Duplicates on Selected Mesh (0.01mm)", "#4CAF82") {
            val idx = selectedIdx
            if (idx < 0) { toastNoSelection(); return@actionButton }
            val glv = (activity as? MainActivity)?.glView ?: return@actionButton
            tvWeldResult.text = "⏳ Welding mesh #$idx…"
            glv.queueEvent {
                val n = NativeLib.nativeWeldVertices(idx, 0.01f)
                activity?.runOnUiThread {
                    tvWeldResult.text = if (n > 0) "✓ Merged $n duplicate vertices"
                                        else "✓ No duplicates found"
                    if (n > 0) loadStats(idx)
                }
            }
        })
        root.addView(tvWeldResult)
        root.addView(divider(ctx))

        // ── CLEANUP (degenerate face removal) ─────────────────────────────────
        root.addView(sectionLabel(ctx, "CLEANUP  (Degenerate Faces)"))
        root.addView(infoLabel(ctx,
            "Removes zero-area triangles and degenerate faces. " +
            "Improves mesh quality for 3D printing."))

        val tvCleanResult = TextView(ctx).apply {
            text = ""; textSize = 10f; setTextColor(Color.parseColor("#4CAF82"))
            setPadding(20, 4, 20, 0)
        }
        root.addView(actionButton(ctx, "✂  Remove Zero-Area Faces", "#FF9B71") {
            val idx = selectedIdx
            if (idx < 0) { toastNoSelection(); return@actionButton }
            val glv = (activity as? MainActivity)?.glView ?: return@actionButton
            tvCleanResult.text = "⏳ Cleaning mesh #$idx…"
            glv.queueEvent {
                val n = NativeLib.nativeRemoveZeroAreaFaces(idx)
                activity?.runOnUiThread {
                    tvCleanResult.text = if (n > 0) "✓ Removed $n degenerate faces"
                                         else "✓ No degenerate faces found"
                    if (n > 0) loadStats(idx)
                }
            }
        })
        root.addView(tvCleanResult)

        return scroll
    }

    // ── Lifecycle / broadcast wiring ──────────────────────────────────────────
    private val selectedMeshChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            selectedIdx = i.getIntExtra("idx", -1)
            refreshSelectionLabel()
            reseedSlidersFromNative()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(MainActivity.ACTION_SELECTED_MESH_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(selectedMeshChangedReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            requireContext().registerReceiver(selectedMeshChangedReceiver, filter)
        }
        // Pull whatever's currently selected at open time
        (activity as? MainActivity)?.glView?.queueEvent {
            val idx = try { NativeLib.nativeGetSelectedMesh() } catch (_: Exception) { -1 }
            activity?.runOnUiThread {
                selectedIdx = idx
                refreshSelectionLabel()
                reseedSlidersFromNative()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(selectedMeshChangedReceiver) } catch (_: Exception) {}
    }

    // ── Slider apply-to-native helpers ────────────────────────────────────────
    // Each apply-* reads the live SeekBar values for the OTHER axes and
    // forwards the full triple to native, since the JNI setter is "set all 3".
    private fun applyRotation(
        rx: Float? = null, ry: Float? = null, rz: Float? = null
    ) {
        val idx = selectedIdx; if (idx < 0) return
        val xv = rx ?: progressToValue(sbRotX, ROT_MIN, ROT_MAX)
        val yv = ry ?: progressToValue(sbRotY, ROT_MIN, ROT_MAX)
        val zv = rz ?: progressToValue(sbRotZ, ROT_MIN, ROT_MAX)
        glRun { NativeLib.nativeSetMeshRotation(idx, xv, yv, zv) }
    }

    private fun applyTranslation(
        px: Float? = null, py: Float? = null, pz: Float? = null
    ) {
        val idx = selectedIdx; if (idx < 0) return
        val xv = px ?: progressToValue(sbPosX, POS_MIN, POS_MAX)
        val yv = py ?: progressToValue(sbPosY, POS_MIN, POS_MAX)
        val zv = pz ?: progressToValue(sbPosZ, POS_MIN, POS_MAX)
        glRun { NativeLib.nativeSetMeshTranslation(idx, xv, yv, zv) }
    }

    private fun applyUniformScale(factor: Float) {
        val idx = selectedIdx; if (idx < 0) return
        // Per-mesh scale lives on top of the mesh's current local extent — we
        // route through nativeSetMeshScaleMM(w,h,d) by reading current size and
        // multiplying, so the existing mm-based path stays the source of truth.
        glRun {
            val sz = try { NativeLib.nativeGetMeshSizeMM(idx) } catch (_: Exception) { null }
            if (sz != null && sz.size >= 3) {
                NativeLib.nativeSetMeshScaleMM(idx, sz[0] * factor, sz[1] * factor, sz[2] * factor)
            }
        }
    }

    // ── Slider seeding from current native transform ──────────────────────────
    private fun reseedSlidersFromNative() {
        val idx = selectedIdx
        if (idx < 0) {
            // No selection — park sliders at neutral
            applyToSliders(0f, 0f, 0f, 0f, 0f, 0f, 1f)
            return
        }
        (activity as? MainActivity)?.glView?.queueEvent {
            val t = try { NativeLib.nativeGetMeshTransform(idx) } catch (_: Exception) { null }
            activity?.runOnUiThread {
                if (t != null && t.size >= 9) {
                    applyToSliders(t[0], t[1], t[2], t[3], t[4], t[5], (t[6] + t[7] + t[8]) / 3f)
                }
            }
        }
    }

    private fun applyToSliders(
        rx: Float, ry: Float, rz: Float,
        px: Float, py: Float, pz: Float,
        scaleAvg: Float
    ) {
        suppressSliderCallbacks = true
        try {
            sbRotX?.progress = valueToProgress(rx, ROT_MIN, ROT_MAX)
            sbRotY?.progress = valueToProgress(ry, ROT_MIN, ROT_MAX)
            sbRotZ?.progress = valueToProgress(rz, ROT_MIN, ROT_MAX)
            sbPosX?.progress = valueToProgress(px, POS_MIN, POS_MAX)
            sbPosY?.progress = valueToProgress(py, POS_MIN, POS_MAX)
            sbPosZ?.progress = valueToProgress(pz, POS_MIN, POS_MAX)
            sbScale?.progress = valueToProgress(scaleAvg.coerceIn(SCA_MIN, SCA_MAX), SCA_MIN, SCA_MAX)
            tvScaleVal?.text = "× %.2f".format(scaleAvg)
        } finally { suppressSliderCallbacks = false }
    }

    private fun refreshSelectionLabel() {
        val idx = selectedIdx
        if (idx < 0) {
            tvSelected?.text = "Long-press a mesh in the viewport to select it."
            tvSelected?.setTextColor(Color.parseColor("#FF9B71"))
            return
        }
        (activity as? MainActivity)?.glView?.queueEvent {
            val name = try { NativeLib.nativeGetMeshName(idx) } catch (_: Exception) { "?" }
            activity?.runOnUiThread {
                tvSelected?.text = "✓ Editing: $name (mesh #$idx)"
                tvSelected?.setTextColor(Color.parseColor("#4CAF82"))
            }
        }
    }

    private fun loadStats(meshIdx: Int) {
        val glv = (activity as? MainActivity)?.glView ?: return
        glv.queueEvent {
            val s = NativeLib.nativeGetMeshStats(meshIdx)
            activity?.runOnUiThread {
                if (s.size >= 9) {
                    val watertight = s[8] > 0.5f
                    tvStats?.text = buildString {
                        appendLine("Surface Area:  %.2f mm²".format(s[0]))
                        appendLine("Volume:        %.2f mm³".format(s[1]))
                        appendLine("Bounding Box:  %.2f × %.2f × %.2f mm".format(s[2], s[3], s[4]))
                        appendLine("Vertices:      ${s[5].toInt()}")
                        appendLine("Triangles:     ${s[6].toInt()}")
                        appendLine("Edges:         ${s[7].toInt()}")
                        append("Watertight:    ${if (watertight) "✓ Yes (printable)" else "✗ No (open mesh)"}")
                    }
                    tvStats?.setTextColor(
                        if (watertight) Color.parseColor("#4CAF82") else Color.WHITE)
                }
            }
        }
    }

    private fun toastNoSelection() {
        Toast.makeText(requireContext(),
            "No mesh selected — long-press one in the viewport first.",
            Toast.LENGTH_SHORT).show()
    }

    // ── Slider builder helpers ────────────────────────────────────────────────
    private fun addAxisSlider(
        ctx: Context, parent: LinearLayout, axis: String,
        min: Float, max: Float, init: Float,
        onChange: (Float) -> Unit
    ): SeekBar {
        parent.addView(TextView(ctx).apply {
            text = axis; textSize = 11f
            setTextColor(Color.parseColor("#A8B6C7")); setPadding(20, 8, 20, 2)
        })
        val sb = makeSlider(ctx, min, max, init, onChange)
        parent.addView(sb)
        return sb
    }

    private fun makeSlider(
        ctx: Context, min: Float, max: Float, init: Float,
        onChange: (Float) -> Unit
    ): SeekBar = SeekBar(ctx).apply {
        this.max = STEPS
        progress = ((init - min) / (max - min) * STEPS).toInt().coerceIn(0, STEPS)
        progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#62E6FF"))
        thumbTintList    = android.content.res.ColorStateList.valueOf(Color.parseColor("#62E6FF"))
        setPadding(20, 4, 20, 4)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || suppressSliderCallbacks) return
                if (selectedIdx < 0) return
                onChange(min + p.toFloat() / STEPS * (max - min))
            }
            // Single undo entry per drag — mirror EditorPanelFragment pattern.
            override fun onStartTrackingTouch(b: SeekBar) {
                if (selectedIdx < 0) return
                glRun { NativeLib.nativePushUndoState() }
            }
            override fun onStopTrackingTouch(b: SeekBar) {}
        })
    }

    private fun progressToValue(sb: SeekBar?, min: Float, max: Float): Float {
        val p = sb?.progress ?: 0
        return min + p.toFloat() / STEPS * (max - min)
    }
    private fun valueToProgress(v: Float, min: Float, max: Float) =
        ((v - min) / (max - min) * STEPS).toInt().coerceIn(0, STEPS)

    private fun glRun(block: () -> Unit) =
        (activity as? MainActivity)?.glView?.queueEvent(block)

    // ── Decorative builders (kept identical to old fragment for visual parity) ─
    private fun handle(ctx: Context) = LinearLayout(ctx).apply {
        gravity = android.view.Gravity.CENTER_HORIZONTAL; setPadding(0, 14, 0, 0)
        addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#607286"))
            layoutParams = LinearLayout.LayoutParams(48, 4)
        })
    }

    private fun titleRow(ctx: Context, t: String, badge: String) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 14, 20, 6)
            addView(TextView(ctx).apply {
                text = t; textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(ctx).apply {
                text = badge; textSize = 8f; letterSpacing = 0.12f
                setTextColor(Color.parseColor("#FFB86B"))
                background = ctx.getDrawable(R.drawable.bg_pill); setPadding(10, 3, 10, 3)
            })
        }

    private fun sectionLabel(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 9f; letterSpacing = 0.14f
        setTextColor(Color.parseColor("#62E6FF")); setPadding(20, 18, 20, 6)
    }

    private fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#243445"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun actionButton(
        ctx: Context, text: String, accentHex: String, onClick: () -> Unit
    ) = Button(ctx).apply {
        this.text = text; textSize = 11f
        setTextColor(Color.parseColor(accentHex))
        background = ctx.getDrawable(R.drawable.bg_card_dark)
        setPadding(24, 0, 24, 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 48
        ).apply { setMargins(14, 8, 14, 0) }
        setOnClickListener { onClick() }
    }

    private fun infoLabel(ctx: Context, msg: String) = TextView(ctx).apply {
        text = msg; textSize = 9f; setTextColor(Color.parseColor("#607286"))
        setPadding(20, 2, 20, 4)
    }

    companion object {
        const val TAG = "MeshTools"
        fun newInstance() = MeshToolsFragment()
    }
}
