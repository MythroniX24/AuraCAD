package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ExportFragment : BottomSheetDialogFragment() {
    private var selectedFormat="obj"
    private data class Fmt(val id:String,val lbl:String,val desc:String,val color:String)
    private val formats=listOf(Fmt("obj","OBJ","Wavefront · Text · Universal","#00D4FF"),Fmt("stl","STL","Binary · 3D Printing","#FF9800"),Fmt("ply","PLY","Stanford · MeshLab compatible","#4CAF82"))
    private var fmtBtns=mutableListOf<Pair<LinearLayout,Fmt>>()

    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View{
        val ctx=requireContext(); val scroll=ScrollView(ctx)
        val root=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;setBackgroundResource(R.drawable.bg_bottom_sheet);setPadding(0,0,0,56)}
        scroll.addView(root)
        root.addView(LinearLayout(ctx).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL;setPadding(0,14,0,0)
            addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#404058"));layoutParams=LinearLayout.LayoutParams(48,4)})})
        root.addView(TextView(ctx).apply{text="↑  Export & Share";textSize=17f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(Color.WHITE);setPadding(20,14,20,4)})
        root.addView(TextView(ctx).apply{text="Select format, then save or share";textSize=11f;setTextColor(Color.parseColor("#606080"));setPadding(20,0,20,10)})
        root.addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#1A1A28"));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})

        root.addView(TextView(ctx).apply{text="FORMAT";textSize=9f;letterSpacing=0.14f;setTextColor(Color.parseColor("#00D4FF"));setPadding(20,14,20,6)})
        val fmtRow=LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;setPadding(14,6,14,14)}
        for(fmt in formats){
            val btn=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER
                background=ctx.getDrawable(if(fmt.id==selectedFormat) R.drawable.bg_card_selected else R.drawable.bg_card_dark)
                setPadding(10,14,10,14);isClickable=true;isFocusable=true
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply{setMargins(4,0,4,0)}
                addView(TextView(ctx).apply{text=fmt.lbl;textSize=15f;setTypeface(null,android.graphics.Typeface.BOLD)
                    setTextColor(if(fmt.id==selectedFormat) Color.parseColor(fmt.color) else Color.WHITE);gravity=android.view.Gravity.CENTER})
                addView(TextView(ctx).apply{text=fmt.desc;textSize=8f;setTextColor(Color.parseColor("#505070"));gravity=android.view.Gravity.CENTER;setPadding(0,4,0,0)})
                setOnClickListener{selectedFormat=fmt.id;refreshBtns()}}
            fmtBtns.add(Pair(btn,fmt)); fmtRow.addView(btn)
        }
        root.addView(fmtRow)
        root.addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#1A1A28"));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})

        root.addView(TextView(ctx).apply{text="SAVE TO DEVICE";textSize=9f;letterSpacing=0.14f;setTextColor(Color.parseColor("#00D4FF"));setPadding(20,14,20,6)})
        root.addView(actionRow(ctx,"💾","Save to Downloads","Downloads/3DViewer/","#00D4FF"){(activity as? MainActivity)?.exportModel(selectedFormat,share=false);dismiss()})
        root.addView(View(ctx).apply{layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,8)})
        root.addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#1A1A28"));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})

        root.addView(TextView(ctx).apply{text="SHARE VIA";textSize=9f;letterSpacing=0.14f;setTextColor(Color.parseColor("#00D4FF"));setPadding(20,14,20,6)})
        root.addView(actionRow(ctx,"↗","Share via Any App","System chooser","#9090B0"){(activity as? MainActivity)?.exportModel(selectedFormat,share=true);dismiss()})
        root.addView(LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;setPadding(14,8,14,0)
            for((emoji,label,pkg) in listOf(Triple("💬","WhatsApp","com.whatsapp"),Triple("✈️","Telegram","org.telegram.messenger"),Triple("📧","Email",null))){
                addView(LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER
                    background=ctx.getDrawable(R.drawable.bg_card_dark);isClickable=true;isFocusable=true
                    layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply{setMargins(4,0,4,0)}
                    setPadding(0,10,0,10)
                    addView(TextView(ctx).apply{text=emoji;textSize=20f;gravity=android.view.Gravity.CENTER})
                    addView(TextView(ctx).apply{text=label;textSize=9f;setTextColor(Color.parseColor("#606080"));gravity=android.view.Gravity.CENTER})
                    setOnClickListener{(activity as? MainActivity)?.exportModel(selectedFormat,share=true,shareApp=pkg);dismiss()}})}})
        return scroll
    }

    private fun actionRow(ctx:android.content.Context,icon:String,title:String,sub:String,color:String,action:()->Unit)=LinearLayout(ctx).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;background=ctx.getDrawable(R.drawable.bg_card_dark)
        setPadding(20,0,20,0);minimumHeight=60;isClickable=true;isFocusable=true
        layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{setMargins(14,0,14,0)}
        addView(TextView(ctx).apply{text=icon;textSize=22f;setPadding(0,0,16,0)})
        addView(LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL
            layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            addView(TextView(ctx).apply{text=title;textSize=13f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(Color.parseColor(color))})
            addView(TextView(ctx).apply{text=sub;textSize=9f;setTextColor(Color.parseColor("#505070"))})})
        addView(TextView(ctx).apply{text="›";textSize=20f;setTextColor(Color.parseColor("#303050"))})
        setOnClickListener{action()}}

    private fun refreshBtns(){val ctx=context?:return
        for((btn,fmt) in fmtBtns){val sel=fmt.id==selectedFormat
            btn.background=ctx.getDrawable(if(sel) R.drawable.bg_card_selected else R.drawable.bg_card_dark)
            (btn.getChildAt(0) as? TextView)?.setTextColor(if(sel) Color.parseColor(fmt.color) else Color.WHITE)}}

    companion object{const val TAG="ExportSheet";fun newInstance()=ExportFragment()}
}
