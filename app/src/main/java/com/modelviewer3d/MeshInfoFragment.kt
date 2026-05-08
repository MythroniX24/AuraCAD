package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MeshInfoFragment : BottomSheetDialogFragment() {
    private var tvContent:TextView?=null
    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View{
        val ctx=requireContext()
        val scroll=ScrollView(ctx)
        val root=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;setBackgroundResource(R.drawable.bg_bottom_sheet);setPadding(0,0,0,56)}
        scroll.addView(root)
        root.addView(LinearLayout(ctx).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL;setPadding(0,14,0,0)
            addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#404058"));layoutParams=LinearLayout.LayoutParams(48,4)})})
        root.addView(LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(20,14,16,6)
            addView(TextView(ctx).apply{text="ℹ️  Mesh Info";textSize=16f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(Color.WHITE)
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
            addView(Button(ctx).apply{text="↻";textSize=12f;setTextColor(Color.parseColor("#00D4FF"));background=ctx.getDrawable(R.drawable.bg_card_dark);setPadding(16,0,16,0)
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,40);setOnClickListener{loadStats()}})})
        root.addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#1A1A28"));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})
        val tv=TextView(ctx).apply{text="⏳ Loading…";textSize=11f;setTextColor(Color.parseColor("#9090B0"))
            typeface=android.graphics.Typeface.MONOSPACE
            background=ctx.getDrawable(R.drawable.bg_hint_card);setPadding(16,16,16,16)
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{setMargins(14,12,14,0)}}
        tvContent=tv; root.addView(tv)
        loadStats(); return scroll
    }
    private fun loadStats(){
        (activity as? MainActivity)?.glView?.queueEvent{
            val mc=try{NativeLib.nativeGetMeshCount()}catch(_:Exception){0}
            if(mc==0){activity?.runOnUiThread{tvContent?.text="No model loaded."};return@queueEvent}
            val sb=StringBuilder()
            for(i in 0 until mc){
                val name=try{NativeLib.nativeGetMeshName(i)}catch(_:Exception){"Mesh #$i"}
                val s=try{NativeLib.nativeGetMeshStats(i)}catch(_:Exception){continue}
                if(s.size<9) continue
                val wt=s[8]>0.5f
                sb.appendLine("── $name ──")
                sb.appendLine("  Verts: ${fmt(s[5].toInt())}   Tris: ${fmt(s[6].toInt())}")
                sb.appendLine("  Area:  %.2f mm²".format(s[0]))
                sb.appendLine("  Vol:   %.2f mm³".format(s[1]))
                sb.appendLine("  Box:   %.1f×%.1f×%.1f mm".format(s[2],s[3],s[4]))
                sb.appendLine("  Watertight: ${if(wt)"✓ Yes" else "✗ No"}")
                if(i<mc-1)sb.appendLine()
            }
            activity?.runOnUiThread{tvContent?.text=sb.toString().trimEnd();tvContent?.setTextColor(Color.WHITE)}
        }
    }
    private fun fmt(n:Int)=when{n>=1_000_000->"%.2fM".format(n/1_000_000f);n>=1_000->"%.1fK".format(n/1_000f);else->"$n"}
    companion object{const val TAG="MeshInfo";fun newInstance()=MeshInfoFragment()}
}
