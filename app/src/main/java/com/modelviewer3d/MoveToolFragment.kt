package com.modelviewer3d
import android.graphics.Color;import android.os.Bundle;import android.view.*;import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
class MoveToolFragment:BottomSheetDialogFragment(){
    private var posX=0f;private var posY=0f;private var posZ=0f
    private data class Ax(val lbl:String,val tint:Int,val getter:()->Float,val setter:(Float)->Unit)
    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View{
        val ctx=requireContext();val scroll=ScrollView(ctx)
        val root=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;setBackgroundResource(R.drawable.bg_bottom_sheet);setPadding(0,0,0,40)}
        scroll.addView(root)
        root.addView(LinearLayout(ctx).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL;setPadding(0,14,0,0);addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#607286"));layoutParams=LinearLayout.LayoutParams(48,4)})})
        root.addView(LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(20,14,20,4)
            addView(TextView(ctx).apply{text="↕  Move";textSize=17f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(Color.WHITE);layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
            addView(Button(ctx).apply{text="Reset";textSize=10f;setTextColor(Color.parseColor("#FF9B71"));background=ctx.getDrawable(R.drawable.bg_btn_danger);setPadding(16,0,16,0);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,36);setOnClickListener{posX=0f;posY=0f;posZ=0f;gl{NativeLib.nativeSetTranslation(0f,0f,0f)}}})})
        root.addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#243445"));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})
        for(ax in listOf(Ax("X  Left/Right",Color.parseColor("#FFB86B"),{posX},{v->posX=v}),Ax("Y  Up/Down",Color.parseColor("#4CAF82"),{posY},{v->posY=v}),Ax("Z  Near/Far",Color.parseColor("#62E6FF"),{posZ},{v->posZ=v}))){
            val tv=TextView(ctx).apply{text="0.00";textSize=11f;setTextColor(Color.parseColor("#62E6FF"))}
            root.addView(LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;setPadding(20,10,20,4)
                addView(LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
                    addView(TextView(ctx).apply{text=ax.lbl;textSize=12f;setTextColor(Color.parseColor("#A8B6C7"));layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)});addView(tv)})
                addView(SeekBar(ctx).apply{setMax(1000);progress=500
                    progressTintList=android.content.res.ColorStateList.valueOf(ax.tint);thumbTintList=android.content.res.ColorStateList.valueOf(ax.tint)
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onStartTrackingTouch(b:SeekBar){};override fun onStopTrackingTouch(b:SeekBar){}
                        override fun onProgressChanged(b:SeekBar,p:Int,fu:Boolean){if(!fu)return;val v=-5f+(p/1000f)*10f;tv.text="%.2f".format(v);ax.setter(v);gl{NativeLib.nativeSetTranslation(posX,posY,posZ)}}})})})}
        return scroll
    }
    private fun gl(block:()->Unit)=(activity as? MainActivity)?.glView?.queueEvent(block)
    companion object{const val TAG="MoveTool";fun newInstance()=MoveToolFragment()}
}
