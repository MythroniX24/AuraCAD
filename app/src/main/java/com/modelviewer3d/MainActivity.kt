package com.modelviewer3d

import android.Manifest
import android.content.Intent
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.Channels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    lateinit var glView: ModelGLSurfaceView
    private lateinit var renderer: ModelRenderer

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
    private var btnToolBrush:  View? = null

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

    internal internal enum class Tool { NONE, SELECT, MOVE, ROTATE, SCALE, RING , BRUSH }
    internal var activeTool: Tool = Tool.NONE
    private var selectedMeshIdx: Int = -1

    private var rulerPoint1: FloatArray? = null
    private var rulerPoint2: FloatArray? = null
    private var rulerActive = false
    private var currentFileName = ""

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument())
    { uri -> uri?.let { openModelFromUri(it) } }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
    { r -> if (r.values.all { it }) launchFilePicker() else toast("Storage permission required") }

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
            findViewById<View>(R.id.btnExport).setOnClickListener     { showExportSheet() }
            findViewById<View>(R.id.btnScreenshot).setOnClickListener { takeScreenshot() }

            // ── Top-bar primary actions ──────────────────────────────────────
            findViewById<View>(R.id.btnUndo).setOnClickListener  { glView.queueEvent { NativeLib.nativeUndo() } }
            findViewById<View>(R.id.btnRedo).setOnClickListener  { glView.queueEvent { NativeLib.nativeRedo() } }
            findViewById<View>(R.id.btnReset).setOnClickListener { glView.queueEvent { NativeLib.nativeResetCamera() } }

            // ── Top-bar overflow menu ────────────────────────────────────────
            findViewById<View>(R.id.btnOverflow).setOnClickListener { showOverflowMenu(it) }

            // ── Bottom-toolbar tool buttons ──────────────────────────────────
            btnToolSelect?.setOnClickListener { onToolClicked(Tool.SELECT) }
            btnToolMove  ?.setOnClickListener { onToolClicked(Tool.MOVE)   }
            btnToolRotate?.setOnClickListener { onToolClicked(Tool.ROTATE) }
            btnToolScale ?.setOnClickListener { onToolClicked(Tool.SCALE)  }
            btnToolRing  ?.setOnClickListener { onToolClicked(Tool.RING)   }
            btnToolBrush ?.setOnClickListener { onToolClicked(Tool.BRUSH)  }

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

            // Register receiver for separation CPU-done signal
            val sepFilter = IntentFilter(SeparationService.ACTION_SEPARATION_CPU_DONE)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(separationCpuDoneReceiver, sepFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(separationCpuDoneReceiver, sepFilter)
            }

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

    override fun onResume()  { super.onResume();  glView.onResume()  }
    override fun onPause()   { super.onPause();   glView.onPause()   }
    override fun onDestroy() {
        try { unregisterReceiver(separationCpuDoneReceiver) } catch (_: Exception) {}
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
        popup.menu.add(0, 4, 3, if (rulerActive) "Disable Ruler" else "Ruler")
        popup.menu.add(0, 5, 4, "Screenshot")
        popup.menu.add(0, 6, 5, "Export…")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> findViewById<View>(R.id.btnOpen).performClick()
                2 -> findViewById<View>(R.id.btnEdit).performClick()
                3 -> findViewById<View>(R.id.btnMeshList).performClick()
                4 -> btnRuler?.performClick()
                5 -> findViewById<View>(R.id.btnScreenshot).performClick()
                6 -> findViewById<View>(R.id.btnExport).performClick()
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
                if (selectedMeshIdx < 0) {
                    toast("Long-press a mesh on the canvas first, then choose a transform")
                    activeTool = Tool.NONE
                    updateToolButtons()
                } else {
                    findViewById<View>(R.id.btnMeshTools).performClick()
                }
            }
            Tool.RING -> {
                findViewById<View>(R.id.btnRingTool).performClick()
            }
            Tool.NONE -> { /* deactivated */ }
        }
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

    // ── Separation GPU upload ─────────────────────────────────────────────────
    private val separationCpuDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
            if (intent.action != SeparationService.ACTION_SEPARATION_CPU_DONE) return
            glView.queueEvent {
                val ok = NativeLib.nativePerformSeparationGPU()
                runOnUiThread {
                    if (ok) {
                        updateStatusBar()
                        sendBroadcast(android.content.Intent(SeparationService.ACTION_SEPARATION_COMPLETE))
                    } else {
                        sendBroadcast(android.content.Intent(SeparationService.ACTION_SEPARATION_FAILED))
                    }
                }
            }
        }
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
                    val dx = p2[0]-p1[0]; val dy = p2[1]-p1[1]; val dz = p2[2]-p1[2]
                    val distW = sqrt((dx*dx+dy*dy+dz*dz).toDouble()).toFloat()
                    var maxMM = 100f
                    val latch = CountDownLatch(1)
                    glView.queueEvent {
                        try { val s=NativeLib.nativeGetModelSizeMM(); maxMM=maxOf(s[3],s[4],s[5]) }
                        catch(_:Exception){}
                        latch.countDown()
                    }
                    withContext(Dispatchers.IO) { latch.await() }
                    val distMM = distW * (if (maxMM>0.001f) maxMM/2f else 1f)
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

    // ── Export Sheet ──────────────────────────────────────────────────────────
    private fun showExportSheet() {
        if (supportFragmentManager.findFragmentByTag(ExportFragment.TAG) != null) return
        ExportFragment.newInstance().show(supportFragmentManager, ExportFragment.TAG)
    }

    /**
     * Export model to OBJ or STL.
     * - share=false → saves to Downloads/3DViewer/ (visible in Files app, no permission needed)
     * - share=true  → exports to cache then shares via system chooser
     */
    fun exportModel(format: String, share: Boolean, shareApp: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val baseName = if (currentFileName.isNotEmpty())
                    currentFileName.substringBeforeLast('.') else "model"
                val fileName = "${baseName}_$ts.$format"

                // For sharing: write to cache first
                if (share) {
                    val cacheFile = File(cacheDir, fileName)
                    val ok = runExportNative(format, cacheFile)
                    withContext(Dispatchers.Main) {
                        if (!ok) { toast("Export failed"); return@withContext }
                        val uri = FileProvider.getUriForFile(
                            this@MainActivity, "$packageName.fileprovider", cacheFile)
                        val mimeType = when(format) {
                            "obj" -> "model/obj"
                            "stl" -> "model/stl"
                            else  -> "application/octet-stream"
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "3D Model: $fileName")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            if (shareApp != null) setPackage(shareApp)
                        }
                        try {
                            startActivity(Intent.createChooser(intent, "Share 3D Model via"))
                        } catch (_: Exception) {
                            toast("App not installed")
                        }
                    }
                    return@launch
                }

                // ── Save to device ─────────────────────────────────────────────
                // Android 10+ (API 29+): use MediaStore.Downloads (no permission needed)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveToMediaStoreDownloads(fileName, format)
                } else {
                    // Android 9 and below: write directly to public Downloads
                    saveToPublicDownloads(fileName, format)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Error: ${e.message}") }
            }
        }
    }

    /**
     * Android 10+ (API 29+): Insert into MediaStore.Downloads.
     * File appears in: Files → Downloads → 3DViewer/
     * No permission required.
     */
    private suspend fun saveToMediaStoreDownloads(fileName: String, format: String) {
        val mimeType = when(format) { "obj" -> "model/obj"; "stl" -> "model/stl"; else -> "application/octet-stream" }

        // Write native export to cache first (native needs a real file path)
        val cacheFile = File(cacheDir, fileName)
        val ok = runExportNative(format, cacheFile)
        if (!ok) {
            withContext(Dispatchers.Main) { toast("Export failed") }
            return
        }

        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/3DViewer")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = resolver.insert(collection, values)

            if (itemUri != null) {
                resolver.openOutputStream(itemUri)?.use { out ->
                    FileInputStream(cacheFile).use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                cacheFile.delete()
                withContext(Dispatchers.Main) {
                    toast("✅ Saved to Downloads/3DViewer/$fileName")
                }
            } else {
                // MediaStore insert failed → fall back to app external storage
                saveFallback(fileName, format, cacheFile)
            }
        } catch (e: Exception) {
            saveFallback(fileName, format, cacheFile)
        }
    }

    /**
     * Android 9 and below: write to public Downloads directly.
     */
    private suspend fun saveToPublicDownloads(fileName: String, format: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "3DViewer")
        dir.mkdirs()
        val outFile = File(dir, fileName)
        val ok = runExportNative(format, outFile)
        withContext(Dispatchers.Main) {
            if (!ok) { toast("Export failed"); return@withContext }
            MediaScannerConnection.scanFile(this@MainActivity, arrayOf(outFile.absolutePath), null, null)
            toast("✅ Saved to Downloads/3DViewer/$fileName")
        }
    }

    /**
     * Fallback: save to app-specific external storage (always works, visible in file managers).
     * Path: /sdcard/Android/data/com.modelviewer3d/files/3DViewer/
     */
    private suspend fun saveFallback(fileName: String, format: String, cacheFile: File) {
        val dir = File(getExternalFilesDir(null), "3DViewer")
        dir.mkdirs()
        val outFile = File(dir, fileName)
        cacheFile.copyTo(outFile, overwrite = true)
        cacheFile.delete()
        withContext(Dispatchers.Main) {
            toast("✅ Saved to Android/data/com.modelviewer3d/files/3DViewer/$fileName")
        }
    }

    /**
     * Run the native export on the GL thread and wait for result.
     */
    private fun runExportNative(format: String, outFile: File): Boolean {
        outFile.parentFile?.mkdirs()
        var ok = false
        val latch = CountDownLatch(1)
        glView.queueEvent {
            try {
                ok = when (format) {
                    "obj" -> NativeLib.nativeExportOBJ(outFile.absolutePath)
                    "stl" -> NativeLib.nativeExportSTL(outFile.absolutePath)
                    else  -> false
                }
            } catch (_: Exception) {}
            latch.countDown()
        }
        latch.await()
        return ok
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
            "model/stl", "model/obj", "model/gltf-binary",
            "application/octet-stream"
        ))
    }

    fun openModelFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
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
                latch.await()
                withContext(Dispatchers.Main) {
                    hideLoading()
                    if (uploadOk) { toast("✓ $name loaded"); updateStatusBar() }
                    else toast("GPU upload failed")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { hideLoading(); toast("Error: ${e.message}") }
            }
        }
    }

    private fun hasKnownExtension(name: String): Boolean {
        val low = name.lowercase()
        return low.endsWith(".obj") || low.endsWith(".stl") || low.endsWith(".glb")
    }

    private fun mimeToExtension(mime: String): String = when {
        "stl" in mime || mime == "model/stl"          -> "stl"
        "obj" in mime || mime == "model/obj"           -> "obj"
        "gltf" in mime || mime == "model/gltf-binary"  -> "glb"
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
    private fun openBrushTool() {
        if (supportFragmentManager.findFragmentByTag(BrushToolFragment.TAG) != null) return
        BrushToolFragment.newInstance().show(supportFragmentManager, BrushToolFragment.TAG)
    }
    private fun openMoveTool() {
        if (supportFragmentManager.findFragmentByTag(MoveToolFragment.TAG) != null) return
        MoveToolFragment.newInstance().show(supportFragmentManager, MoveToolFragment.TAG)
    }
    private fun openRotateTool() {
        if (supportFragmentManager.findFragmentByTag(RotateToolFragment.TAG) != null) return
        RotateToolFragment.newInstance().show(supportFragmentManager, RotateToolFragment.TAG)
    }
    private fun openMeshInfo() {
        if (supportFragmentManager.findFragmentByTag(MeshInfoFragment.TAG) != null) return
        MeshInfoFragment.newInstance().show(supportFragmentManager, MeshInfoFragment.TAG)
    }
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
                put(MediaStore.Images.Media.DISPLAY_NAME, "3DViewer_$ts.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/3DViewer")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { contentResolver.openOutputStream(it)?.use { s -> bmp.compress(Bitmap.CompressFormat.PNG, 100, s) } }
            null  // no File object for MediaStore saves, toast handled outside
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "3DViewer")
            dir.mkdirs()
            val f = File(dir, "3DViewer_$ts.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null)
            f
        }
    } catch (_: Exception) { null }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
