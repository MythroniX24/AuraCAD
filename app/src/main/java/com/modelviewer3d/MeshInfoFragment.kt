package com.modelviewer3d
import android.graphics.Color;import android.os.Bundle;import android.view.*;import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
class MeshInfoFragment:BottomSheetDialogFragment(){
    private var tv:TextView?=null
    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View{
        val ctx=requireContext();val scroll=ScrollView(ctx)
        val root=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;setBackgroundResource(R.drawable.bg_bottom_sheet);setPadding(0,0,0,56)}
        scroll.addView(root)
        root.addView(LinearLayout(ctx).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL;setPadding(0,14,0,0);addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#404058"));layoutParams=LinearLayout.LayoutParams(48,4)})})
        root.addView(LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(20,14,16,6)
            addView(TextView(ctx).apply{text="ℹ️  Mesh Info";textSize=16f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(Color.WHITE);layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
            addView(Button(ctx).apply{text="↻";textSize=12f;setTextColor(Color.parseColor("#00D4FF"));background=ctx.getDrawable(R.drawable.bg_card_dark);setPadding(16,0,16,0);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,40);setOnClickListener{load()}})})
        root.addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#1A1A28"));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)})
        tv=TextView(ctx).apply{text="⏳ Loading…";textSize=11f;setTextColor(Color.WHITE);typeface=android.graphics.Typeface.MONOSPACE;background=ctx.getDrawable(R.drawable.bg_hint_card);setPadding(16,16,16,16);layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{setMargins(14,12,14,0)}}
        root.addView(tv);load();return scroll
    }
    private fun load(){(activity as? MainActivity)?.glView?.queueEvent{
        val mc=try{NativeLib.nativeGetMeshCount()}catch(_:Exception){0}
        if(mc==0){activity?.runOnUiThread{tv?.text="No model loaded."};return@queueEvent}
        val sb=StringBuilder()
        for(idx in 0 until mc){val nm=try{NativeLib.nativeGetMeshName(idx)}catch(_:Exception){"Mesh #$idx"}
            val s=try{NativeLib.nativeGetMeshStats(idx)}catch(_:Exception){continue};if(s.size<9)continue
            sb.appendLine("── $nm ──");sb.appendLine("  Verts: ${s[5].toInt()}   Tris: ${s[6].toInt()}")
            sb.appendLine("  Box:   %.1f×%.1f×%.1f mm".format(s[2],s[3],s[4]))
            sb.appendLine("  Watertight: ${if(s[8]>0.5f)"✓ Yes" else "✗ No"}");if(idx<mc-1)sb.appendLine()}
        activity?.runOnUiThread{tv?.text=sb.toString().trimEnd()}}}
    companion object{const val TAG="MeshInfo";fun newInstance()=MeshInfoFragment()}
}
