package com.modelviewer3d
import android.graphics.Color;import android.os.Bundle;import android.view.*;import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
class RotateToolFragment:BottomSheetDialogFragment(){
    private var rotX=0f;private var rotY=0f;private var rotZ=0f
    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View{
        val ctx=requireContext();val scroll=ScrollView(ctx)
        val root=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;setBackgroundResource(R.drawable.bg_bottom_sheet);setPadding(0,0,0,40)}
        scroll.addView(root)
        root.addView(LinearLayout(ctx).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL;setPadding(0,14,0,0);addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#404058"));layoutParams=LinearLayout.LayoutParams(48,4)})})
        root.addView(LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(20,14,20,4)
            addView(TextView(ctx).apply{text="🔄  Rotate";textSize=17f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(Color.WHITE);layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
            addView(Button(ctx).apply{text="Reset";textSize=10f;setTextColor(Color.parseColor("#FF7043"));background=ctx.getDrawable(R.drawable.bg_btn_danger);setPadding(16,0,16,0);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,36);setOnClickListener{rotX=0f;rotY=0f;rotZ=0f;gl{NativeLib.nativeSetRotation(0f,0f,0f)}}})})
        root.addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#1A1A28"));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})
        root.addView(LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;setPadding(14,8,14,0)
            for((lbl,ax,deg) in listOf(Triple("+90X","X",90f),Triple("−90X","X",-90f),Triple("180Y","Y",180f),Triple("180Z","Z",180f))){
                addView(Button(ctx).apply{text=lbl;textSize=9f;setTextColor(Color.parseColor("#9090B0"));background=ctx.getDrawable(R.drawable.bg_card_dark)
                    layoutParams=LinearLayout.LayoutParams(0,36,1f).apply{setMargins(3,0,3,0)}
                    setOnClickListener{when(ax){"X"->rotX=(rotX+deg).coerceIn(-180f,180f);"Y"->rotY=(rotY+deg).coerceIn(-180f,180f);"Z"->rotZ=(rotZ+deg).coerceIn(-180f,180f)};gl{NativeLib.nativeSetRotation(rotX,rotY,rotZ)}}})}})
        for((lbl,tint) in listOf(Pair("X  Tilt",Color.parseColor("#FF9800")),Pair("Y  Spin",Color.parseColor("#4CAF82")),Pair("Z  Roll",Color.parseColor("#00D4FF")))){
            val tv=TextView(ctx).apply{text="0°";textSize=11f;setTextColor(Color.parseColor("#00D4FF"))}
            root.addView(LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;setPadding(20,10,20,4)
                addView(LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
                    addView(TextView(ctx).apply{text=lbl;textSize=12f;setTextColor(Color.parseColor("#9090B0"));layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)});addView(tv)})
                addView(SeekBar(ctx).apply{setMax(1000);progress=500
                    progressTintList=android.content.res.ColorStateList.valueOf(tint);thumbTintList=android.content.res.ColorStateList.valueOf(tint)
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onStartTrackingTouch(b:SeekBar){};override fun onStopTrackingTouch(b:SeekBar){}
                        override fun onProgressChanged(b:SeekBar,p:Int,fu:Boolean){if(!fu)return;val v=-180f+(p/1000f)*360f;tv.text="%.0f°".format(v)
                            when(lbl){"X  Tilt"->rotX=v;"Y  Spin"->rotY=v;"Z  Roll"->rotZ=v};gl{NativeLib.nativeSetRotation(rotX,rotY,rotZ)}}})})})}
        return scroll
    }
    private fun gl(block:()->Unit)=(activity as? MainActivity)?.glView?.queueEvent(block)
    companion object{const val TAG="RotateTool";fun newInstance()=RotateToolFragment()}
}
