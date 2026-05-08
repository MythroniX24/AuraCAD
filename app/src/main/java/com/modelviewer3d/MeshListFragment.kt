package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.concurrent.CountDownLatch

/**
 * Mesh Separation Panel
 * 
 * Separation uses plain Thread (NOT coroutines/lifecycleScope) to avoid
 * lifecycle cancellation issues when the dialog is interacted with.
 * 
 * Flow:
 *   1. Background Thread → nativePerformSeparationCPU()  (heavy CPU)
 *   2. GL Thread via queueEvent → nativePerformSeparationGPU()  (GPU upload)
 *   3. Main Thread via Handler.post → update UI
 */
class MeshListFragment : BottomSheetDialogFragment() {

    private var isSeparated   = false
    private var meshCount     = 0
    private var selectedIdx   = -1
    private val visibilityMap = mutableMapOf<Int, Boolean>()

    private var listContainer: LinearLayout? = null
    private var btnSeparate:   Button?       = null
    private var progressBar:   android.widget.ProgressBar? = null
    private var tvProgress:    TextView?     = null
    private var separateCard:  View?         = null
    private var tvIslandTitle: TextView?     = null

    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var separationRunning = false

    private val meshColors = listOf(
        "#00D4FF","#FF7043","#4CAF82","#FFD54F",
        "#AB47BC","#EC407A","#26C6DA","#D4E157"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000) }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 48)
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

        // Title row
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(20, 14, 20, 4)
        }
        titleRow.addView(TextView(ctx).apply {
            text = "⬡  Mesh Separation"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val tvCount = TextView(ctx).apply {
            text = "–"; textSize = 11f
            setTextColor(Color.parseColor("#00D4FF"))
            background = ctx.getDrawable(R.drawable.bg_pill)
            setPadding(14, 4, 14, 4)
        }
        tvIslandTitle = tvCount
        titleRow.addView(tvCount)
        root.addView(titleRow)

        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1A1A28"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, 10, 0, 0) }
        })

        val sepCard = buildSeparateCard(ctx)
        separateCard = sepCard
        root.addView(sepCard)

        val lc = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 4, 14, 8)
        }
        listContainer = lc
        root.addView(lc)

        refreshState(ctx)
        return scroll
    }

    override fun onDestroyView() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    // ── Separate card ─────────────────────────────────────────────────────────
    private fun buildSeparateCard(ctx: android.content.Context): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundResource(R.drawable.bg_card_dark)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(14, 16, 14, 8) }
        }

        val headRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        headRow.addView(TextView(ctx).apply {
            text = "⬡"; textSize = 28f
            layoutParams = LinearLayout.LayoutParams(52, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = android.view.Gravity.CENTER
        })
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(ctx).apply {
            text = "Separate Mesh Islands"; textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        texts.addView(TextView(ctx).apply {
            text = "Split disconnected geometry into individual selectable groups"
            textSize = 11f; setTextColor(Color.parseColor("#606080")); setPadding(0, 4, 0, 0)
        })
        headRow.addView(texts)
        card.addView(headRow)

        card.addView(TextView(ctx).apply {
            text = "⚠ Large models may take a minute"
            textSize = 10f; setTextColor(Color.parseColor("#805050")); setPadding(0, 12, 0, 12)
        })

        val btn = Button(ctx).apply {
            text = "⬡  Separate Meshes"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#00D4FF"))
            background = ctx.getDrawable(R.drawable.bg_btn_accent)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52)
            setOnClickListener { if (!separationRunning) startSeparation() }
        }
        btnSeparate = btn
        card.addView(btn)

        // Progress bar (hidden until separation starts)
        val pb = android.widget.ProgressBar(ctx, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 6
            ).apply { setMargins(0, 8, 0, 0) }
            visibility = View.GONE
        }
        progressBar = pb; card.addView(pb)

        val tvProg = TextView(ctx).apply {
            text = ""; textSize = 10f
            setTextColor(Color.parseColor("#606080")); setPadding(0, 4, 0, 0)
            visibility = View.GONE
        }
        tvProgress = tvProg; card.addView(tvProg)

        return card
    }

    // ── SEPARATION — plain Thread, no coroutines, no lifecycle dependency ─────
    private fun startSeparation() {
        val main = activity as? MainActivity ?: return
        separationRunning = true
        btnSeparate?.isEnabled = false
        btnSeparate?.text = "⏳  Separating…"

        // Progress polling on main thread via Handler
        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 0
        tvProgress?.visibility = View.VISIBLE
        tvProgress?.text = "Starting…"

        val progressRunnable = object : Runnable {
            override fun run() {
                if (!separationRunning) return
                val p = try { NativeLib.nativeGetSeparationProgress() } catch (_: Exception) { 0 }
                progressBar?.progress = p
                tvProgress?.text = "Step %d of 6 — %d%%".format(
                    when { p < 12 -> 1; p < 45 -> 2; p < 65 -> 3; p < 72 -> 4; p < 78 -> 5; else -> 6 }, p)
                btnSeparate?.text = "⏳  Separating… $p%%"
                uiHandler.postDelayed(this, 200)
            }
        }
        uiHandler.postDelayed(progressRunnable, 200)

        // CPU work on plain background Thread — no lifecycle dependency
        Thread({
            var cpuOk = false
            try {
                cpuOk = NativeLib.nativePerformSeparationCPU()
            } catch (e: Exception) {
                android.util.Log.e("MeshList", "CPU separation error: ${e.message}")
            }

            if (!cpuOk) {
                uiHandler.post {
                    uiHandler.removeCallbacks(progressRunnable)
                    separationRunning = false
                    btnSeparate?.text = "⬡  Separate Meshes"
                    btnSeparate?.isEnabled = true
                    toast("Model has 1 connected mesh — nothing to separate")
                }
                return@Thread
            }

            // GPU upload on GL thread, block this background thread until done
            val latch = CountDownLatch(1)
            var gpuOk = false
            var mc = 0
            main.glView.queueEvent {
                try {
                    gpuOk = NativeLib.nativePerformSeparationGPU()
                    mc = NativeLib.nativeGetMeshCount()
                } catch (e: Exception) {
                    android.util.Log.e("MeshList", "GPU separation error: ${e.message}")
                } finally {
                    latch.countDown()
                }
            }

            try { latch.await() } catch (_: Exception) {}

            val finalGpuOk = gpuOk
            val finalMc = mc

            // Back to main thread for UI update
            uiHandler.post {
                uiHandler.removeCallbacks(progressRunnable)
                separationRunning = false

                if (finalGpuOk && finalMc > 1) {
                    isSeparated = true
                    meshCount = finalMc
                    separateCard?.visibility = View.GONE
                    for (i in 0 until meshCount)
                        if (!visibilityMap.containsKey(i)) visibilityMap[i] = true
                    tvIslandTitle?.text = "$finalMc"
                    if (isAdded) buildMeshList(requireContext())
                    main.updateStatusBar()
                    progressBar?.visibility = View.GONE
                    tvProgress?.visibility = View.GONE
                    toast("✅ $finalMc mesh islands separated!")
                } else {
                    btnSeparate?.text = "⬡  Separate Meshes"
                    btnSeparate?.isEnabled = true
                    progressBar?.visibility = View.GONE
                    tvProgress?.visibility = View.GONE
                    toast("1 connected mesh — no islands to separate")
                }
            }
        }, "MeshSeparationThread").start()
    }

    private fun refreshState(ctx: android.content.Context) {
        Thread({
            val sep = try { NativeLib.nativeIsSeparated() } catch (_: Exception) { false }
            val mc  = try { NativeLib.nativeGetMeshCount() } catch (_: Exception) { 0 }
            uiHandler.post {
                isSeparated = sep
                meshCount = mc
                if (isSeparated && meshCount > 1) {
                    separateCard?.visibility = View.GONE
                    for (i in 0 until meshCount)
                        if (!visibilityMap.containsKey(i)) visibilityMap[i] = true
                    tvIslandTitle?.text = "$meshCount"
                    if (isAdded) buildMeshList(ctx)
                } else {
                    separateCard?.visibility = View.VISIBLE
                    listContainer?.removeAllViews()
                    tvIslandTitle?.text = "–"
                }
            }
        }, "MeshRefreshThread").start()
    }

    // ── Mesh list ─────────────────────────────────────────────────────────────
    private fun buildMeshList(ctx: android.content.Context) {
        listContainer?.removeAllViews()
        if (meshCount == 0) {
            listContainer?.addView(TextView(ctx).apply {
                text = "No model loaded"
                textSize = 13f; setTextColor(Color.parseColor("#606080"))
                gravity = android.view.Gravity.CENTER; setPadding(0, 24, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            return
        }
        listContainer?.addView(TextView(ctx).apply {
            text = "MESH ISLANDS"; textSize = 9f; letterSpacing = 0.14f
            setTextColor(Color.parseColor("#505070")); setPadding(6, 10, 0, 6)
        })
        for (i in 0 until meshCount) {
            listContainer?.addView(buildMeshRow(ctx, i))
            listContainer?.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 6)
            })
        }
    }

    private fun buildMeshRow(ctx: android.content.Context, idx: Int): View {
        val name     = NativeLib.nativeGetMeshName(idx)
        val isVis    = visibilityMap[idx] ?: true
        val isSel    = (idx == selectedIdx)
        val colorHex = meshColors[idx % meshColors.size]

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.getDrawable(
                if (isSel) R.drawable.bg_card_selected else R.drawable.bg_card_dark)
            setPadding(14, 12, 14, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        header.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor(colorHex))
            layoutParams = LinearLayout.LayoutParams(10, 10).apply { setMargins(0, 0, 10, 0) }
        })

        header.addView(TextView(ctx).apply {
            text = name; textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (isSel) Color.parseColor("#00D4FF") else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val tvVerts = TextView(ctx).apply {
            text = "…"; textSize = 10f
            setTextColor(Color.parseColor("#505070")); setPadding(0, 0, 8, 0)
        }
        header.addView(tvVerts)
        Thread({
            val vc = try { NativeLib.nativeGetMeshVertexCount(idx) } catch (_: Exception) { 0 }
            uiHandler.post {
                tvVerts.text = when {
                    vc >= 1_000_000 -> "%.1fMv".format(vc / 1_000_000f)
                    vc >= 1_000     -> "%.1fKv".format(vc / 1_000f)
                    else            -> "${vc}v"
                }
            }
        }).start()

        val btnVis = ImageButton(ctx).apply {
            setImageResource(if (isVis) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
            setColorFilter(if (isVis) Color.parseColor("#00D4FF") else Color.parseColor("#404060"))
            background = null
            layoutParams = LinearLayout.LayoutParams(36, 36).apply { setMargins(0, 0, 4, 0) }
            setOnClickListener {
                val nv = !(visibilityMap[idx] ?: true)
                visibilityMap[idx] = nv
                (activity as? MainActivity)?.glView?.queueEvent { NativeLib.nativeSetMeshVisible(idx, nv) }
                setImageResource(if (nv) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
                setColorFilter(if (nv) Color.parseColor("#00D4FF") else Color.parseColor("#404060"))
            }
        }
        header.addView(btnVis)

        val btnDel = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(Color.parseColor("#FF7043"))
            background = null
            layoutParams = LinearLayout.LayoutParams(36, 36)
            setOnClickListener {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("Delete Mesh")
                    .setMessage("Delete \"$name\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        (activity as? MainActivity)?.glView?.queueEvent { NativeLib.nativeDeleteMesh(idx) }
                        meshCount--
                        visibilityMap.remove(idx)
                        if (selectedIdx == idx) selectedIdx = -1
                        else if (selectedIdx > idx) selectedIdx--
                        tvIslandTitle?.text = "$meshCount"
                        if (isAdded) buildMeshList(ctx)
                        (activity as? MainActivity)?.updateStatusBar()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        header.addView(btnDel)
        card.addView(header)

        val tvSize = TextView(ctx).apply {
            textSize = 10f; setTextColor(Color.parseColor("#505070")); setPadding(20, 4, 0, 0)
        }
        Thread({
            val s = try { NativeLib.nativeGetMeshSizeMM(idx) } catch (_: Exception) { FloatArray(3) { 0f } }
            uiHandler.post { tvSize.text = "%.1f × %.1f × %.1f mm".format(s[0], s[1], s[2]) }
        }).start()
        card.addView(tvSize)

        if (isSel) card.addView(buildResizeEditor(ctx, idx))

        card.setOnClickListener {
            selectedIdx = if (selectedIdx == idx) -1 else idx
            (activity as? MainActivity)?.glView?.queueEvent { NativeLib.nativeSelectMesh(selectedIdx) }
            if (isAdded) buildMeshList(ctx)
        }
        return card
    }

    private fun buildResizeEditor(ctx: android.content.Context, idx: Int): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(4, 14, 4, 4)
        }
        container.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#252538"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { setMargins(0, 0, 0, 12) }
        })
        container.addView(TextView(ctx).apply {
            text = "RESIZE MESH  (mm)"; textSize = 9f; letterSpacing = 0.14f
            setTextColor(Color.parseColor("#00D4FF")); setPadding(0, 0, 0, 8)
        })

        // Get size non-blocking - use cached 50f as default until loaded
        var origW = 50f; var origH = 50f; var origD = 50f
        val etW = makeField(ctx, container, "W", 50f)
        val etH = makeField(ctx, container, "H", 50f)
        val etD = makeField(ctx, container, "D", 50f)

        // Load actual size asynchronously
        Thread({
            val s = try { NativeLib.nativeGetMeshSizeMM(idx) } catch (_: Exception) { FloatArray(3){50f} }
            origW = s[0]; origH = s[1]; origD = s[2]
            uiHandler.post {
                etW.setText("%.2f".format(s[0]))
                etH.setText("%.2f".format(s[1]))
                etD.setText("%.2f".format(s[2]))
            }
        }).start()

        var lockRatio = true
        container.addView(Switch(ctx).apply {
            text = "Lock Aspect Ratio"; isChecked = lockRatio
            setTextColor(Color.parseColor("#AAAACC")); textSize = 11f; setPadding(0, 0, 0, 8)
            setOnCheckedChangeListener { _, v -> lockRatio = v }
        })

        container.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 10, 0, 0)
            addView(Button(ctx).apply {
                text = "Apply"; textSize = 11f
                setTextColor(Color.parseColor("#00D4FF"))
                background = ctx.getDrawable(R.drawable.bg_btn_accent)
                layoutParams = LinearLayout.LayoutParams(0, 44, 1f).apply { setMargins(0, 0, 8, 0) }
                setOnClickListener {
                    val w = etW.text.toString().toFloatOrNull() ?: origW
                    val h = etH.text.toString().toFloatOrNull() ?: origH
                    val d = etD.text.toString().toFloatOrNull() ?: origD
                    val ow = origW; val oh = origH; val od = origD
                    (activity as? MainActivity)?.glView?.queueEvent {
                        if (lockRatio && ow > 0) {
                            val r = w / ow
                            NativeLib.nativeSetMeshScaleMM(idx, w, oh * r, od * r)
                        } else {
                            NativeLib.nativeSetMeshScaleMM(idx, w, h, d)
                        }
                    }
                    toast("Resized")
                }
            })
            addView(Button(ctx).apply {
                text = "Reset"; textSize = 11f
                setTextColor(Color.parseColor("#FF7043"))
                background = ctx.getDrawable(R.drawable.bg_btn_danger)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44)
                setPadding(16, 0, 16, 0)
                setOnClickListener {
                    val ow = origW; val oh = origH; val od = origD
                    etW.setText("%.2f".format(ow))
                    etH.setText("%.2f".format(oh))
                    etD.setText("%.2f".format(od))
                    (activity as? MainActivity)?.glView?.queueEvent {
                        NativeLib.nativeSetMeshScaleMM(idx, ow, oh, od)
                    }
                }
            })
        })
        return container
    }

    private fun makeField(ctx: android.content.Context, container: LinearLayout,
                          label: String, initVal: Float): EditText {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
        }
        row.addView(TextView(ctx).apply {
            text = label; textSize = 11f; setTextColor(Color.parseColor("#808099"))
            layoutParams = LinearLayout.LayoutParams(26, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val et = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.2f".format(initVal))
            setTextColor(Color.WHITE); textSize = 13f
            background = ctx.getDrawable(R.drawable.bg_input_field)
            setPadding(12, 8, 12, 8)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(et)
        row.addView(TextView(ctx).apply {
            text = " mm"; textSize = 10f; setTextColor(Color.parseColor("#505070"))
        })
        container.addView(row)
        return et
    }

    private fun toast(msg: String) =
        activity?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }

    companion object {
        const val TAG = "MeshList"
        fun newInstance() = MeshListFragment()
    }
}
