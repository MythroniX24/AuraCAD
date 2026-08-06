package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.concurrent.CountDownLatch

/**
 * Mesh Separation — rebuilt as a dedicated professional screen.
 *
 * Flow:
 *   1. Info card explains what separation does
 *   2. Settings card — island filter (drop tiny dust islands below N faces)
 *   3. Action — big "Separate" button with live progress (step + %)
 *   4. Result — mesh count summary + "Combine back into one mesh" when done
 *
 * Separation runs on a plain background Thread (no lifecycle dependency) and
 * uploads on the GL thread via queueEvent, exactly like the previous engine.
 */
class SeparationFragment : BottomSheetDialogFragment() {

    private var btnSeparate: Button? = null
    private var progressBar: ProgressBar? = null
    private var tvProgress: TextView? = null
    private var tvStatus: TextView? = null
    private var resultCard: View? = null
    private var tvResultTitle: TextView? = null
    private var tvResultDetail: TextView? = null
    private var btnCombine: Button? = null
    private var settingsCard: View? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    @Volatile private var separationRunning = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx).apply { setBackgroundColor(0x00000000); isFillViewport = true }
        val root = UISheetKit.sheetRoot(ctx)
        scroll.addView(root)

        root.addView(UISheetKit.handle(ctx))
        root.addView(UISheetKit.titleRow(ctx, "Mesh Separation", "PRO"))
        root.addView(UISheetKit.divider(ctx))

        // ── Info card ─────────────────────────────────────────────────────────
        root.addView(UISheetKit.card(ctx, marginTopDp = 0).apply {
            addView(UISheetKit.cardTitle(ctx, "WHAT IT DOES", "#4DD8FF"))
            addView(UISheetKit.subText(ctx,
                "Splits disconnected geometry into individual meshes — each island " +
                "becomes a selectable part you can move, hide, resize or delete on its own.",
                "#7A8BA3", 11f))
            addView(UISheetKit.subText(ctx,
                "Perfect for cleaning scans, separating interlocking parts, or preparing print-ready models.",
                "#5A6B85", 10f))
        })

        // ── Settings card ─────────────────────────────────────────────────────
        val settings = UISheetKit.card(ctx, marginTopDp = 10).apply {
            addView(UISheetKit.cardTitle(ctx, "ISLAND FILTER", "#FFC46B"))
            addView(UISheetKit.subText(ctx,
                "Skip tiny dust islands so they don't clutter your mesh list.",
                "#7A8BA3", 10f))
            val tvMin = TextView(ctx).apply {
                text = "1 face  ·  keep everything"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#FFC46B"))
                setPadding(0, UISheetKit.dp(ctx, 4), 0, 0)
            }
            addView(tvMin)
            addView(SeekBar(ctx).apply {
                max = 499
                progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#FFC46B"))
                thumbTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#FFC46B"))
                setPadding(0, UISheetKit.dp(ctx, 6), 0, 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val n = p + 1
                        tvMin.text = if (n == 1) "1 face  ·  keep everything"
                                     else "$n faces  ·  drops tiny islands"
                        (activity as? MainActivity)?.glView?.queueEvent {
                            NativeLib.nativeSetSeparationMinFaces(n)
                        }
                    }
                    override fun onStartTrackingTouch(b: SeekBar) {}
                    override fun onStopTrackingTouch(b: SeekBar) {}
                })
            })
        }
        settingsCard = settings
        root.addView(settings)

        // ── Action card ──────────────────────────────────────────────────────
        root.addView(UISheetKit.card(ctx, marginTopDp = 10).apply {
            addView(Button(ctx).apply {
                text = "⬡  Separate Meshes"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#0B1320"))
                background = ctx.getDrawable(R.drawable.bg_btn_accent)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, UISheetKit.dp(ctx, 52))
                setOnClickListener { if (!separationRunning) startSeparation() }
            }.also { btnSeparate = it })

            addView(ProgressBar(ctx, null,
                android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = 0
                progressTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#4DD8FF"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, UISheetKit.dp(ctx, 5)
                ).apply { setMargins(0, UISheetKit.dp(ctx, 14), 0, 0) }
                visibility = View.GONE
            }.also { progressBar = it })

            addView(TextView(ctx).apply {
                text = ""
                textSize = 11f
                setTextColor(Color.parseColor("#7A8BA3"))
                gravity = Gravity.CENTER
                setPadding(0, UISheetKit.dp(ctx, 8), 0, 0)
                visibility = View.GONE
            }.also { tvProgress = it })
        })

        // ── Status line ───────────────────────────────────────────────────────
        root.addView(UISheetKit.infoText(ctx, "",
            "#5A6B85", 10f).also { tvStatus = it })

        // ── Result card (hidden until done) ───────────────────────────────────
        val result = UISheetKit.card(ctx, marginTopDp = 4).apply {
            visibility = View.GONE
            addView(UISheetKit.cardTitle(ctx, "RESULT", "#4CAF82"))
            addView(TextView(ctx).apply {
                text = ""
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#4CAF82"))
                setPadding(0, UISheetKit.dp(ctx, 2), 0, 0)
            }.also { tvResultTitle = it })
            addView(UISheetKit.subText(ctx, "", "#7A8BA3", 11f).also { tvResultDetail = it })
            addView(UISheetKit.spacer(ctx, 4))
            addView(UISheetKit.secondaryButton(ctx, "⊕  Combine Back into One Mesh",
                "#FFC46B").apply {
                setOnClickListener { combineAll() }
            }.also { btnCombine = it })
        }
        resultCard = result
        root.addView(result)

        refreshState()
        return scroll
    }

    override fun onDestroyView() {
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    // ── Separation ────────────────────────────────────────────────────────────
    private fun startSeparation() {
        val main = activity as? MainActivity ?: return
        separationRunning = true
        settingsCard?.visibility = View.GONE
        btnSeparate?.isEnabled = false
        btnSeparate?.text = "⏳  Separating…"
        progressBar?.visibility = View.VISIBLE
        progressBar?.progress = 0
        tvProgress?.visibility = View.VISIBLE
        tvProgress?.text = "Starting…"
        tvStatus?.text = ""

        val progressRunnable = object : Runnable {
            override fun run() {
                if (!separationRunning) return
                val p = try { NativeLib.nativeGetSeparationProgress() } catch (_: Exception) { 0 }
                progressBar?.progress = p
                tvProgress?.text = stepLabel(p)
                btnSeparate?.text = "⏳  Separating… $p%"
                uiHandler.postDelayed(this, 200)
            }
        }
        uiHandler.postDelayed(progressRunnable, 200)

        Thread({
            var cpuOk = false
            try { cpuOk = NativeLib.nativePerformSeparationCPU() }
            catch (e: Exception) { android.util.Log.e("Separation", "CPU error: ${e.message}") }

            if (!cpuOk) {
                uiHandler.post {
                    uiHandler.removeCallbacks(progressRunnable)
                    separationRunning = false
                    btnSeparate?.text = "⬡  Separate Meshes"
                    btnSeparate?.isEnabled = true
                    settingsCard?.visibility = View.VISIBLE
                    progressBar?.visibility = View.GONE
                    tvProgress?.visibility = View.GONE
                    tvStatus?.text = "Model has 1 connected mesh — nothing to separate."
                }
                return@Thread
            }

            val latch = CountDownLatch(1)
            var gpuOk = false
            var mc = 0
            main.glView.queueEvent {
                try {
                    gpuOk = NativeLib.nativePerformSeparationGPU()
                    mc = NativeLib.nativeGetMeshCount()
                } catch (e: Exception) {
                    android.util.Log.e("Separation", "GPU error: ${e.message}")
                } finally { latch.countDown() }
            }
            try { latch.await() } catch (_: Exception) {}

            val ok = gpuOk; val count = mc
            uiHandler.post {
                uiHandler.removeCallbacks(progressRunnable)
                separationRunning = false
                progressBar?.visibility = View.GONE
                tvProgress?.visibility = View.GONE
                if (ok && count > 1) {
                    btnSeparate?.visibility = View.GONE
                    tvResultTitle?.text = "✅ $count mesh islands separated!"
                    tvResultDetail?.text =
                        "Each island is now its own mesh — open the Mesh List to manage them."
                    resultCard?.visibility = View.VISIBLE
                    tvStatus?.text = ""
                    main.updateStatusBar()
                } else {
                    btnSeparate?.text = "⬡  Separate Meshes"
                    btnSeparate?.isEnabled = true
                    settingsCard?.visibility = View.VISIBLE
                    tvStatus?.text = "1 connected mesh — no islands to separate."
                }
            }
        }, "SeparationThread").start()
    }

    /** Merges every separated mesh back into a single mesh. */
    private fun combineAll() {
        val main = activity as? MainActivity ?: return
        val count = try { NativeLib.nativeGetMeshCount() } catch (_: Exception) { 0 }
        if (count <= 1) return
        btnCombine?.isEnabled = false
        btnCombine?.text = "⏳  Combining…"
        val indices = IntArray(count) { it }
        main.glView.queueEvent {
            val ok = try { NativeLib.nativeCombineMeshes(indices) } catch (_: Exception) { false }
            activity?.runOnUiThread {
                btnCombine?.isEnabled = true
                btnCombine?.text = "⊕  Combine Back into One Mesh"
                if (ok) {
                    resultCard?.visibility = View.GONE
                    btnSeparate?.visibility = View.VISIBLE
                    btnSeparate?.text = "⬡  Separate Meshes"
                    settingsCard?.visibility = View.VISIBLE
                    tvStatus?.text = "Combined back into one mesh."
                    main.updateStatusBar()
                } else {
                    tvStatus?.text = "Combine failed — try again."
                }
            }
        }
    }

    private fun stepLabel(p: Int): String = when {
        p < 12  -> "Analyzing connectivity…"
        p < 45  -> "Building adjacency graph…"
        p < 65  -> "Sorting islands…"
        p < 72  -> "Computing boundaries…"
        p < 78  -> "Filtering dust…"
        else    -> "Finalizing… $p%"
    }

    private fun refreshState() {
        Thread({
            val sep = try { NativeLib.nativeIsSeparated() } catch (_: Exception) { false }
            val mc  = try { NativeLib.nativeGetMeshCount() } catch (_: Exception) { 0 }
            uiHandler.post {
                if (sep && mc > 1) {
                    btnSeparate?.visibility = View.GONE
                    settingsCard?.visibility = View.GONE
                    tvResultTitle?.text = "✅ Already separated — $mc meshes"
                    tvResultDetail?.text =
                        "The model is already split into $mc meshes. Combine them back if you need one piece."
                    resultCard?.visibility = View.VISIBLE
                    tvStatus?.text = ""
                } else if (mc <= 0) {
                    tvStatus?.text = "Load a model first, then separate it here."
                    btnSeparate?.isEnabled = false
                } else {
                    tvStatus?.text = "Ready — $mc mesh loaded."
                }
            }
        }, "SeparationStateThread").start()
    }

    companion object {
        const val TAG = "Separation"
        fun newInstance() = SeparationFragment()
    }
}
