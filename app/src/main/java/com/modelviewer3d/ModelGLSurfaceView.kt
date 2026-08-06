package com.modelviewer3d

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.abs
import kotlin.math.hypot

class ModelGLSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    enum class Mode { CAMERA, RULER }
    var mode: Mode = Mode.CAMERA
    /**
     * Active transform gizmo tool: 0 = none, 1 = move, 2 = rotate, 3 = scale.
     * When non-zero, one-finger drags edit the model instead of orbiting the
     * camera (the gizmo is drawn by the native renderer).  Two-finger pan and
     * pinch zoom still control the camera.
     */
    var gizmoTool: Int = 0
    var onRulerPick: ((FloatArray) -> Unit)? = null
    /**
     * Long-press selection callback. Fires on the UI thread with the picked
     * mesh idx, or -1 if nothing was hit.  MainActivity wires this to update
     * the native selected-mesh state and notify open editor panels.
     */
    var onMeshLongPressPick: ((Int) -> Unit)? = null

    private var lastX  = 0f; private var lastY  = 0f
    private var lastMidX = 0f; private var lastMidY = 0f
    // Track whether scale gesture is actively running
    private var isScaling = false
    // Gizmo undo snapshot pushed only once the first real drag starts (so a
    // plain tap doesn't pollute the undo stack)
    private var gizmoUndoPushed = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                isScaling = true
                // Reset mid so pan doesn't jump after zoom ends
                lastMidX = d.focusX; lastMidY = d.focusY
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (mode == Mode.CAMERA) {
                    val sf = d.scaleFactor
                    queueEvent { NativeLib.nativeTouchZoom(sf) }
                }
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) {
                isScaling = false
                // Resync mid position to current finger midpoint after zoom ends
                lastMidX = d.focusX; lastMidY = d.focusY
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (mode == Mode.CAMERA) queueEvent { NativeLib.nativeResetCamera() }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (mode == Mode.RULER) {
                    val sx = e.x; val sy = e.y
                    val sw = width.toFloat(); val sh = height.toFloat()
                    queueEvent {
                        val pt = NativeLib.nativePickPoint(sx, sy, sw, sh)
                        if (pt != null) post { onRulerPick?.invoke(pt) }
                    }
                }
                return true
            }
            // Long-press → ray-pick a mesh, update native selection, broadcast
            // result back to the UI.  Only active in CAMERA mode so it never
            // collides with ruler point-picking (which uses single-tap).
            override fun onLongPress(e: MotionEvent) {
                if (mode != Mode.CAMERA) return
                val sx = e.x; val sy = e.y
                val sw = width.toFloat(); val sh = height.toFloat()
                queueEvent {
                    val idx = NativeLib.nativePickMesh(sx, sy, sw, sh)
                    if (idx >= 0) NativeLib.nativeSelectMesh(idx)
                    post { onMeshLongPressPick?.invoke(idx) }
                }
            }
        })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
    }

    fun attachRenderer(renderer: ModelRenderer) {
        setRenderer(renderer)
        // Render-on-demand: no 24/7 GPU burn. Every native state change flows
        // through queueEvent(), which we override to requestRender() — so the
        // scene stays in sync while idle frames (and the FPS drops they cause
        // while bottom sheets/popups are animating) disappear entirely.
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** All native mutations go through queueEvent — trigger exactly one redraw
     *  so WHEN_DIRTY never misses an update. Also makes slider drags and tool
     *  button clicks cost one frame instead of hammering the GPU. */
    override fun queueEvent(r: Runnable) {
        super.queueEvent(r)
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Scale detector MUST get the event first — it sets isScaling
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // RULER mode: single-tap handled in onSingleTapConfirmed; rotation still works
        val count = event.pointerCount
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                isScaling = false
                gizmoUndoPushed = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger arrived — sync midpoint, stop any 1-finger action
                lastMidX = (event.getX(0) + event.getX(1)) * 0.5f
                lastMidY = (event.getY(0) + event.getY(1)) * 0.5f
            }

            MotionEvent.ACTION_MOVE -> when {
                // 1-finger rotate — only when truly single finger
                count == 1 && !isScaling -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(dx) > 0.4f || abs(dy) > 0.4f) {
                        if (gizmoTool != 0) {
                            if (!gizmoUndoPushed) {
                                gizmoUndoPushed = true
                                queueEvent { NativeLib.nativeGizmoDrag(0f, 0f, true) } // one undo per gesture
                            }
                            queueEvent { NativeLib.nativeGizmoDrag(dx, dy, false) }
                        } else {
                            queueEvent { NativeLib.nativeTouchRotate(dx, dy) }
                        }
                    }
                    lastX = event.x; lastY = event.y
                }
                // 2-finger pan — only when NOT scaling (zoom has priority)
                count >= 2 && !isScaling && !scaleDetector.isInProgress -> {
                    val mx = (event.getX(0) + event.getX(1)) * 0.5f
                    val my = (event.getY(0) + event.getY(1)) * 0.5f
                    val dx = mx - lastMidX; val dy = my - lastMidY
                    // Only pan if midpoint moved more than pinch distance change
                    // (distinguishes pan from zoom)
                    if (abs(dx) > 1f || abs(dy) > 1f)
                        queueEvent { NativeLib.nativeTouchPan(dx, dy) }
                    lastMidX = mx; lastMidY = my
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val rem = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(rem); lastY = event.getY(rem)
                isScaling = false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isScaling = false
                gizmoUndoPushed = false
            }
        }
        return true
    }
}
