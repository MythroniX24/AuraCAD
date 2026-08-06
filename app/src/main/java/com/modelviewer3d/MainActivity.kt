package com.modelviewer3d

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.Channels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch

class MainActivity : AppCompatActivity() {

    lateinit var glView: ModelGLSurfaceView
    private lateinit var renderer: ModelRenderer

    private var btnAi: View? = null
    private var tvFps: TextView? = null
    private var tvHint: View? = null
    private var loadingOverlay: View? = null
    private var tvLoading: TextView? = null
    private var tvLoadingDetail: TextView? = null
    private var rulerOverlay: View? = null
    private var tvRulerInfo: TextView? = null
    private var btnRuler: View? = null
    private var statusBar: View? = null
    private var tvStatusMeshes: TextView? = null
    private var tvStatusVerts: TextView? = null
    private var tvStatusFile: TextView? = null

    // Selection chip
    private var selectionChip: View? = null
    private var tvSelectionLabel: TextView? = null

    // Bottom-toolbar tool buttons
    private var btnToolSelect: View? = null
    private var btnToolMove:   View? = null
    private var btnToolRotate: View? = null
    private var btnToolScale:  View? = null
    private var btnToolRing:   View? = null

    private var icToolSelect: ImageView? = null
    private var icToolMove:   ImageView? = null
    private var icToolRotate: ImageView? = null
    private var icToolScale:  ImageView? = null
    private var icToolRing:   ImageView? = null
    private var lblToolSelect: TextView? = null
    private var lblToolMove:   TextView? = null
    private var lblToolRotate: TextView? = null
    private var lblToolScale:  TextView? = null
    private var lblToolRing:   TextView? = null

    private enum class Tool { NONE, SELECT, MOVE, ROTATE, SCALE, RING }
    private var activeTool: Tool = Tool.NONE
    private var selectedMeshIdx: Int = -1

    private var rulerPoint1: FloatArray? = null
    private var rulerPoint2: FloatArray? = null
    private var rulerActive = false
    var currentFileName = ""

    // App-scoped loader — deliberately NOT lifecycleScope: if the user presses
    // back / backgrounds the app mid-load, parsing keeps running and a
    // notification announces completion.  lifecycleScope would cancel the job.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var loadingInBackground = false

    private var btnUndo: View? = null
    private var btnRedo: View? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument())
    { uri -> uri?.let { openModelFromUri(it) } }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
    { r -> if (r.values.all { it }) launchFilePicker() else toast("Storage permission required") }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0x00000000

        try {
            setContentView(R.layout.activity_main)

            glView           = findViewById(R.id.glSurface)
            tvFps            = findViewById(R.id.tvFps)
            tvHint           = findViewById(R.id.tvHint)
            loadingOverlay   = findViewById(R.id.loadingOverlay)
            tvLoading        = findViewById(R.id.tvLoading)
            tvLoadingDetail  = findViewById(R.id.tvLoadingDetail)
            rulerOverlay     = findViewById(R.id.rulerOverlay)
            tvRulerInfo      = findViewById(R.id.tvRulerInfo)
            btnRuler         = findViewById(R.id.btnRuler)
            statusBar        = findViewById(R.id.statusBar)
            tvStatusMeshes   = findViewById(R.id.tvStatusMeshes)
            tvStatusVerts    = findViewById(R.id.tvStatusVerts)
            tvStatusFile     = findViewById(R.id.tvStatusFile)

            selectionChip     = findViewById(R.id.selectionChip)
            tvSelectionLabel  = findViewById(R.id.tvSelectionLabel)

            btnToolSelect = findViewById(R.id.btnToolSelect)
            btnToolMove   = findViewById(R.id.btnToolMove)
            btnToolRotate = findViewById(R.id.btnToolRotate)
            btnToolScale  = findViewById(R.id.btnToolScale)
            btnToolRing   = findViewById(R.id.btnToolRing)
            icToolSelect = findViewById(R.id.icToolSelect)
            icToolMove   = findViewById(R.id.icToolMove)
            icToolRotate = findViewById(R.id.icToolRotate)
            icToolScale  = findViewById(R.id.icToolScale)
            icToolRing   = findViewById(R.id.icToolRing)
            lblToolSelect = findViewById(R.id.lblToolSelect)
            lblToolMove   = findViewById(R.id.lblToolMove)
            lblToolRotate = findViewById(R.id.lblToolRotate)
            lblToolScale  = findViewById(R.id.lblToolScale)
            lblToolRing   = findViewById(R.id.lblToolRing)

            renderer = ModelRenderer()
            renderer.onFpsUpdate = { fps ->
                runOnUiThread { tvFps?.text = "%.0f".format(fps) }
            }
            glView.attachRenderer(renderer)
            glView.onRulerPick = { pt -> onRulerPointPicked(pt) }
            // Long-press selection: pick → toast → broadcast so any open
            // editor (Transform Tool, Ring Tool, …) re-targets the new mesh.
            glView.onMeshLongPressPick = { idx -> onMeshLongPressPicked(idx) }

            // ── LEGACY toolbar wiring (these views are now hidden in the
            //    layout; the overflow ⋯ menu and bottom toolbar trigger them
            //    via View.performClick(). The wiring stays identical so all
            //    existing features keep working unchanged.) ──────────────────
            findViewById<View>(R.id.btnOpen).setOnClickListener       { requestOpenFile() }
            findViewById<View>(R.id.btnEdit).setOnClickListener       { openEditor() }
            findViewById<View>(R.id.btnMeshList).setOnClickListener   { openMeshList() }
            btnRuler?.setOnClickListener                              { toggleRulerMode() }
            findViewById<View>(R.id.btnRingTool).setOnClickListener   { openRingTool() }
            findViewById<View>(R.id.btnMeshTools).setOnClickListener  { openMeshTools() }
            findViewById<View>(R.id.btnScreenshot).setOnClickListener { takeScreenshot() }

            // ── Top-bar primary actions ──────────────────────────────────────
            btnUndo = findViewById(R.id.btnUndo)
            btnRedo = findViewById(R.id.btnRedo)
            btnUndo?.setOnClickListener  {
                glView.queueEvent { NativeLib.nativeUndo(); refreshUndoRedoButtons() }
            }
            btnRedo?.setOnClickListener  {
                glView.queueEvent { NativeLib.nativeRedo(); refreshUndoRedoButtons() }
            }
            findViewById<View>(R.id.btnReset).setOnClickListener { glView.queueEvent { NativeLib.nativeResetCamera() } }

            // Dim/enable undo+redo according to the native stacks. Polled because
            // nearly every tool pushes state from the GL thread.
            lifecycleScope.launch {
                while (isActive) {
                    refreshUndoRedoButtons()
                    delay(600)
                }
            }

            // ── AI Assistant button ──────────────────────────────────────────
            btnAi = findViewById(R.id.btnAi)
            btnAi?.setOnClickListener { openAiSettings() }

            // ── Top-bar overflow menu ────────────────────────────────────────
            findViewById<View>(R.id.btnOverflow).setOnClickListener { showOverflowMenu(it) }

            // ── Bottom-toolbar tool buttons ──────────────────────────────────
            btnToolSelect?.setOnClickListener { onToolClicked(Tool.SELECT) }
            btnToolMove  ?.setOnClickListener { onToolClicked(Tool.MOVE)   }
            btnToolRotate?.setOnClickListener { onToolClicked(Tool.ROTATE) }
            btnToolScale ?.setOnClickListener { onToolClicked(Tool.SCALE)  }
            btnToolRing  ?.setOnClickListener { onToolClicked(Tool.RING)   }

            // ── Selection chip ───────────────────────────────────────────────
            findViewById<View?>(R.id.btnSelectionClear)?.setOnClickListener { clearSelection() }

            // ── Misc ─────────────────────────────────────────────────────────
            findViewById<View?>(R.id.btnClearRuler)?.setOnClickListener { clearRuler() }
            findViewById<View?>(R.id.btnOpenHint)?.setOnClickListener { requestOpenFile() }

            updateToolButtons()

            // Listen for selection changes to update the chip
            val selFilter = IntentFilter(ACTION_SELECTED_MESH_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(selectionChangedReceiver, selFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(selectionChangedReceiver, selFilter)
            }

            // Back = background the app while a model is loading; the parse keeps
            // running on appScope and a notification fires on completion.
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (loadingOverlay?.visibility == View.VISIBLE) {
                        loadingInBackground = true
                        hideLoading()
                        notifyLoad("AuraCAD", "Loading $currentFileName in background…", progress = true)
                        moveTaskToBack(true)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            })

            // Handle: opened via file manager (ACTION_VIEW)
            if (intent?.action == Intent.ACTION_VIEW) {
                intent?.data?.let { openModelFromUri(it) }
            }
            // Handle: file shared TO this app (ACTION_SEND from WhatsApp/Telegram/etc.)
            else if (intent?.action == Intent.ACTION_SEND) {
                val uri = if (Build.VERSION.SDK_INT >= 33)
                    intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                uri?.let { openModelFromUri(it) }
            }

        } catch (e: Exception) {
            toast("Init error: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { openModelFromUri(it) }
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                uri?.let { openModelFromUri(it) }
            }
        }
    }

    override fun onResume()  { super.onResume();  glView.onResume()
        // Ensure a frame renders after returning to the app — covers the
        // background-load path where the upload event was queued while the
        // GL thread was paused (it runs on resume, and this triggers the draw).
        glView.requestRender()
    }
    override fun onPause()   { super.onPause();   glView.onPause()   }
    override fun onDestroy() {
        try { unregisterReceiver(selectionChangedReceiver)  } catch (_: Exception) {}
        glView.queueEvent { NativeLib.nativeDestroy() }
        super.onDestroy()
    }

    // ── Top-bar overflow menu (legacy actions) ────────────────────────────────
    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Open Model…")
        popup.menu.add(0, 2, 1, "Edit / Materials")
        popup.menu.add(0, 3, 2, "Mesh List")
        popup.menu.add(0, 4, 3, "⬡ Mesh Separation")
        popup.menu.add(0, 5, 4, if (rulerActive) "Disable Ruler" else "Ruler")
        popup.menu.add(0, 6, 5, "Screenshot")
        popup.menu.add(0, 7, 6, "💾 Export…")
        popup.menu.add(0, 8, 7, "🛠 Transform Panel")
        popup.menu.add(0, 9, 8, "✨ AI Assistant")
        popup.menu.add(0, 10, 9, "🔋 Battery Optimization")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> findViewById<View>(R.id.btnOpen).performClick()
                2 -> findViewById<View>(R.id.btnEdit).performClick()
                3 -> findViewById<View>(R.id.btnMeshList).performClick()
                4 -> openSeparation()
                5 -> btnRuler?.performClick()
                6 -> findViewById<View>(R.id.btnScreenshot).performClick()
                7 -> openExport()
                8 -> openMeshTools()
                9 -> openAiSettings()
                10 -> requestBatteryOptimizationExemption()
            }
            true
        }
        popup.show()
    }

    // ── Bottom-toolbar tool dispatch ──────────────────────────────────────────
    private fun onToolClicked(tool: Tool) {
        // Toggle off if same tool tapped again
        val newTool = if (activeTool == tool) Tool.NONE else tool
        activeTool = newTool
        updateToolButtons()

        when (newTool) {
            Tool.SELECT -> {
                if (selectedMeshIdx < 0) {
                    toast("Long-press a mesh on the canvas to select it")
                }
            }
            Tool.MOVE, Tool.ROTATE, Tool.SCALE -> {
                // 3D gizmo on the preview: rotate/move/scale tools manipulate the
                // model directly via the on-canvas axis gizmo (drag to edit).
                val gizmoMode = when (newTool) {
                    Tool.MOVE   -> 1
                    Tool.ROTATE -> 2
                    else        -> 3
                }
                glView.gizmoTool = gizmoMode
                glView.queueEvent { NativeLib.nativeSetGizmoMode(gizmoMode) }
                toast("Drag on the canvas to ${toolHint(newTool)} the model")
            }
            Tool.RING -> {
                glView.gizmoTool = 0
                glView.queueEvent { NativeLib.nativeSetGizmoMode(0) }
                findViewById<View>(R.id.btnRingTool).performClick()
            }
            Tool.NONE -> {
                glView.gizmoTool = 0
                glView.queueEvent { NativeLib.nativeSetGizmoMode(0) }
            }
        }
    }

    private fun toolHint(tool: Tool): String = when (tool) {
        Tool.MOVE   -> "move"
        Tool.ROTATE -> "rotate"
        else        -> "scale"
    }

    private fun updateToolButtons() {
        val pairs = listOf(
            Triple(btnToolSelect, icToolSelect, lblToolSelect) to (activeTool == Tool.SELECT),
            Triple(btnToolMove,   icToolMove,   lblToolMove  ) to (activeTool == Tool.MOVE),
            Triple(btnToolRotate, icToolRotate, lblToolRotate) to (activeTool == Tool.ROTATE),
            Triple(btnToolScale,  icToolScale,  lblToolScale ) to (activeTool == Tool.SCALE),
            Triple(btnToolRing,   icToolRing,   lblToolRing  ) to (activeTool == Tool.RING)
        )
        val activeBg     = ContextCompat.getDrawable(this, R.drawable.bg_tool_button_active)
        val idleBg       = ContextCompat.getDrawable(this, R.drawable.bg_tool_button)
        val activeIcon   = ContextCompat.getColor(this, R.color.tool_active_icon)
        val activeLabel  = ContextCompat.getColor(this, R.color.tool_active_label)
        val idleIcon     = ContextCompat.getColor(this, R.color.tool_idle_icon)
        val idleLabel    = ContextCompat.getColor(this, R.color.tool_idle_label)
        for ((triple, isActive) in pairs) {
            val (btn, icon, label) = triple
            btn?.background = if (isActive) activeBg else idleBg
            icon?.setColorFilter(if (isActive) activeIcon else idleIcon)
            label?.setTextColor(if (isActive) activeLabel else idleLabel)
        }
    }

    // ── Selection chip / state ────────────────────────────────────────────────
    private val selectionChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
            if (intent.action != ACTION_SELECTED_MESH_CHANGED) return
            val idx = intent.getIntExtra("idx", -1)
            selectedMeshIdx = idx
            if (idx < 0) {
                runOnUiThread {
                    selectionChip?.visibility = View.GONE
                    tvSelectionLabel?.text = "No selection"
                }
            } else {
                glView.queueEvent {
                    val name = try { NativeLib.nativeGetMeshName(idx) } catch (_: Exception) { "Mesh #$idx" }
                    runOnUiThread {
                        selectionChip?.visibility = View.VISIBLE
                        tvSelectionLabel?.text = "$name  ·  #$idx"
                    }
                }
            }
        }
    }

    private fun clearSelection() {
        selectedMeshIdx = -1
        selectionChip?.visibility = View.GONE
        tvSelectionLabel?.text = "No selection"
        // Tell native renderer to drop selection highlight
        glView.queueEvent {
            try { NativeLib.nativeSelectMesh(-1) } catch (_: Exception) {}
        }
        // Notify listeners
        sendBroadcast(android.content.Intent(ACTION_SELECTED_MESH_CHANGED)
            .putExtra("idx", -1)
            .setPackage(packageName))
    }

    // ── Status Bar ───────────────────────────────────────────────────────────
    fun updateStatusBar() {
        glView.queueEvent {
            val meshCount = NativeLib.nativeGetMeshCount()
            var totalVerts = 0
            for (i in 0 until meshCount) totalVerts += NativeLib.nativeGetMeshVertexCount(i)
            val mc = meshCount; val tv = totalVerts
            runOnUiThread {
                statusBar?.visibility = View.VISIBLE
                tvStatusMeshes?.text = "● $mc mesh${if (mc != 1) "es" else ""}"
                tvStatusVerts?.text  = "${formatNum(tv)} verts"
                tvStatusFile?.text   = currentFileName
            }
        }
    }

    private fun formatNum(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
        n >= 1_000     -> "%.1fK".format(n / 1_000f)
        else           -> n.toString()
    }

    // ── Undo/Redo availability feedback ──────────────────────────────────────
    private fun refreshUndoRedoButtons() {
        val u = try { NativeLib.nativeCanUndo() } catch (_: Exception) { false }
        val r = try { NativeLib.nativeCanRedo() } catch (_: Exception) { false }
        runOnUiThread {
            btnUndo?.alpha = if (u) 1f else 0.28f
            btnRedo?.alpha = if (r) 1f else 0.28f
        }
    }

    // ── Background-load notifications ────────────────────────────────────────
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val chan = NotificationChannel(
                "aura_load", "AuraCAD", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Model loading progress"
                setShowBadge(false); enableVibration(false); setSound(null, null)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }
    }

    private fun notifyLoad(title: String, text: String, progress: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        ensureNotificationChannel()
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nb = NotificationCompat.Builder(this, "aura_load")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (progress) nb.setProgress(100, 0, true).setOngoing(true)
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(9090, nb.build())
        } catch (_: Exception) {}
    }

    // ── Battery optimization ─────────────────────────────────────────────────
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("Already exempt from battery optimization")
            } else {
                try {
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        } else {
            toast("Not needed on this Android version")
        }
    }

    // ── Ruler ─────────────────────────────────────────────────────────────────
    private fun toggleRulerMode() {
        rulerActive = !rulerActive
        glView.mode = if (rulerActive) ModelGLSurfaceView.Mode.RULER else ModelGLSurfaceView.Mode.CAMERA
        rulerOverlay?.visibility = if (rulerActive) View.VISIBLE else View.GONE
        if (!rulerActive) clearRuler()
        else tvRulerInfo?.text = "Tap mesh surface — Point 1"
    }

    private fun onRulerPointPicked(pt: FloatArray) {
        when {
            rulerPoint1 == null -> {
                rulerPoint1 = pt.copyOf()
                tvRulerInfo?.text = "✅ P1 set — tap for Point 2"
                val p1c = pt.copyOf()
                glView.queueEvent { NativeLib.nativeSetRulerPoints(true, p1c, false, null) }
            }
            rulerPoint2 == null -> {
                rulerPoint2 = pt.copyOf()
                val p1 = rulerPoint1!!.copyOf(); val p2 = pt.copyOf()
                glView.queueEvent { NativeLib.nativeSetRulerPoints(true, p1, true, p2) }
                lifecycleScope.launch(Dispatchers.Default) {
                    // Picked points are in world space (includes the current global +
                    // per-mesh transforms).  nativeGetMMPerUnit() returns the CURRENT
                    // mm-per-world-unit conversion (unitToMM / normalizeScale), so the
                    // measured distance always reflects the live model size — even
                    // after the user resizes the model with the Scale/Editor tools.
                    var mmPerUnit = 1f
                    val latch = CountDownLatch(1)
                    glView.queueEvent {
                        try { mmPerUnit = NativeLib.nativeGetMMPerUnit() } catch (_: Exception) {}
                        latch.countDown()
                    }
                    withContext(Dispatchers.IO) { latch.await() }
                    val distMM = if (mmPerUnit > 1e-9f)
                        UnitMath.distanceMM(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], mmPerUnit)
                    else UnitMath.distance3D(p1[0], p1[1], p1[2], p2[0], p2[1], p2[2]) * 100f
                    withContext(Dispatchers.Main) {
                        tvRulerInfo?.text = "📏 %.2f mm  (%.2f cm)".format(distMM, distMM/10f)
                    }
                }
            }
            else -> clearRuler()
        }
    }

    private fun clearRuler() {
        rulerPoint1=null; rulerPoint2=null
        glView.queueEvent { NativeLib.nativeClearRuler() }
        if (rulerActive) tvRulerInfo?.text = "Tap mesh surface — Point 1"
    }

    // ── Export (all supported formats) ────────────────────────────────────────
    // Writes the current model in the requested format to Downloads/AuraCAD
    // (MediaStore on Android 10+, app storage otherwise) and optionally opens
    // the system share sheet. Every exported unit = 1 mm, so files stay
    // correctly sized in every format.
    fun exportModel(formatId: String, share: Boolean) {
        val info = exportInfo(formatId) ?: run {
            toast("Unknown format $formatId"); return
        }
        val (label, ext, mime) = info
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (currentFileName.isEmpty()) {
                    withContext(Dispatchers.Main) { toast("Load a model first") }
                    return@launch
                }
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val base = currentFileName.substringBeforeLast('.', "model")
                val name = "${base}_$ts.$ext"

                val cacheFile = File(cacheDir, name)
                var ok = false
                val latch = CountDownLatch(1)
                glView.queueEvent {
                    try { ok = nativeExportFor(formatId, cacheFile.absolutePath) } catch (_: Exception) {}
                    latch.countDown()
                }
                latch.await()
                if (!ok) {
                    withContext(Dispatchers.Main) { toast("$label export failed") }
                    return@launch
                }

                // 1) MediaStore (Android 10+): public Downloads/AuraCAD
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/AuraCAD")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            cacheFile.inputStream().use { it.copyTo(out) }
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        cacheFile.delete()
                        withContext(Dispatchers.Main) {
                            if (share) { shareUri(uri, mime, name); toast("✅ Exported & ready to share") }
                            else toast("✅ Saved to Downloads/AuraCAD/$name")
                        }
                        return@launch
                    }
                }
                // 2) Android 9- or MediaStore unavailable → app-specific storage
                val dir = File(getExternalFilesDir(null), "AuraCAD")
                dir.mkdirs()
                val out = File(dir, name)
                cacheFile.copyTo(out, overwrite = true)
                cacheFile.delete()
                MediaScannerConnection.scanFile(this@MainActivity, arrayOf(out.absolutePath), null, null)
                withContext(Dispatchers.Main) {
                    if (share) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            this@MainActivity, "$packageName.fileprovider", out)
                        shareUri(uri, mime, name)
                        toast("✅ Exported & ready to share")
                    } else toast("✅ Saved: ${out.absolutePath}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Export error: ${e.message}") }
            }
        }
    }

    /** [label, extension, mime-type] for a format id, or null if unknown. */
    private fun exportInfo(formatId: String): Triple<String, String, String>? = when (formatId) {
        "OBJ" -> Triple("OBJ", "obj", "model/obj")
        "STL" -> Triple("STL", "stl", "model/stl")
        "PLY" -> Triple("PLY", "ply", "model/ply")
        "GLB" -> Triple("GLB", "glb", "model/gltf-binary")
        "3DS" -> Triple("3DS", "3ds", "model/x-3ds")
        "3DM" -> Triple("3DM", "3dm", "model/x-3dm")
        else -> null
    }

    private fun nativeExportFor(formatId: String, path: String): Boolean = when (formatId) {
        "OBJ" -> NativeLib.nativeExportOBJ(path)
        "STL" -> NativeLib.nativeExportSTL(path)
        "PLY" -> NativeLib.nativeExportPLY(path)
        "GLB" -> NativeLib.nativeExportGLB(path)
        "3DS" -> NativeLib.nativeExport3DS(path)
        "3DM" -> NativeLib.nativeExport3DM(path)
        else -> false
    }

    private fun shareUri(uri: Uri, mime: String, name: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_SUBJECT, name)
        }
        startActivity(Intent.createChooser(send, "Share $name"))
    }

    // ── File open ─────────────────────────────────────────────────────────────
    private fun requestOpenFile() {
        if (hasStoragePermission()) launchFilePicker() else requestStoragePermission()
    }
    private fun hasStoragePermission() = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q        -> true
        else -> ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
    private fun requestStoragePermission() {
        permLauncher.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
    }
    private fun launchFilePicker() {
        filePicker.launch(arrayOf(
            "*/*",
            "model/stl", "model/obj", "model/gltf-binary", "model/gltf+json",
            "model/ply", "model/x-3ds", "application/x-3ds", "model/x-3dm",
            "application/octet-stream"
        ))
    }

    fun openModelFromUri(uri: Uri) {
        // appScope (NOT lifecycleScope) — survives back-press/backgrounding so
        // heavy models keep loading in the background with a completion
        // notification.
        appScope.launch(Dispatchers.IO) {
            try {
                // Ask for notification permission upfront (Android 13+)
                withContext(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        if (!nm.areNotificationsEnabled())
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                // Resolve the display name from the URI
                var name = resolveFileName(uri)

                // If no name or unknown extension, try to get from MIME type
                if (name == null || !hasKnownExtension(name)) {
                    val mime = contentResolver.getType(uri) ?: ""
                    val ext  = mimeToExtension(mime)
                    name = if (name != null && ext.isNotEmpty()) "${name.substringBeforeLast('.')}.$ext"
                    else if (ext.isNotEmpty()) "model.$ext"
                    else name ?: "model.stl"
                }

                // Final safety check
                if (!hasKnownExtension(name)) name = "model.stl"

                currentFileName = name

                // GLTF (JSON) isn't supported — only GLB is. Give a clear message
                // instead of a confusing "Failed to parse".
                if (name.lowercase().endsWith(".gltf")) {
                    withContext(Dispatchers.Main) {
                        toast("GLTF (JSON) isn't bundled — please use GLB / STL / OBJ / PLY / 3DS.")
                    }
                    return@launch
                }

                // Delete old cached model files to prevent storage bloat
                cacheDir.listFiles { f -> f.extension.lowercase() in
                    setOf("stl","obj","glb","gltf","ply","3ds","3dm","3mf","fbx","dae") }
                    ?.forEach { it.delete() }

                val dest = File(cacheDir, name)

                // Copy URI content to local cache file (NIO fast transfer)
                contentResolver.openInputStream(uri)?.use { inp ->
                    val src = Channels.newChannel(inp)
                    FileOutputStream(dest).channel.use { dst ->
                        var pos = 0L
                        while (true) { val n = dst.transferFrom(src, pos, 4L*1024*1024); if(n<=0) break; pos+=n }
                    }
                }

                withContext(Dispatchers.Main) {
                    tvHint?.visibility = View.GONE
                    showLoading("Loading $name…", "Parsing model…")
                }

                val parseOk = try { NativeLib.nativeParseModel(dest.absolutePath) } catch (_: Exception) { false }
                if (!parseOk) {
                    withContext(Dispatchers.Main) { hideLoading(); toast("Failed to parse $name") }
                    return@launch
                }
                withContext(Dispatchers.Main) { showLoading("Uploading to GPU…", "Almost ready…") }
                var uploadOk = false
                val latch = CountDownLatch(1)
                glView.queueEvent {
                    try { uploadOk = NativeLib.nativeUploadParsed() } catch (_: Exception) {}
                    latch.countDown()
                }
                // Background-load path: the GL thread is paused while the app is
                // backgrounded, so the queued upload runs on next resume. Don't
                // block the IO coroutine forever — notify from parse success and
                // let the model appear when the user returns.
                if (loadingInBackground) {
                    withContext(Dispatchers.Main) {
                        notifyLoad("AuraCAD", "✅ $name loaded — tap to view")
                    }
                } else {
                    latch.await()
                }
                val wasBackgrounded = loadingInBackground
                withContext(Dispatchers.Main) {
                    hideLoading()
                    loadingInBackground = false
                    if (uploadOk) {
                        if (wasBackgrounded || isFinishing) {
                            notifyLoad("AuraCAD", "✅ $name loaded — tap to view")
                        } else {
                            toast("✓ $name loaded")
                        }
                        updateStatusBar()
                    } else if (wasBackgrounded) {
                        // Upload is queued and will run on next resume — the
                        // model WILL appear, so don't show a false failure toast.
                        updateStatusBar()
                    } else {
                        toast("GPU upload failed")
                    }
                }
            } catch (e: Exception) {
                loadingInBackground = false
                withContext(Dispatchers.Main) { hideLoading(); toast("Error: ${e.message}") }
            }
        }
    }

    private fun hasKnownExtension(name: String): Boolean {
        val low = name.lowercase()
        return low.endsWith(".obj") || low.endsWith(".stl") || low.endsWith(".glb") ||
               low.endsWith(".gltf") || low.endsWith(".ply") || low.endsWith(".3ds") ||
               low.endsWith(".3dm")
    }

    private fun mimeToExtension(mime: String): String = when {
        "stl" in mime || mime == "model/stl"           -> "stl"
        "obj" in mime || mime == "model/obj"            -> "obj"
        "gltf-binary" in mime || mime == "model/gltf-binary" -> "glb"
        "gltf+json" in mime || mime == "model/gltf+json" -> "gltf"
        "ply" in mime || mime == "model/ply"            -> "ply"
        "3ds" in mime                                    -> "3ds"
        "3dm" in mime                                    -> "3dm"
        else -> ""
    }

    private fun resolveFileName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i)
            }
        }
        return uri.lastPathSegment
    }

    // ── Panels ────────────────────────────────────────────────────────────────
    private fun openRingTool() {
        if (supportFragmentManager.findFragmentByTag(RingToolFragment.TAG) != null) return
        RingToolFragment.newInstance().show(supportFragmentManager, RingToolFragment.TAG)
    }
    private fun openMeshTools() {
        if (supportFragmentManager.findFragmentByTag(MeshToolsFragment.TAG) != null) return
        MeshToolsFragment.newInstance().show(supportFragmentManager, MeshToolsFragment.TAG)
    }
    private fun openEditor() {
        if (supportFragmentManager.findFragmentByTag(EditorPanelFragment.TAG) != null) return
        EditorPanelFragment.newInstance().show(supportFragmentManager, EditorPanelFragment.TAG)
    }
    private fun openMeshList() {
        if (supportFragmentManager.findFragmentByTag(MeshListFragment.TAG) != null) return
        MeshListFragment.newInstance().show(supportFragmentManager, MeshListFragment.TAG)
    }
    private fun openSeparation() {
        if (supportFragmentManager.findFragmentByTag(SeparationFragment.TAG) != null) return
        SeparationFragment.newInstance().show(supportFragmentManager, SeparationFragment.TAG)
    }
    private fun openAiSettings() {
        if (supportFragmentManager.findFragmentByTag(AiSettingsFragment.TAG) != null) return
        AiSettingsFragment.newInstance().show(supportFragmentManager, AiSettingsFragment.TAG)
    }
    private fun openExport() {
        if (supportFragmentManager.findFragmentByTag(ExportFragment.TAG) != null) return
        ExportFragment.newInstance().show(supportFragmentManager, ExportFragment.TAG)
    }

    /**
     * Long-press selection result handler.  Already runs on the UI thread
     * (queued by ModelGLSurfaceView via post{}).  Toasts the user and fires a
     * broadcast so any open editor sheet can re-target its controls.
     */
    private fun onMeshLongPressPicked(idx: Int) {
        if (idx < 0) {
            android.widget.Toast.makeText(
                this, "No mesh under your finger", android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            // Cheap label fetch on GL thread, then update UI.
            glView.queueEvent {
                val name = try { NativeLib.nativeGetMeshName(idx) } catch (_: Exception) { "Mesh #$idx" }
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this, "Selected: $name (#$idx)", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        sendBroadcast(android.content.Intent(ACTION_SELECTED_MESH_CHANGED)
            .putExtra("idx", idx)
            .setPackage(packageName))
    }

    companion object {
        /**
         * Broadcast emitted whenever the long-press selection changes.
         * Fragments (Transform Tool, Ring Tool) listen so their per-mesh
         * controls retarget the freshly picked mesh.
         */
        const val ACTION_SELECTED_MESH_CHANGED = "com.modelviewer3d.SELECTED_MESH_CHANGED"
    }

    // ── Loading ───────────────────────────────────────────────────────────────
    private fun showLoading(msg: String, detail: String = "") {
        tvLoading?.text = msg
        tvLoadingDetail?.text = detail
        loadingOverlay?.visibility = View.VISIBLE
    }
    private fun hideLoading() { loadingOverlay?.visibility = View.GONE }

    // ── Screenshot ────────────────────────────────────────────────────────────
    private fun takeScreenshot() {
        val capW = glView.width; val capH = glView.height
        if (capW == 0 || capH == 0) { toast("No model loaded"); return }
        lifecycleScope.launch {
            var rgba: ByteArray? = null; val latch = CountDownLatch(1)
            glView.queueEvent { try { rgba = NativeLib.nativeTakeScreenshot() } catch(_: Exception){}; latch.countDown() }
            withContext(Dispatchers.IO) { latch.await() }
            val bytes = rgba ?: run { toast("Screenshot failed"); return@launch }
            val bmp = withContext(Dispatchers.Default) {
                val argb = IntArray(capW * capH)
                for (i in argb.indices) {
                    val b = i * 4
                    argb[i] = (bytes[b+3].toInt() and 0xFF shl 24) or
                              (bytes[b+0].toInt() and 0xFF shl 16) or
                              (bytes[b+1].toInt() and 0xFF shl 8)  or
                              (bytes[b+2].toInt() and 0xFF)
                }
                Bitmap.createBitmap(argb, capW, capH, Bitmap.Config.ARGB_8888)
            }
            val saved = withContext(Dispatchers.IO) { saveBitmap(bmp) }
            toast(if (saved != null) "📸 Saved: ${saved.name}" else "Could not save screenshot")
        }
    }

    private fun saveBitmap(bmp: Bitmap): File? = try {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "AuraCAD_$ts.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AuraCAD")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { contentResolver.openOutputStream(it)?.use { s -> bmp.compress(Bitmap.CompressFormat.PNG, 100, s) } }
            null  // no File object for MediaStore saves, toast handled outside
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AuraCAD")
            dir.mkdirs()
            val f = File(dir, "AuraCAD_$ts.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null)
            f
        }
    } catch (_: Exception) { null }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
