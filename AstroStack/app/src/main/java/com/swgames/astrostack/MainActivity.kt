package com.swgames.astrostack

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {

    // ---------- Tela ----------
    private lateinit var textureView: TextureView
    private lateinit var resultView: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnExposure: Button
    private lateinit var btnIso: Button
    private lateinit var btnFrames: Button
    private lateinit var btnStart: Button
    private lateinit var btnSave: Button
    private lateinit var btnView: Button
    private lateinit var stretchBar: SeekBar
    private lateinit var gradientBox: CheckBox

    // ---------- Threads ----------
    private val camThread = HandlerThread("camera").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    // ---------- Câmera ----------
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var yuvSize = Size(1920, 1440)
    private var previewSize = Size(1280, 960)
    private var sensorOrientation = 90
    private var maxExposureNs = 1_000_000_000L
    private var minIso = 100
    private var maxIso = 3200

    // ---------- Ajustes ----------
    private val allExposures = listOf(0.5, 1.0, 2.0, 4.0, 6.0, 8.0, 10.0, 15.0, 20.0, 25.0, 30.0)
    private var exposures = listOf(1.0)
    private var expIdx = 0
    private var isos = listOf(800)
    private var isoIdx = 0
    private val frameOptions = listOf(5, 10, 20, 30, 50, 100)
    private var framesIdx = 2

    @Volatile private var strength = 50
    @Volatile private var removeGradient = true

    // ---------- Sessão de fotos ----------
    @Volatile private var running = false
    @Volatile private var framesTaken = 0
    private var stacker: Stacker? = null

    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (textureView.isAvailable) openCamera()
        } else {
            setStatus("O app precisa da câmera pra funcionar.")
        }
    }

    override fun onResume() {
        super.onResume()
        if (textureView.isAvailable) openCamera()
    }

    override fun onPause() {
        stopRun(restartPreview = false)
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdown()
        camThread.quitSafely()
        super.onDestroy()
    }

    // =====================================================================
    // Interface (feita em código, em vermelho pra não ofuscar a visão noturna)
    private val red = Color.rgb(255, 70, 70)

    private fun mkButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setTextColor(red)
        setBackgroundColor(Color.rgb(30, 0, 0))
        isAllCaps = false
        textSize = 14f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(6, 6, 6, 6)
        }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        textureView = TextureView(this)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        root.addView(textureView, FrameLayout.LayoutParams(-1, -1))

        resultView = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        root.addView(resultView, FrameLayout.LayoutParams(-1, -1))

        val title = TextView(this).apply {
            text = "AstroStack"
            setTextColor(red)
            textSize = 18f
            setPadding(24, 48, 24, 12)
        }
        root.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        statusText = TextView(this).apply {
            setTextColor(red)
            textSize = 14f
            setPadding(12, 8, 12, 8)
            text = "Abrindo a câmera…"
        }
        btnExposure = mkButton("Exposição") { cycleExposure() }
        btnIso = mkButton("ISO") { cycleIso() }
        btnFrames = mkButton("Fotos") { cycleFrames() }
        btnStart = mkButton("▶ INICIAR") { if (running) stopRun(true) else startRun() }
        btnSave = mkButton("💾 Salvar") { saveCurrent() }
        btnView = mkButton("👁 Câmera") { toggleView() }

        val stretchLabel = TextView(this).apply {
            setTextColor(red); text = "Realce"; setPadding(12, 0, 12, 0)
        }
        stretchBar = SeekBar(this).apply {
            max = 100
            progress = strength
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    strength = p
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) = refreshRender()
            })
        }
        gradientBox = CheckBox(this).apply {
            text = "Tirar poluição luminosa"
            setTextColor(red)
            isChecked = removeGradient
            setOnCheckedChangeListener { _, c -> removeGradient = c; refreshRender() }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(8, 8, 8, 32)
            addView(statusText)
            addView(row(btnExposure, btnIso, btnFrames))
            addView(row(stretchLabel, stretchBar))
            addView(gradientBox)
            addView(row(btnStart, btnSave, btnView))
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        updateLabels()
    }

    private fun expLabel(): String {
        val e = exposures[expIdx]
        return if (e < 1.0) "1/${(1 / e).toInt()} s" else "${e.toInt()} s"
    }

    private fun updateLabels() {
        btnExposure.text = "⏱ ${expLabel()}"
        btnIso.text = "ISO ${isos[isoIdx]}"
        btnFrames.text = "📷 ${frameOptions[framesIdx]}x"
    }

    private fun cycleExposure() { if (!running) { expIdx = (expIdx + 1) % exposures.size; updateLabels() } }
    private fun cycleIso() { if (!running) { isoIdx = (isoIdx + 1) % isos.size; updateLabels() } }
    private fun cycleFrames() { if (!running) { framesIdx = (framesIdx + 1) % frameOptions.size; updateLabels() } }

    private fun toggleView() {
        val showResult = resultView.visibility != View.VISIBLE
        showResult(showResult)
    }

    private fun showResult(show: Boolean) {
        resultView.visibility = if (show) View.VISIBLE else View.GONE
        btnView.text = if (show) "👁 Câmera" else "👁 Resultado"
    }

    private fun setStatus(t: String) = ui.post { statusText.text = t }

    // =====================================================================
    // Câmera
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (camera != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager

        var bestId: String? = null
        var bestScore = -1L
        var manualOk = false
        for (id in mgr.cameraIdList) {
            val c = mgr.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val manual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.upper ?: 0L
            val score = if (manual) exp else exp / 1000
            if (score > bestScore) {
                bestScore = score; bestId = id; manualOk = manual
            }
        }
        val id = bestId ?: run { setStatus("Não achei a câmera traseira."); return }
        val ch = mgr.getCameraCharacteristics(id)

        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        maxExposureNs = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.upper ?: 1_000_000_000L
        ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { minIso = it.lower; maxIso = it.upper }

        val maxSec = maxExposureNs / 1e9
        exposures = allExposures.filter { it <= maxSec + 0.01 }.ifEmpty { listOf(maxSec) }
        expIdx = exposures.indices.minByOrNull { abs(exposures[it] - 10.0) } ?: 0
        isos = listOf(100, 200, 400, 800, 1600, 3200, 6400).filter { it in minIso..maxIso }.ifEmpty { listOf(maxIso) }
        isoIdx = isos.indices.minByOrNull { abs(isos[it] - 1600) } ?: 0
        ui.post { updateLabels() }

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        yuvSize = yuvSizes.filter { maxOf(it.width, it.height) <= 2400 }.maxByOrNull { it.width * it.height }
            ?: yuvSizes.minByOrNull { it.width * it.height } ?: Size(1920, 1080)
        val aspect = yuvSize.width.toFloat() / yuvSize.height
        previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1920 && it.height <= 1080 && abs(it.width.toFloat() / it.height - aspect) < 0.05f }
            .maxByOrNull { it.width * it.height } ?: Size(1280, 720)

        setStatus(
            "Câmera pronta. Exposição máx: ${"%.0f".format(maxSec)} s" +
                (if (!manualOk) " (aviso: seu celular pode ignorar o modo manual)" else "") +
                "\nApoie o celular num tripé e aponte pro céu."
        )

        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    createSession(cam)
                }
                override fun onDisconnected(cam: CameraDevice) {
                    cam.close(); camera = null
                }
                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close(); camera = null
                    setStatus("Erro na câmera ($error)")
                }
            }, camHandler)
        } catch (e: Exception) {
            setStatus("Não deu pra abrir a câmera: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(cam: CameraDevice) {
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val ps = Surface(st)
        previewSurface = ps
        val r = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ rd -> onImage(rd) }, camHandler)
        reader = r
        try {
            cam.createCaptureSession(listOf(ps, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startPreview()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    setStatus("Falha ao configurar a câmera.")
                }
            }, camHandler)
        } catch (e: Exception) {
            setStatus("Erro: ${e.message}")
        }
    }

    private fun applyManual(b: CaptureRequest.Builder, expNs: Long, iso: Int) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f) // 0 = infinito
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, expNs)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
    }

    private fun startPreview() {
        val s = session ?: return
        val cam = camera ?: return
        val ps = previewSurface ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            // prévia com 1/4 s pra dar pra ver as estrelas mais fortes
            applyManual(b, minOf(250_000_000L, maxExposureNs), minOf(maxIso, 3200))
            s.setRepeatingRequest(b.build(), null, camHandler)
        } catch (e: Exception) {
            setStatus("Erro na prévia: ${e.message}")
        }
    }

    private fun closeCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; camera = null; reader = null
    }

    // =====================================================================
    // Sessão de fotos com empilhamento ao vivo
    private fun startRun() {
        if (session == null) { setStatus("A câmera ainda não está pronta."); return }
        running = true
        framesTaken = 0
        worker.execute { stacker = null }
        btnStart.text = "■ PARAR"
        setStatus("Começando em 3 s… não encoste no celular!")
        camHandler.postDelayed({
            if (running) {
                try { session?.stopRepeating() } catch (_: Exception) {}
                captureNext()
            }
        }, 3000)
    }

    private fun stopRun(restartPreview: Boolean) {
        val was = running
        running = false
        ui.post { btnStart.text = "▶ INICIAR" }
        if (was && restartPreview) camHandler.post { startPreview() }
    }

    private fun captureNext() {
        if (!running) return
        val total = frameOptions[framesIdx]
        if (framesTaken >= total) { finishRun(); return }
        val cam = camera ?: return
        val s = session ?: return
        val r = reader ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            applyManual(b, (exposures[expIdx] * 1e9).toLong(), isos[isoIdx])
            s.capture(b.build(), null, camHandler)
            framesTaken++
            setStatus("Fotografando $framesTaken/$total (${expLabel()})…")
        } catch (e: Exception) {
            setStatus("Erro ao fotografar: ${e.message}")
            stopRun(true)
        }
    }

    private fun onImage(rd: ImageReader) {
        val img = try { rd.acquireNextImage() } catch (_: Exception) { null } ?: return
        if (!running) { img.close(); return }
        worker.execute {
            try {
                val frame = try { toFrame(img) } finally { img.close() }
                val st = stacker ?: Stacker(frame.w, frame.h).also { stacker = it }
                val ok = st.add(frame)
                val out = st.render(2, strength, removeGradient)
                val bmp = out?.let { toBitmap(it) }
                ui.post {
                    if (bmp != null) { resultView.setImageBitmap(bmp); showResult(true) }
                }
                setStatus(
                    "Empilhadas ${st.stacked} de $framesTaken • ${st.lastStarCount} estrelas" +
                        if (!ok) "\n(essa foto não alinhou e foi descartada)" else ""
                )
            } catch (e: Throwable) {
                setStatus("Erro no empilhamento: ${e.message}")
            }
            camHandler.post { captureNext() }
        }
    }

    private fun finishRun() {
        running = false
        ui.post { btnStart.text = "▶ INICIAR" }
        startPreview()
        worker.execute {
            val st = stacker
            if (st == null || st.stacked == 0) {
                setStatus("Nenhuma foto alinhou. Tente mais exposição ou ISO.")
                return@execute
            }
            val name = saveStack(st)
            setStatus("Pronto! ${st.stacked} fotos empilhadas.\nSalvo em Imagens/AstroStack/$name")
        }
    }

    private fun refreshRender() {
        worker.execute {
            val st = stacker ?: return@execute
            val out = st.render(2, strength, removeGradient) ?: return@execute
            val bmp = toBitmap(out)
            ui.post { resultView.setImageBitmap(bmp) }
        }
    }

    private fun saveCurrent() {
        worker.execute {
            val st = stacker
            if (st == null || st.stacked == 0) { setStatus("Ainda não tem nada pra salvar."); return@execute }
            val name = saveStack(st)
            setStatus("Salvo em Imagens/AstroStack/$name")
        }
    }

    private fun saveStack(st: Stacker): String {
        val out = st.render(1, strength, removeGradient) ?: return ""
        val bmp = toBitmap(out)
        val name = "AstroStack_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AstroStack")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("sem acesso à galeria")
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            name
        } catch (e: Exception) {
            setStatus("Não deu pra salvar: ${e.message}")
            ""
        }
    }

    // =====================================================================
    // Conversões
    private fun toBitmap(img: RenderedImage): Bitmap {
        val raw = Bitmap.createBitmap(img.pixels, img.w, img.h, Bitmap.Config.ARGB_8888)
        if (sensorOrientation % 360 == 0) return raw
        val m = Matrix().apply { postRotate(sensorOrientation.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    private fun toFrame(img: Image): Frame {
        val w = img.width
        val h = img.height
        val yP = img.planes[0]
        val uP = img.planes[1]
        val vP = img.planes[2]
        val yArr = ByteArray(yP.buffer.remaining()).also { yP.buffer.get(it) }
        val uArr = ByteArray(uP.buffer.remaining()).also { uP.buffer.get(it) }
        val vArr = ByteArray(vP.buffer.remaining()).also { vP.buffer.get(it) }
        val yRow = yP.rowStride
        val yPix = yP.pixelStride
        val uRow = uP.rowStride
        val uPix = uP.pixelStride
        val vRow = vP.rowStride
        val vPix = vP.pixelStride
        val n = w * h
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val lum = FloatArray(n)
        for (y in 0 until h) {
            val yo = y * yRow
            val uo = (y shr 1) * uRow
            val vo = (y shr 1) * vRow
            val row = y * w
            for (x in 0 until w) {
                val yy = (yArr[yo + x * yPix].toInt() and 0xFF).toFloat()
                val u = (uArr[uo + (x shr 1) * uPix].toInt() and 0xFF) - 128f
                val v = (vArr[vo + (x shr 1) * vPix].toInt() and 0xFF) - 128f
                val i = row + x
                r[i] = yy + 1.402f * v
                g[i] = yy - 0.344f * u - 0.714f * v
                b[i] = yy + 1.772f * u
                lum[i] = yy
            }
        }
        return Frame(w, h, r, g, b, lum)
    }
}
