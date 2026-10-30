package com.c7t.concurrentcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executor

// Test bench for running the rear and front cameras together. Logs every
// camera's calibration, then per-second fps and worst frame gap, under tag
// "ConcurrentCamera". Options come from intent extras:
//   adb shell am start -n com.c7t.concurrentcamera/.MainActivity \
//       --ef zoom 0.5 --ei fps 30 --ez faces false --es rearPhysical 3 \
//       --ei rearWidth 1920 --ei frontWidth 640 --ez lean false \
//       --es mode switch --ei period 3000 --ez reopen false --ez abort false
// zoom defaults to the rear camera's widest. rearPhysical streams one
// physical camera of the logical rear camera, unzoomed. lean turns off
// noise reduction, edge enhancement and stabilization.
//
// mode soak (the default) streams both cameras at once. mode switch streams one at a
// time: the front for period ms, then the rear until auto-exposure
// converges, then the front again, logging each switch's timing. With
// reopen, each switch closes and reopens the cameras. Otherwise both stay
// open and configured, and only the streaming request is stopped and
// restarted. abort drops in-flight frames instead of waiting for them.
class MainActivity : Activity() {
    private val tag = "ConcurrentCamera"
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private lateinit var manager: CameraManager
    private lateinit var status: TextView
    private val views = arrayOfNulls<TextureView>(2)
    private val cams = arrayOfNulls<Cam>(2)
    private val names = arrayOf("rear", "front")
    private val counts = IntArray(2)
    private val lastFrame = LongArray(2)
    private val worstGap = LongArray(2)
    private var errors = ""
    private val startTime = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Run over the lock screen, and before first unlock (directBootAware
        // in the manifest), so reboot-per-case soak tests need no one present.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        manager = getSystemService(CameraManager::class.java)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)
            return
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in 0..1) {
            val view = TextureView(this)
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    if (views.all { it?.isAvailable == true }) start()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = countFrame(i)
            }
            views[i] = view
            column.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0x80000000.toInt()); textSize = 14f }
        setContentView(FrameLayout(this).apply { addView(column); addView(status) })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) recreate()
    }

    // Close the cameras whenever the activity leaves the screen, so a
    // backgrounded app never holds them.
    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cams.forEach { it?.device?.close() }
        thread.quitSafely()
    }

    private fun countFrame(i: Int) {
        val now = SystemClock.elapsedRealtime()
        if (lastFrame[i] != 0L) worstGap[i] = maxOf(worstGap[i], now - lastFrame[i])
        lastFrame[i] = now
        counts[i]++
    }

    private fun start() {
        dumpCameras()
        val rearId = manager.cameraIdList.first { facing(it) == CameraCharacteristics.LENS_FACING_BACK }
        val frontId = manager.cameraIdList.first { facing(it) == CameraCharacteristics.LENS_FACING_FRONT }
        val rear = manager.getCameraCharacteristics(rearId)
        val zoom = intent.getFloatExtra("zoom", rear.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)!!.lower)
        val physical = intent.getStringExtra("rearPhysical")
        cams[0] = Cam(0, rearId, physical, size4x3(physical ?: rearId, intent.getIntExtra("rearWidth", 1920)), zoom.takeIf { physical == null })
        cams[1] = Cam(1, frontId, null, size4x3(frontId, intent.getIntExtra("frontWidth", 640)), null)
        if (intent.getStringExtra("mode") == "switch") startSwitching()
        else cams.forEach { cam -> open(cam!!) { stream(cam) } }
        report()
    }

    private fun facing(id: String) = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)

    // The largest 4:3 output no wider than maxWidth. 4:3 spans the whole
    // sensor array, which gives the widest view.
    private fun size4x3(id: String, maxWidth: Int): Size {
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        return map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width * 3 == it.height * 4 && it.width <= maxWidth }
            .maxBy { it.width }
    }

    // One camera's device and session. The one-shot hooks run on the camera
    // thread when the session next goes idle, when the device closes, and
    // on each completed frame.
    private class Cam(val i: Int, val id: String, val physicalId: String?, val size: Size, val zoom: Float?) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var request: CaptureRequest? = null
        var onReady: (() -> Unit)? = null
        var onClosed: (() -> Unit)? = null
        var onFrame: ((TotalCaptureResult) -> Unit)? = null
    }

    // Open and configure cam, then run then.
    @SuppressLint("MissingPermission")
    private fun open(cam: Cam, then: () -> Unit) {
        val i = cam.i
        val fps = intent.getIntExtra("fps", 30)
        val faces = intent.getBooleanExtra("faces", false)
        val lean = intent.getBooleanExtra("lean", false)
        Log.i(tag, "open ${names[i]} id ${cam.id} physical ${cam.physicalId} size ${cam.size} zoom ${cam.zoom} fps $fps faces $faces lean $lean")
        manager.openCamera(cam.id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cam.device = device
                val texture = views[i]!!.surfaceTexture!!
                texture.setDefaultBufferSize(cam.size.width, cam.size.height)
                val surface = Surface(texture)
                val output = OutputConfiguration(surface).apply { cam.physicalId?.let { setPhysicalCameraId(it) } }
                val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(output), executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            cam.session = session
                            cam.request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                                set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                                    if (faces) CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
                                    else CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
                                cam.zoom?.let { set(CaptureRequest.CONTROL_ZOOM_RATIO, it) }
                                if (lean) {
                                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                                }
                            }.build()
                            then()
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) = fail(i, "configure failed")
                        override fun onReady(session: CameraCaptureSession) {
                            cam.onReady?.also { cam.onReady = null }?.invoke()
                        }
                    })
                device.createCaptureSession(config)
            }
            override fun onClosed(device: CameraDevice) {
                cam.device = null
                cam.session = null
                cam.onClosed?.also { cam.onClosed = null }?.invoke()
            }
            override fun onDisconnected(device: CameraDevice) {
                fail(i, "disconnected")
                device.close()
            }
            override fun onError(device: CameraDevice, error: Int) {
                fail(i, "error $error")
                device.close()
            }
        }, handler)
    }

    private fun stream(cam: Cam) {
        cam.session!!.setRepeatingRequest(cam.request!!, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                cam.onFrame?.invoke(result)
            }
        }, handler)
    }

    private val reopen by lazy { intent.getBooleanExtra("reopen", false) }
    private val abort by lazy { intent.getBooleanExtra("abort", false) }
    private var lastFront = 0L
    private var cycles = 0

    private fun startSwitching() {
        val (rear, front) = cams.map { it!! }
        front.onFrame = { lastFront = SystemClock.elapsedRealtime() }
        open(front) {
            stream(front)
            if (reopen) scheduleSwitch() else open(rear) { scheduleSwitch() }
        }
    }

    private fun scheduleSwitch() = handler.postDelayed(::switchToRear, intent.getIntExtra("period", 3000).toLong())

    // Stop cam streaming, then run next once its in-flight frames are done,
    // or once it has closed if reopen.
    private fun halt(cam: Cam, next: () -> Unit) {
        if (reopen) {
            cam.onClosed = next
            cam.device!!.close()
        } else {
            cam.onReady = next
            if (abort) cam.session!!.abortCaptures() else cam.session!!.stopRepeating()
        }
    }

    // Start cam streaming, opening it first if it was closed.
    private fun resume(cam: Cam) = if (cam.device == null) open(cam) { stream(cam) } else stream(cam)

    // Swap to the rear until auto-exposure converges, then back to the
    // front. Each cycle logs the time from the start of the switch to the
    // first rear frame and to a converged one, and the gap in front frames.
    private fun switchToRear() {
        val (rear, front) = cams.map { it!! }
        val start = SystemClock.elapsedRealtime()
        val frontBefore = lastFront
        halt(front) {
            var frames = 0
            var first = -1L
            rear.onFrame = { result ->
                frames++
                if (first < 0) first = SystemClock.elapsedRealtime() - start
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (ae in CONVERGED || frames >= MAX_REAR_FRAMES) {
                    rear.onFrame = null
                    val ready = SystemClock.elapsedRealtime() - start
                    halt(rear) {
                        front.onFrame = {
                            val now = SystemClock.elapsedRealtime()
                            lastFront = now
                            front.onFrame = { lastFront = SystemClock.elapsedRealtime() }
                            Log.i(tag, "switch ${++cycles}: rear first $first ms ready $ready ms " +
                                "($frames frames, ae $ae)  front gap ${now - frontBefore} ms")
                            scheduleSwitch()
                        }
                        resume(front)
                    }
                }
            }
            resume(rear)
        }
    }

    private fun fail(i: Int, what: String) {
        val line = "${elapsed()}s ${names[i]} $what"
        Log.e(tag, line)
        runOnUiThread { errors += "\n$line" }
    }

    private fun elapsed() = (SystemClock.elapsedRealtime() - startTime) / 1000

    private fun report() {
        val line = "${elapsed()}s " + (0..1).joinToString("  ") { "${names[it]} ${counts[it]} fps gap ${worstGap[it]} ms" }
        Log.i(tag, line)
        status.text = line + errors
        counts.fill(0)
        worstGap.fill(0)
        status.postDelayed(::report, 1000)
    }

    private fun dumpCameras() {
        Log.i(tag, "concurrent ${manager.concurrentCameraIds}")
        val ids = manager.cameraIdList.flatMap { listOf(it) + manager.getCameraCharacteristics(it).physicalCameraIds }.distinct()
        for (id in ids) {
            val c = manager.getCameraCharacteristics(id)
            fun <T> get(key: CameraCharacteristics.Key<T>): String = when (val v = c.get(key)) {
                is FloatArray -> v.contentToString()
                is IntArray -> v.contentToString()
                is Array<*> -> v.contentToString()
                else -> v.toString()
            }
            Log.i(tag, "camera $id facing ${get(CameraCharacteristics.LENS_FACING)} " +
                "physical ${c.physicalCameraIds} " +
                "focal ${get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)} " +
                "sensor ${get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)} " +
                "array ${get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)} " +
                "active ${get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)} " +
                "intrinsics ${get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)} " +
                "distortion ${get(CameraCharacteristics.LENS_DISTORTION)} " +
                "pose ${get(CameraCharacteristics.LENS_POSE_TRANSLATION)} " +
                "rotation ${get(CameraCharacteristics.LENS_POSE_ROTATION)} " +
                "poseRef ${get(CameraCharacteristics.LENS_POSE_REFERENCE)} " +
                "zoom ${get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)} " +
                "faceModes ${get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)} " +
                "timestamp ${get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)} " +
                "fpsRanges ${get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)}")
        }
    }

    companion object {
        private val CONVERGED = setOf(
            CaptureResult.CONTROL_AE_STATE_CONVERGED,
            CaptureResult.CONTROL_AE_STATE_LOCKED,
            CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED,
        )
        // Give up waiting for convergence after this many rear frames, so a
        // dark scene can't stall the cycle.
        private const val MAX_REAR_FRAMES = 30
    }
}
