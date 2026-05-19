package com.modelviewer3d
import android.graphics.Color;import android.os.Bundle;import android.view.*;import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
class MoveToolFragment:BottomSheetDialogFragment(){
    private var posX=0f;private var posY=0f;private var posZ=0f
    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View{
        val ctx=requireContext();val scroll=ScrollView(ctx)
        val root=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;setBackgroundResource(R.drawable.bg_bottom_sheet);setPadding(0,0,0,40)}
        scroll.addView(root)
        root.addView(handle(ctx))
        root.addView(titleRow(ctx,"↕  Move"){posX=0f;posY=0f;posZ=0f;gl{NativeLib.nativeSetTranslation(0f,0f,0f)}})
        root.addView(div(ctx))
        root.addView(axis(ctx,"X  Left/Right",Color.parseColor("#FF9800"),-5f,5f){v->posX=v;gl{NativeLib.nativeSetTranslation(posX,posY,posZ)}})
        root.addView(axis(ctx,"Y  Up/Down",Color.parseColor("#4CAF82"),-5f,5f){v->posY=v;gl{NativeLib.nativeSetTranslation(posX,posY,posZ)}})
        root.addView(axis(ctx,"Z  Near/Far",Color.parseColor("#00D4FF"),-5f,5f){v->posZ=v;gl{NativeLib.nativeSetTranslation(posX,posY,posZ)}})
        return scroll
    }
    private fun axis(ctx:android.content.Context,l:String,t:Int,min:Float,max:Float,cb:(Float)->Unit)=LinearLayout(ctx).apply{
        orientation=LinearLayout.VERTICAL;setPadding(20,10,20,4)
        val tv=TextView(ctx).apply{text="0.00";textSize=11f;setTextColor(Color.parseColor("#00D4FF"))}
        addView(LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply{text=l;textSize=12f;setTextColor(Color.parseColor("#9090B0"));layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)});addView(tv)})
        addView(SeekBar(ctx).apply{setMax(1000);progress=500
            progressTintList=android.content.res.ColorStateList.valueOf(t);thumbTintList=android.content.res.ColorStateList.valueOf(t)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onStartTrackingTouch(b:SeekBar){};override fun onStopTrackingTouch(b:SeekBar){}
                override fun onProgressChanged(b:SeekBar,p:Int,fu:Boolean){if(!fu)return;val v=min+(p/1000f)*(max-min);tv.text="%.2f".format(v);cb(v)}})})}
    private fun handle(c:android.content.Context)=LinearLayout(c).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL;setPadding(0,14,0,0);addView(View(c).apply{setBackgroundColor(Color.parseColor("#404058"));layoutParams=LinearLayout.LayoutParams(48,4)})}
    private fun titleRow(c:android.content.Context,t:String,reset:()->Unit)=LinearLayout(c).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(20,14,20,4)
        addView(TextView(c).apply{text=t;textSize=17f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(Color.WHITE);layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
        addView(Button(c).apply{text="Reset";textSize=10f;setTextColor(Color.parseColor("#FF7043"));background=c.getDrawable(R.drawable.bg_btn_danger);setPadding(16,0,16,0);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,36);setOnClickListener{reset()}})}
    private fun div(c:android.content.Context)=View(c).apply{setBackgroundColor(Color.parseColor("#1A1A28"));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)}
    private fun gl(block:()->Unit)=(activity as? MainActivity)?.glView?.queueEvent(block)
    companion object{const val TAG="MoveTool";fun newInstance()=MoveToolFragment()}
}
