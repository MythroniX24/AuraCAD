package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BrushToolFragment : BottomSheetDialogFragment() {
    var brushRadius = 0.06f; var brushIntensity = 0.4f
    var isSmooth = true; var sculptSign = 1f; var targetMeshIdx = 0

    fun applyBrushAt(wx: Float, wy: Float, wz: Float) {
        if (!isAdded) return
        val glv = (activity as? MainActivity)?.glView ?: return
        val r = brushRadius; val i = brushIntensity; val idx = targetMeshIdx
        glv.queueEvent {
            if (isSmooth) NativeLib.nativeApplySmooth(idx, wx, wy, wz, r, i)
            else NativeLib.nativeApplySculpt(idx, wx, wy, wz, r, i, sculptSign)
        }
        glv.requestRender()
    }

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet); setPadding(0,0,0,48) }
        scroll.addView(root)
        root.addView(handle(ctx))
        root.addView(LinearLayout(ctx).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL; setPadding(20,14,16,6)
            addView(TextView(ctx).apply { text="🖌️  Brush Sculpting"; textSize=16f
                setTypeface(null,android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f) })
        })
        root.addView(hdiv(ctx))
        root.addView(TextView(ctx).apply { text="Tap model surface to apply brush. Mesh #:"; textSize=10f
            setTextColor(Color.parseColor("#505070")); setPadding(20,8,20,4) })

        val etIdx = EditText(ctx).apply {
            inputType=android.text.InputType.TYPE_CLASS_NUMBER; setText("0")
            setTextColor(Color.WHITE); textSize=13f; background=ctx.getDrawable(R.drawable.bg_input_field); setPadding(10,6,10,6)
            layoutParams=LinearLayout.LayoutParams(80,LinearLayout.LayoutParams.WRAP_CONTENT).apply{setMargins(20,0,20,8)}
            addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun afterTextChanged(s:android.text.Editable?){targetMeshIdx=s?.toString()?.toIntOrNull()?:0}})
        }
        root.addView(etIdx)
        root.addView(sec(ctx,"BRUSH TYPE"))

        val btns = LinearLayout(ctx).apply { orientation=LinearLayout.HORIZONTAL; setPadding(14,4,14,8) }
        fun typeBtn(lbl:String,active:Boolean,onClick:()->Unit)=Button(ctx).apply{
            text=lbl; textSize=11f
            setTextColor(if(active) Color.parseColor("#050508") else Color.parseColor("#9090B0"))
            background=ctx.getDrawable(if(active) R.drawable.bg_btn_accent else R.drawable.bg_card_dark)
            layoutParams=LinearLayout.LayoutParams(0,44,1f).apply{setMargins(3,0,3,0)}
            setOnClickListener{onClick()}}
        val bSmooth = typeBtn("✨ Smooth",true){isSmooth=true;sculptSign=1f; refreshBrushUI(btns,0)}
        val bRaise  = typeBtn("▲ Raise",false){isSmooth=false;sculptSign=1f; refreshBrushUI(btns,1)}
        val bLower  = typeBtn("▼ Lower",false){isSmooth=false;sculptSign=-1f;refreshBrushUI(btns,2)}
        btns.addView(bSmooth); btns.addView(bRaise); btns.addView(bLower)
        root.addView(btns)
        root.addView(sec(ctx,"BRUSH RADIUS"))
        root.addView(sliderRow(ctx,"📏","%.3f".format(brushRadius),"#00D4FF",
            (brushRadius/0.3f*100).toInt(),1,100){p->brushRadius=0.003f+p/100f*0.297f;"%.3f".format(brushRadius)})
        root.addView(sec(ctx,"INTENSITY"))
        root.addView(sliderRow(ctx,"💪","${(brushIntensity*100).toInt()}%","#FF9800",
            (brushIntensity*100).toInt(),0,100){p->brushIntensity=p/100f;"$p%"})
        root.addView(hdiv(ctx))
        root.addView(TextView(ctx).apply{ text="⚠ Brush permanently modifies vertices. Use ↩ Undo to revert."
            textSize=9f; setTextColor(Color.parseColor("#604020")); setPadding(20,8,20,16)})
        return scroll
    }

    private fun refreshBrushUI(row:LinearLayout, sel:Int){
        for(i in 0 until row.childCount){
            val b=row.getChildAt(i) as? Button ?: continue; val active=i==sel
            b.setTextColor(if(active)Color.parseColor("#050508") else Color.parseColor("#9090B0"))
            b.background=context?.getDrawable(if(active) R.drawable.bg_btn_accent else R.drawable.bg_card_dark)
        }
    }
    private fun sliderRow(ctx:android.content.Context,icon:String,initVal:String,tint:String,init:Int,min:Int,max:Int,cb:(Int)->String)=LinearLayout(ctx).apply{
        orientation=LinearLayout.VERTICAL; setPadding(20,4,20,4)
        val tvV=TextView(ctx).apply{text=initVal;textSize=11f;setTextColor(Color.parseColor(tint));gravity=android.view.Gravity.CENTER}
        addView(tvV)
        addView(SeekBar(ctx).apply{setMax(max);progress=init
            progressTintList=android.content.res.ColorStateList.valueOf(Color.parseColor(tint))
            thumbTintList=android.content.res.ColorStateList.valueOf(Color.parseColor(tint))
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onStartTrackingTouch(b:SeekBar){}; override fun onStopTrackingTouch(b:SeekBar){}
                override fun onProgressChanged(b:SeekBar,p:Int,fromUser:Boolean){if(fromUser) tvV.text=cb(p)}})})
    }
    private fun handle(ctx:android.content.Context)=LinearLayout(ctx).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL;setPadding(0,14,0,0)
        addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#404058"));layoutParams=LinearLayout.LayoutParams(48,4)})}
    private fun sec(ctx:android.content.Context,t:String)=TextView(ctx).apply{text=t;textSize=9f;letterSpacing=0.14f;setTextColor(Color.parseColor("#00D4FF"));setPadding(20,12,20,4)}
    private fun hdiv(ctx:android.content.Context)=View(ctx).apply{setBackgroundColor(Color.parseColor("#1A1A28"));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)}
    companion object { const val TAG="BrushTool"; fun newInstance()=BrushToolFragment() }
}
