package com.swgames.astrostack

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity(), Actions {

    private val state = AppState()
    private lateinit var textureView: TextureView

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
    private var maxIso = 3200

    private val allExposures = listOf(0.5, 1.0, 2.0, 4.0, 6.0, 8.0, 10.0, 15.0, 20.0, 25.0, 30.0)

    // ---------- Sessão (mexidos só na thread da câmera / worker) ----------
    @Volatile private var running = false
    @Volatile private var capMode = Mode.IDLE
    private var framesTaken = 0
    private var target = 0
    private var shotExpNs = 0L
    private var shotIso = 0
    private var dithered = false
    private var stacker: Stacker? = null
    private var darkBuilder: DarkBuilder? = null
    @Volatile private var masterDark: MasterDark? = null

    private val darkFrames = 10
    private val ditherEvery = 5

    private val tone by lazy {
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) } catch (_: Exception) { null }
    }

    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        textureView = TextureView(this)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        setContent { AstroApp(state, this) { textureView } }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
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
        stopInternal(restartPreview = false)
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdown()
        camThread.quitSafely()
        try { tone?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun setStatus(t: String) = ui.post { state.status = t }
    private fun onUi(block: () -> Unit) = ui.post(block)

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
        var minIso = 100
        ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { minIso = it.lower; maxIso = it.upper }

        val maxSec = maxExposureNs / 1e9
        val exps = allExposures.filter { it <= maxSec + 0.01 }.ifEmpty { listOf(maxSec) }
        val isoList = listOf(100, 200, 400, 800, 1600, 3200, 6400).filter { it in minIso..maxIso }.ifEmpty { listOf(maxIso) }
        onUi {
            state.exposures = exps
            state.expIdx = exps.indices.minByOrNull { abs(exps[it] - 10.0) } ?: 0
            state.isos = isoList
            state.isoIdx = isoList.indices.minByOrNull { abs(isoList[it] - 1600) } ?: 0
        }

        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        yuvSize = yuvSizes.filter { maxOf(it.width, it.height) <= 2400 }.maxByOrNull { it.width * it.height }
            ?: yuvSizes.minByOrNull { it.width * it.height } ?: Size(1920, 1080)
        val aspect = yuvSize.width.toFloat() / yuvSize.height
        previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1920 && it.height <= 1080 && abs(it.width.toFloat() / it.height - aspect) < 0.05f }
            .maxByOrNull { it.width * it.height } ?: Size(1280, 720)

        setStatus(
            "Pronto. Exposição máxima do seu celular: ${"%.0f".format(maxSec)} s" +
                (if (!manualOk) "\nAviso: seu celular pode ignorar o modo manual." else "") +
                "\nApoie o celular firme e aponte pro céu."
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
    // Ações da tela
    override fun start() = begin(Mode.LIGHTS)

    override fun takeDarks() = begin(Mode.DARKS)

    override fun clearDarks() {
        masterDark = null
        state.darkInfo = null
        setStatus("Dark frames apagados.")
    }

    override fun stop() = stopInternal(restartPreview = true)

    override fun save() {
        worker.execute {
            val st = stacker
            if (st == null || st.stacked == 0) { setStatus("Ainda não tem nada pra salvar."); return@execute }
            val name = saveStack(st)
            if (name.isNotEmpty()) setStatus("Salvo em Imagens/AstroStack/$name")
        }
    }

    override fun rerender() {
        val strength = state.strength
        val grad = state.removeGradient
        worker.execute {
            val st = stacker ?: return@execute
            val out = st.render(2, strength, grad) ?: return@execute
            val bmp = toBitmap(out)
            onUi { state.result = bmp }
        }
    }

    private fun begin(mode: Mode) {
        if (session == null) { setStatus("A câmera ainda não está pronta."); return }
        if (state.busy) return
        shotExpNs = (state.exposure * 1e9).toLong()
        shotIso = state.iso
        target = if (mode == Mode.DARKS) darkFrames else state.frames
        framesTaken = 0
        dithered = false
        running = true
        capMode = mode

        val dark = masterDark
        val darkOk = dark != null && dark.exposureNs == shotExpNs && dark.iso == shotIso
        worker.execute {
            if (mode == Mode.LIGHTS) stacker = null
            if (mode == Mode.DARKS) darkBuilder = null
        }

        val wait = if (mode == Mode.DARKS) 5 else 3
        state.total = target
        state.done = 0
        state.mode = Mode.COUNTDOWN
        state.countdown = wait
        if (mode == Mode.DARKS) {
            state.status = "Tampe a lente (dedo, fita ou tampa) e não deixe entrar luz."
        } else {
            state.stacked = 0
            state.stars = 0
            state.status = when {
                darkOk -> "Usando dark frames. Não encoste no celular!"
                dark != null -> "Os darks são de outra exposição/ISO e não vão ser usados. Não encoste no celular!"
                else -> "Não encoste no celular!"
            }
        }
        tick(wait, mode)
    }

    private fun tick(left: Int, mode: Mode) {
        if (!running) return
        if (left <= 0) {
            state.mode = mode
            camHandler.post {
                try { session?.stopRepeating() } catch (_: Exception) {}
                captureNext()
            }
            return
        }
        state.countdown = left
        ui.postDelayed({ tick(left - 1, mode) }, 1000)
    }

    private fun stopInternal(restartPreview: Boolean) {
        val was = running
        running = false
        capMode = Mode.IDLE
        onUi {
            state.mode = Mode.IDLE
            if (was) state.status = "Parado. ${state.stacked} fotos empilhadas."
        }
        if (was && restartPreview) camHandler.post { startPreview() }
    }

    // =====================================================================
    // Sequência de fotos (thread da câmera)
    private fun captureNext() {
        if (!running) return
        if (framesTaken >= target) { finishSession(); return }

        // Dithering manual: a cada N fotos, pede pra mexer o celular de leve
        if (capMode == Mode.LIGHTS && state.dither && framesTaken > 0 &&
            framesTaken % ditherEvery == 0 && !dithered
        ) {
            dithered = true
            onUi {
                state.mode = Mode.DITHER
                state.status = "Dê um toquinho bem leve no celular (alguns milímetros)…"
            }
            try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200) } catch (_: Exception) {}
            camHandler.postDelayed({
                if (running) {
                    onUi { if (state.mode == Mode.DITHER) state.mode = Mode.LIGHTS }
                    captureNext()
                }
            }, 4000)
            return
        }
        dithered = false

        val cam = camera ?: return
        val s = session ?: return
        val r = reader ?: return
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            applyManual(b, shotExpNs, shotIso)
            s.capture(b.build(), null, camHandler)
            framesTaken++
            val n = framesTaken
            val kind = if (capMode == Mode.DARKS) "Dark" else "Foto"
            setStatus("$kind $n de $target (${state.expLabel()}, ISO $shotIso)…")
        } catch (e: Exception) {
            setStatus("Erro ao fotografar: ${e.message}")
            stopInternal(true)
        }
    }

    private fun onImage(rd: ImageReader) {
        val img = try { rd.acquireNextImage() } catch (_: Exception) { null } ?: return
        if (!running) { img.close(); return }
        val mode = capMode
        val strength = state.strength
        val grad = state.removeGradient
        worker.execute {
            try {
                val frame = try { toFrame(img) } finally { img.close() }
                if (mode == Mode.DARKS) {
                    val db = darkBuilder ?: DarkBuilder(frame.w, frame.h, shotExpNs, shotIso).also { darkBuilder = it }
                    db.add(frame)
                    val c = db.count
                    onUi { state.done = c }
                } else {
                    val st = stacker ?: run {
                        val d = masterDark
                        val use = if (d != null && d.exposureNs == shotExpNs && d.iso == shotIso) d else null
                        Stacker(frame.w, frame.h, use).also { stacker = it }
                    }
                    val ok = st.add(frame)
                    val bmp = st.render(2, strength, grad)?.let { toBitmap(it) }
                    val stacked = st.stacked
                    val stars = st.lastStarCount
                    val rej = st.lastRejectedPct
                    onUi {
                        if (bmp != null) {
                            state.result = bmp
                            if (stacked == 1) state.showResult = true
                        }
                        state.done = framesTaken
                        state.stacked = stacked
                        state.stars = stars
                        state.rejectedPct = rej
                        if (!ok) state.status = "Essa foto não alinhou e foi descartada (nuvem ou celular mexeu?)."
                    }
                }
            } catch (e: Throwable) {
                setStatus("Erro no processamento: ${e.message}")
            }
            camHandler.post { captureNext() }
        }
    }

    private fun finishSession() {
        val mode = capMode
        running = false
        capMode = Mode.IDLE
        startPreview()
        worker.execute {
            if (mode == Mode.DARKS) {
                val md = darkBuilder?.build()
                darkBuilder = null
                masterDark = md
                onUi {
                    state.mode = Mode.IDLE
                    if (md != null) {
                        state.darkInfo = "Darks: ${md.frames}x ${state.expLabel()} • ISO ${md.iso}"
                        state.status = "Dark frames prontos! Destampe a lente e toque no botão grande."
                    } else {
                        state.status = "Não consegui tirar os darks."
                    }
                }
            } else {
                val st = stacker
                if (st == null || st.stacked == 0) {
                    onUi {
                        state.mode = Mode.IDLE
                        state.status = "Nenhuma foto alinhou. Tente mais exposição ou ISO."
                    }
                    return@execute
                }
                val name = saveStack(st)
                val n = st.stacked
                val dk = if (st.usesDark) " com darks" else ""
                onUi {
                    state.mode = Mode.IDLE
                    state.status = "Pronto! $n fotos empilhadas$dk.\nSalvo em Imagens/AstroStack/$name"
                }
            }
        }
    }

    private fun saveStack(st: Stacker): String {
        val out = st.render(1, state.strength, state.removeGradient) ?: return ""
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
