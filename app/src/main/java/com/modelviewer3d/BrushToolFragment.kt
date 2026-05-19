package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Brush Sculpting Tool — compact settings panel.
 *
 * HOW IT WORKS:
 * 1. Tap 🖌️ Brush in toolbar → this panel opens at PEEK height (bottom 240dp)
 * 2. Set brush type, radius, intensity
 * 3. Dismiss / swipe down → panel closes BUT tool stays ACTIVE (button highlighted)
 * 4. Drag finger on model → brush applies continuously at each touch point
 * 5. Re-tap Brush to adjust settings, tap again to dismiss/deactivate
 *
 * Touch flow (in ModelGLSurfaceView):
 *   onTouchEvent ACTION_DOWN/MOVE with activeTool==BRUSH
 *     → nativePickPoint(x,y,w,h)  → FloatArray[3] world hit
 *     → applyBrushAt(wx,wy,wz)    → nativeApplySmooth / nativeApplySculpt
 */
class BrushToolFragment : BottomSheetDialogFragment() {

    // These are read by ModelGLSurfaceView while tool is active
    var brushRadius    = 0.06f
    var brushIntensity = 0.4f
    var isSmooth       = true
    var sculptSign     = 1f
    var targetMeshIdx  = 0

    // Called by ModelGLSurfaceView on every touch-move event when BRUSH is active
    fun applyBrushAt(wx: Float, wy: Float, wz: Float) {
        val glv = (activity as? MainActivity)?.glView ?: return
        val r=brushRadius; val i=brushIntensity; val idx=targetMeshIdx
        glv.queueEvent {
            if (isSmooth) NativeLib.nativeApplySmooth(idx, wx, wy, wz, r, i)
            else          NativeLib.nativeApplySculpt(idx, wx, wy, wz, r, i, sculptSign)
        }
        glv.requestRender()
    }

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
            setPadding(0, 0, 0, 32)
        }

        // Handle bar
        root.addView(LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL; setPadding(0,14,0,0)
            addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#404058"))
                layoutParams = LinearLayout.LayoutParams(48,4) })
        })

        // Title row
        root.addView(LinearLayout(ctx).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL; setPadding(18,10,16,6)
            addView(TextView(ctx).apply { text="🖌  Brush Sculpting"; textSize=15f
                setTypeface(null,android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f) })
            addView(TextView(ctx).apply { text="Mesh #"; textSize=10f; setTextColor(Color.parseColor("#9090B0")) })
            addView(EditText(ctx).apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER; setText("0")
                setTextColor(Color.WHITE); textSize=12f; background=ctx.getDrawable(R.drawable.bg_input_field); setPadding(8,4,8,4)
                layoutParams=LinearLayout.LayoutParams(56,LinearLayout.LayoutParams.WRAP_CONTENT)
                addTextChangedListener(object:android.text.TextWatcher{
                    override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                    override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                    override fun afterTextChanged(s:android.text.Editable?){targetMeshIdx=s?.toString()?.toIntOrNull()?:0}}) })
        })

        root.addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#1A1A28"))
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1) })

        // HOW TO USE hint
        root.addView(TextView(ctx).apply {
            text = "ℹ  Dismiss this panel → drag finger on model to sculpt"
            textSize=10f; setTextColor(Color.parseColor("#00D4FF")); setPadding(18,8,18,2)
        })

        // Brush type buttons
        root.addView(LinearLayout(ctx).apply {
            orientation=LinearLayout.HORIZONTAL; setPadding(12,8,12,4)
            val btns = mutableListOf<Button>()
            fun btn(lbl:String,idx:Int,action:()->Unit)=Button(ctx).apply{
                text=lbl; textSize=11f
                setTextColor(if(idx==0)Color.parseColor("#050508") else Color.parseColor("#9090B0"))
                background=ctx.getDrawable(if(idx==0)R.drawable.bg_btn_accent else R.drawable.bg_card_dark)
                layoutParams=LinearLayout.LayoutParams(0,42,1f).apply{setMargins(3,0,3,0)}
                setOnClickListener{
                    action()
                    btns.forEachIndexed{i,b->
                        b.setTextColor(if(i==idx)Color.parseColor("#050508") else Color.parseColor("#9090B0"))
                        b.background=ctx.getDrawable(if(i==idx)R.drawable.bg_btn_accent else R.drawable.bg_card_dark)}
                }}
            btns.add(btn("✨ Smooth",0){isSmooth=true})
            btns.add(btn("▲ Raise",1){isSmooth=false;sculptSign=1f})
            btns.add(btn("▼ Lower",2){isSmooth=false;sculptSign=-1f})
            btns.forEach{addView(it)}
        })

        // Radius slider
        root.addView(sliderRow(ctx,"Radius","#00D4FF",(brushRadius/0.3f*100).toInt(),1,100){p->
            brushRadius=0.003f+p/100f*0.297f; "%.3f".format(brushRadius) })

        // Intensity slider
        root.addView(sliderRow(ctx,"Intensity","#FF9800",(brushIntensity*100).toInt(),0,100){p->
            brushIntensity=p/100f; "$p%" })

        root.addView(TextView(ctx).apply {
            text="⚠ Brush edits vertices permanently. Use ↩ Undo to revert."
            textSize=9f; setTextColor(Color.parseColor("#604020")); setPadding(18,6,18,0) })

        return root
    }

    private fun sliderRow(ctx:android.content.Context,label:String,tint:String,init:Int,min:Int,max:Int,cb:(Int)->String):LinearLayout{
        val tvV=TextView(ctx).apply{text=cb(init);textSize=10f;setTextColor(Color.parseColor(tint));minWidth=60}
        return LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(18,4,18,4)
            addView(TextView(ctx).apply{text=label;textSize=11f;setTextColor(Color.parseColor("#9090B0"))
                layoutParams=LinearLayout.LayoutParams(90,LinearLayout.LayoutParams.WRAP_CONTENT)})
            addView(SeekBar(ctx).apply{setMax(max);progress=init
                progressTintList=android.content.res.ColorStateList.valueOf(Color.parseColor(tint))
                thumbTintList=android.content.res.ColorStateList.valueOf(Color.parseColor(tint))
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onStartTrackingTouch(b:SeekBar){}; override fun onStopTrackingTouch(b:SeekBar){}
                    override fun onProgressChanged(b:SeekBar,p:Int,fromUser:Boolean){if(fromUser)tvV.text=cb(p)}})})
            addView(tvV)}
    }

    companion object { const val TAG="BrushTool"; fun newInstance()=BrushToolFragment() }
}
