package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class RingToolFragment : BottomSheetDialogFragment() {
    private var origBandWidthMM=0f; private var origInnerDiaMM=0f
    private var bwMin=0.1f; private var bwMax=20f
    private var idMin=1f;   private var idMax=50f
    private val STEPS=3000
    private var ringAnalyzed=false
    private var targetMeshIdx=0
    private var lastBWMM=-1f; private var lastIDMM=-1f
    @Volatile private var suppressBW=false
    @Volatile private var suppressID=false

    private var tvStatus:TextView?=null; private var tvInfo:TextView?=null
    private var tvBwInfo:TextView?=null; private var tvIdInfo:TextView?=null
    private var sbBW:SeekBar?=null; private var etBW:EditText?=null
    private var sbID:SeekBar?=null; private var etID:EditText?=null
    private var cardBW:View?=null; private var cardID:View?=null

    private val broadcastDimsRunnable=Runnable{
        activity?.sendBroadcast(android.content.Intent(EditorPanelFragment.ACTION_DIMS_CHANGED))
    }

    override fun onCreate(s:Bundle?){super.onCreate(s); targetMeshIdx=arguments?.getInt("meshIdx",0)?:0}

    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View{
        val ctx=requireContext()
        val scroll=ScrollView(ctx)
        val root=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet);setPadding(0,0,0,72)}
        scroll.addView(root)

        root.addView(handle(ctx))
        root.addView(LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(20,14,20,6)
            addView(TextView(ctx).apply{text="💍  Ring Tool";textSize=16f;setTypeface(null,android.graphics.Typeface.BOLD);setTextColor(Color.WHITE)
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
            addView(TextView(ctx).apply{text="v3";textSize=9f;setTextColor(Color.parseColor("#00D4FF"))
                background=ctx.getDrawable(R.drawable.bg_pill);setPadding(10,3,10,3)})})
        root.addView(hdiv(ctx))

        root.addView(sec(ctx,"RING DETECTION"))
        root.addView(LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(20,8,20,0)
            addView(TextView(ctx).apply{text="Mesh:";textSize=11f;setTextColor(Color.parseColor("#9090B0"));setPadding(0,0,10,0)})
            addView(EditText(ctx).apply{inputType=InputType.TYPE_CLASS_NUMBER;setText("$targetMeshIdx")
                setTextColor(Color.WHITE);textSize=13f;background=ctx.getDrawable(R.drawable.bg_input_field);setPadding(10,6,10,6)
                layoutParams=LinearLayout.LayoutParams(80,LinearLayout.LayoutParams.WRAP_CONTENT)
                addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                    override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                    override fun afterTextChanged(s:Editable?){targetMeshIdx=s?.toString()?.toIntOrNull()?:0}})})
            addView(TextView(ctx).apply{text="  (auto-uses selected mesh)";textSize=9f;setTextColor(Color.parseColor("#404060"))})})

        val btnDetect=Button(ctx).apply{text="▶  Detect Ring Geometry";textSize=12f;setTextColor(Color.parseColor("#050508"))
            setTypeface(null,android.graphics.Typeface.BOLD);background=ctx.getDrawable(R.drawable.bg_btn_accent)
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,52).apply{setMargins(20,12,20,0)}}
        root.addView(btnDetect)

        tvStatus=TextView(ctx).apply{text="⚠  No ring detected yet";textSize=10f;setTextColor(Color.parseColor("#FF7043"));setPadding(20,10,20,2)}
        root.addView(tvStatus)
        tvInfo=TextView(ctx).apply{text="";textSize=10f;setTextColor(Color.parseColor("#606080"));setPadding(20,0,20,6)}
        root.addView(tvInfo)
        root.addView(hdiv(ctx))

        val bwCard=buildSliderCard(ctx,"BAND WIDTH","Outer wall expands · Inner bore fixed","#00D4FF",
            onSb={sb->sbBW=sb}, onEt={et->etBW=et}, onInfo={tv->tvBwInfo=tv}){v->
            if(ringAnalyzed&&v!=lastBWMM){lastBWMM=v
                glRun{NativeLib.nativeSetRingBandWidth(v)}; glReq()
                activity?.runOnUiThread{tvBwInfo?.text="Band: %.2fmm  →  outer ⌀ %.2fmm".format(v,(origInnerDiaMM/2f+v)*2f)
                    activity?.sendBroadcast(android.content.Intent(EditorPanelFragment.ACTION_DIMS_CHANGED))}}
        }
        cardBW=bwCard; bwCard.visibility=View.GONE; root.addView(bwCard)
        root.addView(View(ctx).apply{layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,6)})

        val idCard=buildSliderCard(ctx,"INNER DIAMETER","Inner bore changes · Band width stays","#FF9800",
            onSb={sb->sbID=sb}, onEt={et->etID=et}, onInfo={tv->tvIdInfo=tv}){v->
            if(ringAnalyzed&&v!=lastIDMM){lastIDMM=v
                glRun{NativeLib.nativeSetRingInnerDiameter(v)}; glReq()
                activity?.runOnUiThread{tvIdInfo?.text="Inner ⌀: %.2fmm  →  US ~%.1f".format(v,diamToUS(v))
                    activity?.sendBroadcast(android.content.Intent(EditorPanelFragment.ACTION_DIMS_CHANGED))}}
        }
        cardID=idCard; idCard.visibility=View.GONE; root.addView(idCard)
        root.addView(hdiv(ctx))

        root.addView(LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;setPadding(20,12,20,0)
            addView(Button(ctx).apply{text="↺ Reset";textSize=11f;setTextColor(Color.parseColor("#FF7043"))
                background=ctx.getDrawable(R.drawable.bg_btn_danger);setPadding(20,0,20,0)
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,44).apply{setMargins(0,0,12,0)}
                setOnClickListener{glRun{NativeLib.nativeResetRingDeformation()
                    val p=NativeLib.nativeGetRingParams()
                    activity?.runOnUiThread{if(p.size>=6)applyParams(p)}}}})
            addView(Button(ctx).apply{text="Re-Detect";textSize=11f;setTextColor(Color.parseColor("#9090B0"))
                background=ctx.getDrawable(R.drawable.bg_card_dark);setPadding(20,0,20,0)
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,44)
                setOnClickListener{btnDetect.performClick()}})})

        btnDetect.setOnClickListener{
            ringAnalyzed=false; cardBW?.visibility=View.GONE; cardID?.visibility=View.GONE
            val idx=if(targetMeshIdx<0) 0 else targetMeshIdx
            tvStatus?.text="⏳  Detecting on mesh #$idx…"; tvStatus?.setTextColor(Color.parseColor("#FFD54F")); tvInfo?.text=""
            glRun{val ok=NativeLib.nativeAnalyzeRing(idx)
                if(ok){val p=NativeLib.nativeGetRingParams()
                    activity?.runOnUiThread{if(p.size>=6)applyParams(p)
                    else{tvStatus?.text="✗ Param error";tvStatus?.setTextColor(Color.parseColor("#FF5252"))}}}
                else activity?.runOnUiThread{tvStatus?.text="✗ Mesh #$idx not a ring shape"
                    tvStatus?.setTextColor(Color.parseColor("#FF5252"))}}
        }
        return scroll
    }

    private fun applyParams(p:FloatArray){
        origBandWidthMM=p[2]; origInnerDiaMM=p[3]; lastBWMM=p[2]; lastIDMM=p[3]
        bwMin=(p[2]*0.1f).coerceAtLeast(0.05f); bwMax=(p[2]*3.5f).coerceAtMost(50f)
        idMin=(p[3]*0.5f).coerceAtLeast(0.5f);   idMax=(p[3]*2.0f).coerceAtMost(80f)
        ringAnalyzed=true
        tvStatus?.text="✓ Ring detected"; tvStatus?.setTextColor(Color.parseColor("#4CAF82"))
        tvInfo?.text="Inner ⌀ %.2fmm  ·  Outer ⌀ %.2fmm  ·  Band %.2fmm  ·  H %.2fmm".format(p[3],p[4],p[2],p[5])
        setSlider(sbBW,etBW,p[2],bwMin,bwMax,"BW")
        tvBwInfo?.text="Band: %.2fmm  →  outer ⌀ %.2fmm".format(p[2],p[4])
        cardBW?.visibility=View.VISIBLE
        setSlider(sbID,etID,p[3],idMin,idMax,"ID")
        tvIdInfo?.text="Inner ⌀: %.2fmm  →  US ~%.1f".format(p[3],diamToUS(p[3]))
        cardID?.visibility=View.VISIBLE
    }

    private fun setSlider(sb:SeekBar?,et:EditText?,v:Float,min:Float,max:Float,tok:String){
        val p=((v-min)/(max-min)*STEPS).toInt().coerceIn(0,STEPS)
        val t="%.2f".format(v)
        when(tok){"BW"->{suppressBW=true;sb?.progress=p;et?.setText(t);suppressBW=false}
                   "ID"->{suppressID=true;sb?.progress=p;et?.setText(t);suppressID=false}}
    }

    private fun buildSliderCard(ctx:android.content.Context,title:String,sub:String,hex:String,
        onSb:(SeekBar)->Unit,onEt:(EditText)->Unit,onInfo:(TextView)->Unit,onChange:(Float)->Unit):LinearLayout{
        val accent=Color.parseColor(hex)
        val card=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_dark);setPadding(0,0,0,16)
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{setMargins(14,4,14,0)}}
        card.addView(TextView(ctx).apply{text=title;textSize=9f;letterSpacing=0.14f;setTextColor(Color.parseColor(hex));setPadding(16,14,16,0)})
        card.addView(TextView(ctx).apply{text=sub;textSize=9f;setTextColor(Color.parseColor("#505070"));setPadding(16,3,16,8)})
        val row=LinearLayout(ctx).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(16,0,16,0)}
        val et=EditText(ctx).apply{inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("0.00");setTextColor(Color.WHITE);textSize=18f;setTypeface(null,android.graphics.Typeface.BOLD)
            background=ctx.getDrawable(R.drawable.bg_input_field);setPadding(14,10,14,10)
            layoutParams=LinearLayout.LayoutParams(150,LinearLayout.LayoutParams.WRAP_CONTENT)}
        onEt(et); row.addView(et)
        row.addView(TextView(ctx).apply{text=" mm";textSize=12f;setTextColor(Color.parseColor("#505070"))})
        card.addView(row)
        val sb=SeekBar(ctx).apply{setMax(STEPS);progress=0
            progressTintList=android.content.res.ColorStateList.valueOf(accent)
            thumbTintList=android.content.res.ColorStateList.valueOf(accent);setPadding(16,8,16,0)}
        onSb(sb); card.addView(sb)
        val tvI=TextView(ctx).apply{text="";textSize=10f;setTextColor(Color.parseColor("#606080"));setPadding(16,6,16,0)}
        onInfo(tvI); card.addView(tvI)

        val isBW=hex=="#00D4FF"
        sb.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(b:SeekBar){}; override fun onStopTrackingTouch(b:SeekBar){}
            override fun onProgressChanged(b:SeekBar,p:Int,fromUser:Boolean){
                if(!fromUser||!ringAnalyzed) return
                val min=if(isBW)bwMin else idMin; val max=if(isBW)bwMax else idMax
                val v=min+p.toFloat()/STEPS*(max-min); val t="%.2f".format(v)
                if(isBW){if(!suppressBW){suppressBW=true;et.setText(t);suppressBW=false}}
                else{if(!suppressID){suppressID=true;et.setText(t);suppressID=false}}
                onChange(v)}})
        et.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
            override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
            override fun afterTextChanged(s:Editable?){
                if(!ringAnalyzed) return
                if(isBW&&suppressBW) return; if(!isBW&&suppressID) return
                val v=et.text.toString().toFloatOrNull()?:return
                val min=if(isBW)bwMin else idMin; val max=if(isBW)bwMax else idMax
                if(v<min*0.5f||v>max*2f) return
                val p=((v.coerceIn(min,max)-min)/(max-min)*STEPS).toInt().coerceIn(0,STEPS)
                if(isBW){suppressBW=true;sb.progress=p;suppressBW=false}
                else{suppressID=true;sb.progress=p;suppressID=false}
                onChange(v.coerceIn(min,max))}})
        return card
    }

    private fun diamToUS(d:Float)=((d*Math.PI.toFloat()-36.5f)/2.55f).coerceAtLeast(0f)
    private fun handle(ctx:android.content.Context)=LinearLayout(ctx).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL;setPadding(0,14,0,0)
        addView(View(ctx).apply{setBackgroundColor(Color.parseColor("#404058"));layoutParams=LinearLayout.LayoutParams(48,4)})}
    private fun sec(ctx:android.content.Context,t:String)=TextView(ctx).apply{text=t;textSize=9f;letterSpacing=0.14f;setTextColor(Color.parseColor("#00D4FF"));setPadding(20,18,20,6)}
    private fun hdiv(ctx:android.content.Context)=View(ctx).apply{setBackgroundColor(Color.parseColor("#1A1A28"));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)}
    private fun glRun(block:()->Unit)=(activity as? MainActivity)?.glView?.queueEvent(block)
    private fun glReq()=(activity as? MainActivity)?.glView?.requestRender()

    override fun onDestroyView(){view?.removeCallbacks(broadcastDimsRunnable);super.onDestroyView()}

    companion object{
        const val TAG="RingTool"
        fun newInstance(meshIdx:Int=-1)=RingToolFragment().apply{if(meshIdx>=0)arguments=android.os.Bundle().apply{putInt("meshIdx",meshIdx)}}
    }
}
