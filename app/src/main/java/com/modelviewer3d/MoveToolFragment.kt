package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Move Tool — X/Y/Z position sliders with live preview */
class MoveToolFragment : BottomSheetDialogFragment() {

    private var posX = 0f; private var posY = 0f; private var posZ = 0f

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(0, 0, 0, 40)
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

        // Title
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20, 14, 20, 6)
            addView(TextView(ctx).apply {
                text = "↕  Move  /  Position"
                textSize = 17f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(ctx).apply {
                text = "Reset"; textSize = 10f; setTextColor(Color.parseColor("#FF7043"))
                background = ctx.getDrawable(R.drawable.bg_btn_danger); setPadding(16,0,16,0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 36)
                setOnClickListener {
                    posX = 0f; posY = 0f; posZ = 0f
                    glRun { NativeLib.nativeSetTranslation(0f, 0f, 0f) }
                }
            })
        })

        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1A1A28"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        root.addView(helpText(ctx, "Drag sliders to reposition the model along each axis."))

        // X / Y / Z sliders
        for ((axis, getter, setter) in listOf<Triple<String, () -> Float, (Float) -> Unit>>(
            Triple("X  (Left/Right)",  { posX }) { v -> posX = v; glRun { NativeLib.nativeSetTranslation(posX, posY, posZ) } },
            Triple("Y  (Up/Down)",     { posY }) { v -> posY = v; glRun { NativeLib.nativeSetTranslation(posX, posY, posZ) } },
            Triple("Z  (Near/Far)",    { posZ }) { v -> posZ = v; glRun { NativeLib.nativeSetTranslation(posX, posY, posZ) } }
        )) {
            root.addView(axisSlider(ctx, axis, Color.parseColor("#00D4FF"), -5f, 5f, getter()) { v -> setter(v) })
        }

        root.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 20) })
        return scroll
    }

    private fun axisSlider(ctx: android.content.Context, label: String, tint: Int, min: Float, max: Float, init: Float, cb: (Float) -> Unit): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(20, 10, 20, 2)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(ctx).apply {
                    text = label; textSize = 12f; setTextColor(Color.parseColor("#9090B0"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val tvVal = TextView(ctx).apply {
                    text = "%.2f".format(init); textSize = 11f; setTextColor(Color.parseColor("#00D4FF"))
                }
                addView(tvVal)
                addView(SeekBar(ctx).apply {
                    max = 1000; progress = ((init - min) / (max - min) * 1000).toInt().coerceIn(0, 1000)
                    progressTintList = android.content.res.ColorStateList.valueOf(tint)
                    thumbTintList    = android.content.res.ColorStateList.valueOf(tint)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = 4 }
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(b: SeekBar) {}
                        override fun onStopTrackingTouch(b: SeekBar) {}
                        override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            val v = min + p / 1000f * (max - min)
                            tvVal.text = "%.2f".format(v)
                            cb(v)
                        }
                    })
                })
            })
        }
    }

    private fun helpText(ctx: android.content.Context, msg: String) = TextView(ctx).apply {
        text = msg; textSize = 10f; setTextColor(Color.parseColor("#404060")); setPadding(20, 8, 20, 4)
    }

    private fun glRun(block: () -> Unit) = (activity as? MainActivity)?.glView?.queueEvent(block)

    companion object { const val TAG = "MoveTool"; fun newInstance() = MoveToolFragment() }
}
