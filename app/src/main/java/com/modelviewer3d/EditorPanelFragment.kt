package com.modelviewer3d

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class EditorPanelFragment : BottomSheetDialogFragment() {

    private var origWmm = 0f; private var origHmm = 0f; private var origDmm = 0f
    private var curWmm  = 0f; private var curHmm  = 0f; private var curDmm  = 0f
    private var colR = 0.72f; private var colG = 0.72f; private var colB = 0.92f
    private var ambient = 0.3f; private var diffuse = 0.8f
    private var uniformScale = false

    private var etW: EditText? = null
    private var etH: EditText? = null
    private var etD: EditText? = null
    private var tvDimStatus: TextView? = null
    private var suppressTextChange = false

    private val dimsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) { refreshDimensions() }
    }

    override fun onStart() {
        super.onStart()
        val f = android.content.IntentFilter(ACTION_DIMS_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            requireContext().registerReceiver(dimsReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") requireContext().registerReceiver(dimsReceiver, f)
        refreshDimensions()
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(dimsReceiver) } catch (_: Exception) {}
    }

    fun refreshDimensions() {
        (activity as? MainActivity)?.glView?.queueEvent {
            try {
                val s = NativeLib.nativeGetModelSizeMM()
                val ow=s[0]; val oh=s[1]; val od=s[2]
                val cw=s[3]; val ch=s[4]; val cd=s[5]
                activity?.runOnUiThread {
                    origWmm=ow; origHmm=oh; origDmm=od
                    curWmm=cw; curHmm=ch; curDmm=cd
                    tvDimStatus?.text = "Original: %.1fmm × %.1fmm × %.1fmm".format(ow,oh,od)
                    silentSet(etW,cw); silentSet(etH,ch); silentSet(etD,cd)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        scroll.addView(root)

        root.addView(LinearLayout(ctx).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL; setPadding(0,14,0,0)
            addView(View(ctx).apply { setBackgroundColor(Color.parseColor("#404058"))
                layoutParams = LinearLayout.LayoutParams(48,4) })
        })

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(20,14,16,8)
            addView(TextView(ctx).apply {
                text = "✏️  Edit / Material"; textSize = 17f
                setTypeface(null,android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            })
            addView(Button(ctx).apply {
                text = "↻  Refresh"; textSize = 11f; setTextColor(Color.parseColor("#00D4FF"))
                background = ctx.getDrawable(R.drawable.bg_card_dark)
                setPadding(20,0,20,0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,40)
                setOnClickListener { refreshDimensions() }
            })
        })
        root.addView(hdiv(ctx))

        root.addView(sec(ctx,"📐  DIMENSIONS"))
        val tvSt = TextView(ctx).apply {
            text = "Tap ↻ Refresh to load current size"; textSize=10f
            setTextColor(Color.parseColor("#505070")); setPadding(20,0,20,4)
        }
        tvDimStatus = tvSt; root.addView(tvSt)
        root.addView(sw(ctx,"Uniform Scale",false){v->uniformScale=v})

        fun dimRow(lbl:String, getOrig:()->Float): EditText {
            val row = LinearLayout(ctx).apply {
                orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL; setPadding(20,6,20,6)
            }
            row.addView(TextView(ctx).apply { text=lbl; textSize=12f; setTextColor(Color.parseColor("#9090B0"))
                layoutParams=LinearLayout.LayoutParams(28,LinearLayout.LayoutParams.WRAP_CONTENT) })
            val et = EditText(ctx).apply {
                inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText("–"); setTextColor(Color.WHITE); textSize=15f
                setTypeface(null,android.graphics.Typeface.BOLD)
                background=ctx.getDrawable(R.drawable.bg_input_field); setPadding(14,10,14,10)
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
            }
            row.addView(et)
            row.addView(TextView(ctx).apply{text=" mm";textSize=11f;setTextColor(Color.parseColor("#505070"))})
            root.addView(row)
            et.addTextChangedListener(object:TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
                override fun afterTextChanged(s:Editable?){
                    if(suppressTextChange) return
                    if(origWmm<0.001f||origHmm<0.001f||origDmm<0.001f) return
                    val v=s?.toString()?.toFloatOrNull()?:return; if(v<0.001f) return
                    if(uniformScale){
                        val o=getOrig(); if(o<0.001f) return
                        val r=v/o; val nw=origWmm*r; val nh=origHmm*r; val nd=origDmm*r
                        when(lbl){"W"->{silentSet(etH,nh);silentSet(etD,nd)}
                                   "H"->{silentSet(etW,nw);silentSet(etD,nd)}
                                   "D"->{silentSet(etW,nw);silentSet(etH,nh)}}
                        glRun{NativeLib.nativeSetScaleMM(nw,nh,nd)}
                    } else {
                        val wv=etW?.text?.toString()?.toFloatOrNull()?.takeIf{it>0.001f}?:origWmm
                        val hv=etH?.text?.toString()?.toFloatOrNull()?.takeIf{it>0.001f}?:origHmm
                        val dv=etD?.text?.toString()?.toFloatOrNull()?.takeIf{it>0.001f}?:origDmm
                        glRun{NativeLib.nativeSetScaleMM(wv,hv,dv)}
                    }
                }
            })
            return et
        }
        etW=dimRow("W"){origWmm}; etH=dimRow("H"){origHmm}; etD=dimRow("D"){origDmm}

        root.addView(LinearLayout(ctx).apply {
            orientation=LinearLayout.HORIZONTAL; setPadding(20,8,20,8)
            addView(Button(ctx).apply{
                text="↩  Reset Size"; textSize=11f; setTextColor(Color.parseColor("#00D4FF"))
                background=ctx.getDrawable(R.drawable.bg_card_dark); setPadding(20,0,20,0)
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,40)
                setOnClickListener{if(origWmm>0.001f){silentSet(etW,origWmm);silentSet(etH,origHmm);silentSet(etD,origDmm);glRun{NativeLib.nativeSetScaleMM(origWmm,origHmm,origDmm)}}}
            })
        })
        root.addView(hdiv(ctx))

        root.addView(sec(ctx,"↔  FLIP"))
        root.addView(LinearLayout(ctx).apply{
            orientation=LinearLayout.HORIZONTAL; setPadding(14,6,14,10)
            for((l,fn) in listOf("Flip X" to {glRun{NativeLib.nativeMirrorX()}},"Flip Y" to {glRun{NativeLib.nativeMirrorY()}},"Flip Z" to {glRun{NativeLib.nativeMirrorZ()}})){
                addView(Button(ctx).apply{text=l;textSize=11f;setTextColor(Color.parseColor("#9090B0"))
                    background=ctx.getDrawable(R.drawable.bg_card_dark)
                    layoutParams=LinearLayout.LayoutParams(0,44,1f).apply{setMargins(4,0,4,0)}
                    setOnClickListener{fn()}})
            }
        })
        root.addView(hdiv(ctx))

        root.addView(sec(ctx,"🎨  COLOR"))
        fun colorSlider(label:String,tint:String,init:()->Float,onChg:(Float)->Unit){
            root.addView(LinearLayout(ctx).apply{
                orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(20,4,20,4)
                addView(TextView(ctx).apply{text=label;textSize=11f;setTextColor(Color.parseColor("#9090B0"))
                    layoutParams=LinearLayout.LayoutParams(24,LinearLayout.LayoutParams.WRAP_CONTENT)})
                addView(SeekBar(ctx).apply{
                    setMax(100);progress=(init()*100).toInt()
                    progressTintList=android.content.res.ColorStateList.valueOf(Color.parseColor(tint))
                    thumbTintList=android.content.res.ColorStateList.valueOf(Color.parseColor(tint))
                    layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                        override fun onStartTrackingTouch(b:SeekBar){}
                        override fun onStopTrackingTouch(b:SeekBar){}
                        override fun onProgressChanged(b:SeekBar,p:Int,fromUser:Boolean){if(fromUser)onChg(p/100f)}})})})
        }
        colorSlider("R","#FF5252",{colR}){v->colR=v;glRun{NativeLib.nativeSetColor(colR,colG,colB)}}
        colorSlider("G","#4CAF82",{colG}){v->colG=v;glRun{NativeLib.nativeSetColor(colR,colG,colB)}}
        colorSlider("B","#4FC3F7",{colB}){v->colB=v;glRun{NativeLib.nativeSetColor(colR,colG,colB)}}
        root.addView(hdiv(ctx))

        root.addView(sec(ctx,"💡  MATERIAL"))
        for((lbl,get,set) in listOf<Triple<String,()->Float,(Float)->Unit>>(
            Triple("Ambient",{ambient}){v->ambient=v;glRun{NativeLib.nativeSetAmbient(v)}},
            Triple("Diffuse",{diffuse}){v->diffuse=v;glRun{NativeLib.nativeSetDiffuse(v)}}
        )){
            root.addView(LinearLayout(ctx).apply{
                orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL; setPadding(20,4,20,4)
                addView(TextView(ctx).apply{text=lbl;textSize=11f;setTextColor(Color.parseColor("#9090B0"))
                    layoutParams=LinearLayout.LayoutParams(90,LinearLayout.LayoutParams.WRAP_CONTENT)})
                addView(SeekBar(ctx).apply{
                    setMax(100); progress=(get()*100).toInt()
                    progressTintList=android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
                    thumbTintList=android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
                    layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                        override fun onStartTrackingTouch(b:SeekBar){}
                        override fun onStopTrackingTouch(b:SeekBar){}
                        override fun onProgressChanged(b:SeekBar,p:Int,fromUser:Boolean){if(fromUser)set(p/100f)}
                    })
                })
            })
        }
        root.addView(hdiv(ctx))

        root.addView(sec(ctx,"🖥  DISPLAY"))
        root.addView(sw(ctx,"Wireframe",false){on->glRun{NativeLib.nativeSetWireframe(on)}})
        root.addView(sw(ctx,"Bounding Box",false){on->glRun{NativeLib.nativeSetBoundingBox(on)}})
        root.addView(View(ctx).apply{layoutParams=LinearLayout.LayoutParams(0,16)})

        root.addView(LinearLayout(ctx).apply{
            orientation=LinearLayout.HORIZONTAL; setPadding(14,0,14,28)
            addView(Button(ctx).apply{
                text="⟳  Reset All Transforms"; textSize=11f; setTextColor(Color.parseColor("#FF5252"))
                background=ctx.getDrawable(R.drawable.bg_btn_danger); setPadding(20,0,20,0)
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,44)
                setOnClickListener{
                    glRun{NativeLib.nativeResetAllTransforms();NativeLib.nativeResetCamera()}
                    activity?.sendBroadcast(android.content.Intent(ACTION_DIMS_CHANGED))
                }
            })
        })
        return scroll
    }

    private fun sec(ctx: android.content.Context, text: String) = TextView(ctx).apply {
        this.text=text; textSize=11f; letterSpacing=0.05f
        setTypeface(null,android.graphics.Typeface.BOLD)
        setTextColor(Color.parseColor("#00D4FF")); setPadding(20,16,20,6)
    }
    private fun hdiv(ctx: android.content.Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#1A1A28"))
        layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,1)
    }
    private fun sw(ctx: android.content.Context, label: String, default: Boolean, cb: (Boolean)->Unit) =
        LinearLayout(ctx).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL; setPadding(20,8,20,8)
            addView(TextView(ctx).apply{text=label;textSize=12f;setTextColor(Color.WHITE)
                layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)})
            addView(Switch(ctx).apply{isChecked=default
                thumbTintList=android.content.res.ColorStateList.valueOf(Color.parseColor("#00D4FF"))
                trackTintList=android.content.res.ColorStateList.valueOf(Color.parseColor("#1A3D50"))
                setOnCheckedChangeListener{_,v->cb(v)}})
        }
    private fun silentSet(et:EditText?,value:Float){
        et?:return; val t="%.2f".format(value)
        if(et.text?.toString()!=t){suppressTextChange=true;et.setText(t);suppressTextChange=false}
    }
    private fun glRun(block:()->Unit)=(activity as? MainActivity)?.glView?.queueEvent(block)

    companion object {
        const val TAG = "EditorPanel"
        const val ACTION_DIMS_CHANGED = "com.modelviewer3d.DIMS_CHANGED"
        fun newInstance() = EditorPanelFragment()
    }
}
