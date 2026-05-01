package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Rotate Tool — X/Y/Z rotation sliders with live preview */
class RotateToolFragment : BottomSheetDialogFragment() {

    private var rotX = 0f; private var rotY = 0f; private var rotZ = 0f

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
                text = "🔄  Rotate"
                textSize = 17f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(ctx).apply {
                text = "Reset"; textSize = 10f; setTextColor(Color.parseColor("#FF7043"))
                background = ctx.getDrawable(R.drawable.bg_btn_danger); setPadding(16,0,16,0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 36)
                setOnClickListener {
                    rotX = 0f; rotY = 0f; rotZ = 0f
                    glRun { NativeLib.nativeSetRotation(0f, 0f, 0f) }
                }
            })
        })

        root.addView(View(ctx).apply {
            setBackgroundColor(Color.parseColor("#1A1A28"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        root.addView(helpText(ctx, "Drag sliders to rotate the model. Range: −180° to +180°."))

        // Degree quick-buttons row
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(14, 8, 14, 0)
            for (deg in listOf(90, -90, 180)) {
                addView(Button(ctx).apply {
                    text = "${if (deg > 0) "+" else ""}${deg}°  X"
                    textSize = 10f; setTextColor(Color.parseColor("#9090B0"))
                    background = ctx.getDrawable(R.drawable.bg_card_dark)
                    layoutParams = LinearLayout.LayoutParams(0, 38, 1f).apply { setMargins(4,0,4,0) }
                    setOnClickListener {
                        rotX = (rotX + deg).coerceIn(-180f, 180f)
                        glRun { NativeLib.nativeSetRotation(rotX, rotY, rotZ) }
                    }
                })
            }
        })

        for ((axis, tint, getter, setter) in listOf<Any>(
            arrayOf("X  (Tilt)",     "#FF9800", { rotX }) { v: Float -> rotX = v; glRun { NativeLib.nativeSetRotation(rotX, rotY, rotZ) } },
            arrayOf("Y  (Spin)",     "#4CAF82", { rotY }) { v: Float -> rotY = v; glRun { NativeLib.nativeSetRotation(rotX, rotY, rotZ) } },
            arrayOf("Z  (Roll)",     "#00D4FF", { rotZ }) { v: Float -> rotZ = v; glRun { NativeLib.nativeSetRotation(rotX, rotY, rotZ) } }
        )) {
            @Suppress("UNCHECKED_CAST")
            val a = axis as Array<Any>
            root.addView(axisSlider(ctx, a[0] as String, Color.parseColor(a[1] as String),
                -180f, 180f, (a[2] as () -> Float)()) { v -> (a[3] as (Float) -> Unit)(v) })
        }

        root.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 20) })
        return scroll
    }

    private fun axisSlider(ctx: android.content.Context, label: String, tint: Int, min: Float, max: Float, init: Float, cb: (Float) -> Unit): LinearLayout {
        val tvVal = TextView(ctx).apply {
            text = "%.0f°".format(init); textSize = 11f; setTextColor(Color.parseColor("#00D4FF"))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(20, 10, 20, 2)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(ctx).apply {
                    text = label; textSize = 12f; setTextColor(Color.parseColor("#9090B0"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(tvVal)
            })
            addView(SeekBar(ctx).apply {
                max = 1000; progress = ((init - min) / (max - min) * 1000).toInt().coerceIn(0, 1000)
                progressTintList = android.content.res.ColorStateList.valueOf(tint)
                thumbTintList    = android.content.res.ColorStateList.valueOf(tint)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(b: SeekBar) {}
                    override fun onStopTrackingTouch(b: SeekBar) {}
                    override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val v = min + p / 1000f * (max - min)
                        tvVal.text = "%.0f°".format(v); cb(v)
                    }
                })
            })
        }
    }

    private fun helpText(ctx: android.content.Context, msg: String) = TextView(ctx).apply {
        text = msg; textSize = 10f; setTextColor(Color.parseColor("#404060")); setPadding(20, 8, 20, 4)
    }

    private fun glRun(block: () -> Unit) = (activity as? MainActivity)?.glView?.queueEvent(block)

    companion object { const val TAG = "RotateTool"; fun newInstance() = RotateToolFragment() }
}
