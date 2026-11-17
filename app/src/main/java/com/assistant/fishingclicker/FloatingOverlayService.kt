package com.assistant.fishingclicker

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import kotlin.math.abs

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Menu Views
    private lateinit var menuLayout: LinearLayout
    private lateinit var menuBodyLayout: LinearLayout
    private lateinit var statusLabel: TextView
    private lateinit var subStatusLabel: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnMinimize: TextView

    // Circle 1 (Tombol Mancing/Tarik): Click-through Ring + Touchable Drag Handle
    private lateinit var circle1RingView: TargetCircleView
    private lateinit var circle1HandleView: TextView

    // Circle 2 (Sensor Bar Vertikal): Click-through Scanner Capsule + Touchable Drag Handle
    private lateinit var circle2ScannerView: BarScannerView
    private lateinit var circle2HandleView: TextView

    private lateinit var paramsMenu: WindowManager.LayoutParams
    private lateinit var paramsCircle1Ring: WindowManager.LayoutParams
    private lateinit var paramsCircle1Handle: WindowManager.LayoutParams
    private lateinit var paramsCircle2Scanner: WindowManager.LayoutParams
    private lateinit var paramsCircle2Handle: WindowManager.LayoutParams

    @Volatile
    private var isRunning = false
    private var isMinimized = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loopJob: Job? = null
    private var animJob: Job? = null

    private var screenWidth = 2400
    private var screenHeight = 1080
    private var screenDensity = 400

    // Ukuran fleksibel (bisa diperbesar/diperkecil lewat tombol + / -)
    private var scaleFactor = 1.0f
    private val baseCircle1Size = 155
    private val baseCircle2Width = 135
    private val baseCircle2Height = 300

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            releaseVirtualDisplay()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        updateScreenMetrics()

        createFloatingMenu()
        createFlexibleOverlays()
        startScannerAnimation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("HAS_PROJECTION", false) == true) {
            val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val data: Intent? = intent.getParcelableExtra("DATA")
            if (resultCode == Activity.RESULT_OK && data != null) {
                initMediaProjection(resultCode, data)
            } else {
                statusLabel.text = "Status: Gagal Izin Layar!"
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val oldW = screenWidth
        val oldH = screenHeight
        updateScreenMetrics()
        if (oldW != screenWidth || oldH != screenHeight) {
            // Recreate VirtualDisplay to match new Portrait/Landscape dimensions
            if (mediaProjection != null) {
                setupVirtualDisplay()
            }
        }
    }

    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}

        try {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, data)
            // Wajib untuk Android 14+ (API 34) sebelum memanggil createVirtualDisplay
            mediaProjection?.registerCallback(projectionCallback, mainHandler)
            setupVirtualDisplay()
            statusLabel.text = "Status: Layar Siap ✅ (Klik MULAI)"
            subStatusLabel.text = "Geser [✥ 1:KLIK] & [✥ 2:BAR] ke target"
        } catch (e: Exception) {
            statusLabel.text = "Error Layar: ${e.javaClass.simpleName}"
        }
    }

    @Synchronized
    private fun setupVirtualDisplay() {
        releaseVirtualDisplay()
        updateScreenMetrics()
        try {
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AutoMancingCaptureV3",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                mainHandler
            )
        } catch (e: Exception) {
            serviceScope.launch(Dispatchers.Main) {
                statusLabel.text = "Gagal VirtualDisplay: ${e.message?.take(22)}"
            }
        }
    }

    @Synchronized
    private fun releaseVirtualDisplay() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {}
        try {
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {}
    }

    private fun createFloatingMenu() {
        menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE0F172A"))
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#38BDF8"))
            }
        }

        // Top Title Bar (Draggable + Minimize button)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = "🎣 Auto Mancing v3  "
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        btnMinimize = TextView(this).apply {
            text = " [–] "
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(12, 2, 8, 2)
            setOnClickListener {
                isMinimized = !isMinimized
                menuBodyLayout.visibility = if (isMinimized) View.GONE else View.VISIBLE
                btnMinimize.text = if (isMinimized) " [+] " else " [–] "
            }
        }

        headerRow.addView(titleView)
        headerRow.addView(btnMinimize)

        menuBodyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        statusLabel = TextView(this).apply {
            text = "Status: Menunggu Izin Layar..."
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 11f
            setPadding(0, 6, 0, 2)
        }

        subStatusLabel = TextView(this).apply {
            text = "Tarik label [✥] untuk geser bulatan"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 9.5f
            setPadding(0, 0, 0, 8)
        }

        btnStartStop = Button(this).apply {
            text = "▶ MULAI AUTO MANCING"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#28A745"))
                cornerRadius = 18f
            }
            setOnClickListener {
                toggleBot(!isRunning)
            }
        }

        // Row 1: Snap Posisi + Ukuran - / +
        val flexRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }

        val btnSnap = Button(this).apply {
            text = "🎯 Snap"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = 14f
            }
            setOnClickListener {
                snapIdealPositions()
            }
        }

        val btnSmaller = Button(this).apply {
            text = "🔍 -"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#475569"))
                cornerRadius = 14f
            }
            setOnClickListener {
                resizeOverlays(-0.12f)
            }
        }

        val btnBigger = Button(this).apply {
            text = "🔍 +"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#475569"))
                cornerRadius = 14f
            }
            setOnClickListener {
                resizeOverlays(+0.12f)
            }
        }

        val btnClose = Button(this).apply {
            text = "✕"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DC3545"))
                cornerRadius = 14f
            }
            setOnClickListener {
                stopSelf()
            }
        }

        flexRow.addView(btnSnap)
        flexRow.addView(btnSmaller)
        flexRow.addView(btnBigger)
        flexRow.addView(btnClose)

        menuBodyLayout.addView(statusLabel)
        menuBodyLayout.addView(subStatusLabel)
        menuBodyLayout.addView(btnStartStop)
        menuBodyLayout.addView(flexRow)

        menuLayout.addView(headerRow)
        menuLayout.addView(menuBodyLayout)

        paramsMenu = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 28
            y = 110
        }

        makeDraggable(menuLayout, paramsMenu)
        windowManager.addView(menuLayout, paramsMenu)
    }

    /**
     * Membuat 2 Bulatan dengan desain Cincin Jernih (100% Click-Through)
     * + Label Gagang Geser [✥ 1:KLIK] & [✥ 2:BAR] yang bisa digeser kapan saja!
     */
    private fun createFlexibleOverlays() {
        val c1Size = (baseCircle1Size * scaleFactor).toInt()
        val c2W = (baseCircle2Width * scaleFactor).toInt()
        val c2H = (baseCircle2Height * scaleFactor).toInt()

        // Posisi default disesuaikan dengan layar Landscape Game di video_1.mp4
        val initC1X = (screenWidth * 0.86).toInt()
        val initC1Y = (screenHeight * 0.72).toInt()

        val initC2X = (screenWidth * 0.58).toInt()
        val initC2Y = (screenHeight * 0.34).toInt()

        // 1A. Ring Visual Bulatan 1 (SELALU FLAG_NOT_TOUCHABLE agar tidak pernah menghalangi klik!)
        circle1RingView = TargetCircleView(this)
        paramsCircle1Ring = WindowManager.LayoutParams(
            c1Size,
            c1Size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initC1X
            y = initC1Y
        }
        windowManager.addView(circle1RingView, paramsCircle1Ring)

        // 1B. Gagang Geser Bulatan 1 ([✥ 1:KLIK]) tepat di atas cincin Bulatan 1
        circle1HandleView = TextView(this).apply {
            text = "✥ 1:KLIK"
            setTextColor(Color.WHITE)
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(18, 6, 18, 6)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6FF3B30"))
                cornerRadius = 24f
                setStroke(2, Color.WHITE)
            }
        }
        paramsCircle1Handle = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initC1X + 12
            y = (initC1Y - 46).coerceAtLeast(0)
        }
        makeLinkedDraggable(
            handleView = circle1HandleView,
            handleParams = paramsCircle1Handle,
            targetView = circle1RingView,
            targetParams = paramsCircle1Ring,
            offsetX = -12,
            offsetY = 46
        )
        windowManager.addView(circle1HandleView, paramsCircle1Handle)

        // 2A. Kapsul Visual Bulatan 2 (SELALU FLAG_NOT_TOUCHABLE & Tengah Jernih)
        circle2ScannerView = BarScannerView(this)
        paramsCircle2Scanner = WindowManager.LayoutParams(
            c2W,
            c2H,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initC2X
            y = initC2Y
        }
        windowManager.addView(circle2ScannerView, paramsCircle2Scanner)

        // 2B. Gagang Geser Bulatan 2 ([✥ 2:BAR]) tepat di atas kapsul Bulatan 2
        circle2HandleView = TextView(this).apply {
            text = "✥ 2:BAR"
            setTextColor(Color.WHITE)
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(18, 6, 18, 6)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60284C7"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#38BDF8"))
            }
        }
        paramsCircle2Handle = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initC2X + 8
            y = (initC2Y - 46).coerceAtLeast(0)
        }
        makeLinkedDraggable(
            handleView = circle2HandleView,
            handleParams = paramsCircle2Handle,
            targetView = circle2ScannerView,
            targetParams = paramsCircle2Scanner,
            offsetX = -8,
            offsetY = 46
        )
        windowManager.addView(circle2HandleView, paramsCircle2Handle)
    }

    private fun resizeOverlays(delta: Float) {
        scaleFactor = (scaleFactor + delta).coerceIn(0.65f, 1.65f)
        paramsCircle1Ring.width = (baseCircle1Size * scaleFactor).toInt()
        paramsCircle1Ring.height = (baseCircle1Size * scaleFactor).toInt()
        windowManager.updateViewLayout(circle1RingView, paramsCircle1Ring)

        paramsCircle2Scanner.width = (baseCircle2Width * scaleFactor).toInt()
        paramsCircle2Scanner.height = (baseCircle2Height * scaleFactor).toInt()
        windowManager.updateViewLayout(circle2ScannerView, paramsCircle2Scanner)

        statusLabel.text = "Ukuran Bulatan: ${(scaleFactor * 100).toInt()}%"
    }

    private fun snapIdealPositions() {
        updateScreenMetrics()
        // Posisi ideal berdasarkan analisa frame video_1.mp4 (Landscape)
        val c1X = (screenWidth * 0.865).toInt()
        val c1Y = (screenHeight * 0.72).toInt()

        val c2X = (screenWidth * 0.585).toInt()
        val c2Y = (screenHeight * 0.35).toInt()

        paramsCircle1Ring.x = c1X
        paramsCircle1Ring.y = c1Y
        paramsCircle1Handle.x = c1X + 12
        paramsCircle1Handle.y = (c1Y - 46).coerceAtLeast(0)
        windowManager.updateViewLayout(circle1RingView, paramsCircle1Ring)
        windowManager.updateViewLayout(circle1HandleView, paramsCircle1Handle)

        paramsCircle2Scanner.x = c2X
        paramsCircle2Scanner.y = c2Y
        paramsCircle2Handle.x = c2X + 8
        paramsCircle2Handle.y = (c2Y - 46).coerceAtLeast(0)
        windowManager.updateViewLayout(circle2ScannerView, paramsCircle2Scanner)
        windowManager.updateViewLayout(circle2HandleView, paramsCircle2Handle)

        statusLabel.text = "Status: Posisi Di-Snap 🎯"
        subStatusLabel.text = "Sesuaikan sedikit dengan tarik [✥]"
    }

    private fun toggleBot(run: Boolean) {
        if (run && AccessibilityServiceMissing()) {
            statusLabel.text = "⚠️ Aktifkan Aksesibilitas Dulu!"
            subStatusLabel.text = "Buka app Auto Mancing -> Klik tombol 2"
            return
        }
        if (run && imageReader == null) {
            statusLabel.text = "⚠️ Izin Layar Belum Aktif!"
            subStatusLabel.text = "Buka app Auto Mancing -> Klik tombol 3"
            return
        }

        isRunning = run
        if (isRunning) {
            btnStartStop.text = "⏹ HENTIKAN BOT"
            btnStartStop.background = GradientDrawable().apply {
                setColor(Color.parseColor("#EAB308"))
                cornerRadius = 18f
            }
            statusLabel.text = "Status: BOT BERJALAN 🎣"
            subStatusLabel.text = "Memindai warna tombol & bar..."
            startFishingLoop()
        } else {
            loopJob?.cancel()
            btnStartStop.text = "▶ MULAI AUTO MANCING"
            btnStartStop.background = GradientDrawable().apply {
                setColor(Color.parseColor("#28A745"))
                cornerRadius = 18f
            }
            circle1RingView.isHolding = false
            circle1RingView.invalidate()
            statusLabel.text = "Status: Berhenti (Siap)"
            subStatusLabel.text = "Tarik [✥ 1:KLIK] / [✥ 2:BAR] untuk geser"
        }
    }

    private fun AccessibilityServiceMissing(): Boolean {
        return FishingAccessibilityService.instance == null
    }

    private fun startScannerAnimation() {
        animJob = serviceScope.launch {
            var laserProgress = 0f
            var dir = 1f
            while (isActive) {
                laserProgress += 0.055f * dir
                if (laserProgress >= 1f) {
                    laserProgress = 1f
                    dir = -1f
                } else if (laserProgress <= 0f) {
                    laserProgress = 0f
                    dir = 1f
                }
                circle2ScannerView.laserRatio = laserProgress
                circle2ScannerView.invalidate()
                delay(35L)
            }
        }
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Menggeser gagang [✥] sekaligus menggerakkan cincin/kapsul target secara real-time,
     * baik saat bot berhenti maupun saat bot sedang berjalan!
     */
    private fun makeLinkedDraggable(
        handleView: View,
        handleParams: WindowManager.LayoutParams,
        targetView: View,
        targetParams: WindowManager.LayoutParams,
        offsetX: Int,
        offsetY: Int
    ) {
        var initialHX = 0
        var initialHY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        handleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialHX = handleParams.x
                    initialHY = handleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newHX = initialHX + (event.rawX - initialTouchX).toInt()
                    val newHY = initialHY + (event.rawY - initialTouchY).toInt()
                    handleParams.x = newHX
                    handleParams.y = newHY
                    targetParams.x = newHX + offsetX
                    targetParams.y = newHY + offsetY
                    windowManager.updateViewLayout(handleView, handleParams)
                    windowManager.updateViewLayout(targetView, targetParams)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Loop Utama Deteksi & Aksi Otomatis (Disesuaikan dengan video_1.mp4)
     */
    private fun startFishingLoop() {
        loopJob?.cancel()
        loopJob = serviceScope.launch(Dispatchers.Default) {
            var lastCastTimestamp = 0L

            while (isActive && isRunning) {
                // Pastikan ukuran layar sinkron jika user baru masuk mode Landscape game
                val currentMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(currentMetrics)
                if (currentMetrics.widthPixels != screenWidth || currentMetrics.heightPixels != screenHeight) {
                    withContext(Dispatchers.Main) {
                        setupVirtualDisplay()
                    }
                    delay(250L)
                    continue
                }

                val frame = captureScreenBitmap()
                if (frame == null) {
                    delay(60L)
                    continue
                }

                val loc1 = IntArray(2)
                val loc2 = IntArray(2)
                var w1 = 150
                var h1 = 150
                var w2 = 135
                var h2 = 300

                withContext(Dispatchers.Main) {
                    circle1RingView.getLocationOnScreen(loc1)
                    circle2ScannerView.getLocationOnScreen(loc2)
                    w1 = circle1RingView.width
                    h1 = circle1RingView.height
                    w2 = circle2ScannerView.width
                    h2 = circle2ScannerView.height
                }

                // Titik tengah Bulatan 1 untuk lokasi Tap/Hold
                val btnX = (loc1[0] + w1 / 2).coerceIn(15, frame.width - 15)
                val btnY = (loc1[1] + h1 / 2).coerceIn(15, frame.height - 15)

                // Pindai multi-titik di dalam Bulatan 1 (menghindari ikon/teks putih di tengah)
                val stateResult = detectButtonStateMultiPoint(frame, btnX, btnY, w1 / 4)
                val now = System.currentTimeMillis()

                when (stateResult.state) {
                    ButtonState.TARIK_GREEN -> {
                        // FASE 5: MINIGAME TARIK IKAN (Prioritas Tertinggi & Kecepatan Tinggi)
                        val holdMs = scan2DBarAndCalculateHold(
                            frame,
                            loc2[0],
                            loc2[1],
                            w2,
                            h2
                        )
                        if (holdMs > 0L) {
                            updateUiStatus("5. TARIK (Tahan ${holdMs}ms)", "Ikan di atas -> Naikkan Bar", true)
                            FishingAccessibilityService.instance?.performHold(
                                btnX.toFloat(),
                                btnY.toFloat(),
                                holdMs
                            )
                            delay(holdMs + 20L)
                        } else {
                            updateUiStatus("5. LEPAS (Turun)", "Ikan di bawah -> Turunkan Bar", false)
                            delay(55L)
                        }
                    }

                    ButtonState.STRIKE_RED_ORANGE -> {
                        // FASE 4: STRIKE! Ketuk secepat mungkin sebelum "Telat"
                        updateUiStatus("4. STRIKE! Ketuk Cepat!", "Menyentak kail pancing!", true)
                        FishingAccessibilityService.instance?.performTap(btnX.toFloat(), btnY.toFloat(), 55L)
                        delay(160L)
                    }

                    ButtonState.LEPAS_ORANGE -> {
                        // FASE 2: Tombol Oranye "Lepas!" -> Tahan sebentar untuk isi bar lemparan lalu lepas
                        updateUiStatus("2. Lepas! (Lempar Kail)", "Mengisi bar lemparan...", true)
                        FishingAccessibilityService.instance?.performTap(btnX.toFloat(), btnY.toFloat(), 65L)
                        lastCastTimestamp = System.currentTimeMillis()
                        delay(900L)
                    }

                    ButtonState.ANGKAT_WAIT -> {
                        // FASE 3: Tombol Biru Tua "Angkat" -> Jangan diklik! Tunggu berubah jadi Strike!
                        updateUiStatus("3. Tunggu Strike...", "Menunggu ikan menyambar kail", false)
                        delay(45L) // Polling cepat agar tidak telat saat berubah jadi Strike!
                    }

                    ButtonState.MANCING_BLUE -> {
                        // Cegah klik ganda saat tombol masih berstatus "..." (animasi kail terbang ke air)
                        if (now - lastCastTimestamp < 1300L) {
                            updateUiStatus("Melempar Kail (...)", "Menunggu kail masuk air", false)
                            delay(150L)
                        } else {
                            // FASE 1: Tombol Biru "Mancing 0/25" -> Tahan 480ms lalu lepas untuk melempar!
                            updateUiStatus("1. Mancing (Lempar)", "Menekan tombol Mancing...", true)
                            FishingAccessibilityService.instance?.performHold(btnX.toFloat(), btnY.toFloat(), 480L)
                            lastCastTimestamp = System.currentTimeMillis()
                            delay(650L)
                        }
                    }

                    ButtonState.UNKNOWN_OR_POPUP -> {
                        // Cek apakah kartu putih/krem hasil tangkapan ikan muncul di tengah layar
                        val cx = frame.width / 2
                        val cy = frame.height / 2
                        val centerPixel = frame.getPixel(cx, cy)
                        val cr = Color.red(centerPixel)
                        val cg = Color.green(centerPixel)
                        val cb = Color.blue(centerPixel)

                        if (cr > 225 && cg > 215 && cb > 185) {
                            updateUiStatus("6. Klaim Ikan! 🐟", "Menutup kartu hasil tangkapan", true)
                            FishingAccessibilityService.instance?.performTap(btnX.toFloat(), btnY.toFloat(), 60L)
                            delay(550L)
                        } else {
                            updateUiStatus(
                                "Memindai Tombol...",
                                "RGB(${stateResult.r},${stateResult.g},${stateResult.b}) - Geser [✥ 1:KLIK] pas ke tombol",
                                false
                            )
                            delay(90L)
                        }
                    }
                }
                frame.recycle()
            }
        }
    }

    private data class StateDetectionResult(
        val state: ButtonState,
        val r: Int,
        val g: Int,
        val b: Int
    )

    /**
     * Memindai 5 titik sampel di sekitar dalam cincin Bulatan 1 (bukan hanya 1 piksel)
     * agar tidak terganggu oleh teks putih "Mancing" / "Tarik" / ikon pancing.
     */
    private fun detectButtonStateMultiPoint(
        bitmap: Bitmap,
        cx: Int,
        cy: Int,
        offset: Int
    ): StateDetectionResult {
        val points = arrayOf(
            Pair(cx - offset, cy - offset),
            Pair(cx + offset, cy - offset),
            Pair(cx, cy - offset),
            Pair(cx - offset, cy + offset / 2),
            Pair(cx + offset, cy + offset / 2)
        )

        var lastR = 0
        var lastG = 0
        var lastB = 0

        // Prioritaskan deteksi state aktif dari 5 titik sampel
        for ((px, py) in points) {
            val sx = px.coerceIn(0, bitmap.width - 1)
            val sy = py.coerceIn(0, bitmap.height - 1)
            val p = bitmap.getPixel(sx, sy)
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            lastR = r
            lastG = g
            lastB = b

            // Lewati piksel teks putih terang
            if (r > 215 && g > 215 && b > 215) continue

            val st = classifyButtonState(r, g, b)
            if (st != ButtonState.UNKNOWN_OR_POPUP) {
                return StateDetectionResult(st, r, g, b)
            }
        }
        return StateDetectionResult(ButtonState.UNKNOWN_OR_POPUP, lastR, lastG, lastB)
    }

    /**
     * Pemindaian Grid 2D pada Bulatan 2 (2: BAR):
     * - Memindai 7 jalur vertikal di dalam kapsul Bulatan 2 (sehingga tetap akurat
     *   meskipun posisi Bulatan 2 agak bergeser ke kiri/kanan).
     * - Mendeteksi posisi vertikal Siluet Ikan Hitam (#1C262B) & Bar Hijau/Kuning (#8CE063 / #D4DC4E).
     */
    private fun scan2DBarAndCalculateHold(
        bitmap: Bitmap,
        boxX: Int,
        boxY: Int,
        boxW: Int,
        boxH: Int
    ): Long {
        // Hanya pindai bagian dalam kapsul (hindari garis tepi kapsul)
        val startX = (boxX + boxW * 0.20f).toInt().coerceIn(0, bitmap.width - 1)
        val endX = (boxX + boxW * 0.80f).toInt().coerceIn(startX + 1, bitmap.width - 1)
        val topY = (boxY + 18).coerceIn(0, bitmap.height - 1)
        val bottomY = (boxY + boxH - 18).coerceIn(topY + 1, bitmap.height - 1)

        val stepX = ((endX - startX) / 6).coerceAtLeast(3)

        var fishYSum = 0
        var fishCount = 0
        var barYSum = 0
        var barCount = 0

        for (y in topY until bottomY step 3) {
            var darkPixelsInRow = 0
            var greenYellowPixelsInRow = 0

            for (x in startX..endX step stepX) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                // Deteksi Bar Penangkap Hijau (#8CE063) atau Kuning-Hijau (#D4DC4E)
                if (g > 170 && b < 150 && (g - b) > 35 && r in 95..248) {
                    greenYellowPixelsInRow++
                }
                // Deteksi Siluet Ikan Hitam (#1C262B) di dalam bar
                else if (r < 58 && g < 68 && b < 72) {
                    darkPixelsInRow++
                }
            }

            if (greenYellowPixelsInRow >= 1) {
                barYSum += y * greenYellowPixelsInRow
                barCount += greenYellowPixelsInRow
            }
            if (darkPixelsInRow >= 2) {
                fishYSum += y * darkPixelsInRow
                fishCount += darkPixelsInRow
            }
        }

        if (fishCount == 0 && barCount == 0) {
            // Jika bar belum terbaca, berikan pulse ringan
            return 75L
        }

        val fishY = if (fishCount > 0) fishYSum / fishCount else (topY + bottomY) / 2
        val barY = if (barCount > 0) barYSum / barCount else (bottomY - 35)

        val totalH = (bottomY - topY).coerceAtLeast(1)
        val normFish = ((fishY - topY).toFloat() / totalH).coerceIn(0.05f, 0.95f)
        val normBar = ((barY - topY).toFloat() / totalH).coerceIn(0.05f, 0.95f)

        serviceScope.launch(Dispatchers.Main) {
            circle2ScannerView.detectedFishRatio = if (fishCount > 0) normFish else -1f
            circle2ScannerView.detectedBarRatio = if (barCount > 0) normBar else -1f
        }

        // Di layar Android, Y kecil = ATAS, Y besar = BAWAH.
        // diff > 0 berarti Ikan berada di ATAS Bar Hijau -> harus ditahan (Hold) agar Bar naik!
        val diff = barY - fishY

        return when {
            diff > 65 -> 240L   // Ikan jauh di atas -> Tahan lama agar cepat naik
            diff > 30 -> 155L   // Ikan sedang di atas -> Tahan sedang
            diff > 8 -> 105L    // Ikan sedikit di atas -> Tahan halus
            diff in -14..8 -> 70L // Ikan sudah di tengah bar -> Pulse penyeimbang gravitasi
            else -> 0L          // Ikan di bawah bar -> Lepas agar bar turun mengikuti ikan
        }
    }

    private enum class ButtonState {
        MANCING_BLUE,
        LEPAS_ORANGE,
        ANGKAT_WAIT,
        STRIKE_RED_ORANGE,
        TARIK_GREEN,
        UNKNOWN_OR_POPUP
    }

    /**
     * Klasifikasi warna tombol yang dikalibrasi langsung dari piksel video_1.mp4:
     * - Mancing (Biru Cerah): R~25..85, G~115..190, B~185..255
     * - Lepas! (Oranye Kuning): R~200..255, G~125..195, B~20..95
     * - Angkat (Biru Gelap/Slate): R~25..80, G~55..112, B~85..150
     * - Strike! (Merah-Oranye Terang): R~195..255, G~55..124, B~20..95
     * - Tarik (Hijau): R~50..135, G~135..225, B~25..100
     */
    private fun classifyButtonState(r: Int, g: Int, b: Int): ButtonState {
        return when {
            // Tombol "Tarik" (Hijau)
            g > 130 && g > r + 20 && g > b + 35 -> ButtonState.TARIK_GREEN

            // Tombol "Strike!" (Merah-Oranye menyala)
            r > 190 && g in 45..124 && b < 100 && (r - g) > 70 -> ButtonState.STRIKE_RED_ORANGE

            // Tombol "Lepas!" (Oranye Kekuningan)
            r > 195 && g >= 125 && b < 105 && (r - b) > 100 -> ButtonState.LEPAS_ORANGE

            // Tombol "Mancing" (Biru Laut Cerah)
            b > 170 && g in 105..195 && r < 95 && (b - r) > 85 -> ButtonState.MANCING_BLUE

            // Tombol "Angkat" (Biru Gelap Keabuan saat menunggu ikan)
            b in 80..165 && g in 50..115 && r in 20..85 && b > r + 20 -> ButtonState.ANGKAT_WAIT

            else -> ButtonState.UNKNOWN_OR_POPUP
        }
    }

    @Synchronized
    private fun captureScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null

        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            if (bmp.width != image.width) {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
                bmp.recycle()
                cropped
            } else {
                bmp
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                image.close()
            } catch (_: Exception) {}
        }
    }

    private fun updateUiStatus(mainMsg: String, subMsg: String, holding: Boolean) {
        serviceScope.launch(Dispatchers.Main) {
            statusLabel.text = mainMsg
            subStatusLabel.text = subMsg
            if (circle1RingView.isHolding != holding) {
                circle1RingView.isHolding = holding
                circle1RingView.invalidate()
            }
        }
    }

    private fun startForegroundNotification() {
        val channelId = "auto_mancing_overlay_v3"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Auto Mancing Overlay Service v3",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("🎣 Auto Mancing v3 Aktif")
            .setContentText("Sensor 1:KLIK & 2:BAR siap mendeteksi permainan")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        loopJob?.cancel()
        animJob?.cancel()
        serviceScope.cancel()
        releaseVirtualDisplay()
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {}
        try { if (::menuLayout.isInitialized) windowManager.removeView(menuLayout) } catch (_: Exception) {}
        try { if (::circle1RingView.isInitialized) windowManager.removeView(circle1RingView) } catch (_: Exception) {}
        try { if (::circle1HandleView.isInitialized) windowManager.removeView(circle1HandleView) } catch (_: Exception) {}
        try { if (::circle2ScannerView.isInitialized) windowManager.removeView(circle2ScannerView) } catch (_: Exception) {}
        try { if (::circle2HandleView.isInitialized) windowManager.removeView(circle2HandleView) } catch (_: Exception) {}
    }

    /**
     * Cincin Target Bulatan 1 (1: KLIK):
     * - Bagian tengah 100% TRANSPARAN (Color.TRANSPARENT) agar kamera MediaProjection
     *   membaca warna tombol asli game tanpa tercampur warna merah overlay!
     */
    private class TargetCircleView(context: Context) : View(context) {
        var isHolding = false

        private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f
            color = Color.parseColor("#FF3B30")
        }
        private val activeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 11f
            color = Color.parseColor("#22C55E")
        }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (width.coerceAtMost(height) / 2f) - 10f

            // Gambar cincin di bagian tepi saja, tengah dibiarkan 100% jernih untuk sensor warna
            canvas.drawCircle(cx, cy, radius, if (isHolding) activeRingPaint else outerRingPaint)

            // Garis bidik pendek di tepi luar cincin (tidak menutupi tengah)
            canvas.drawLine(cx - radius, cy, cx - radius + 16f, cy, tickPaint)
            canvas.drawLine(cx + radius - 16f, cy, cx + radius, cy, tickPaint)
            canvas.drawLine(cx, cy - radius, cx, cy - radius + 16f, tickPaint)
            canvas.drawLine(cx, cy + radius - 16f, cx, cy + radius, tickPaint)
        }
    }

    /**
     * Kapsul Sensor Bulatan 2 (2: BAR):
     * - Bagian tengah 100% TRANSPARAN agar siluet ikan hitam & bar hijau terbaca murni.
     * - Indikator laser pemindai & titik deteksi digambar di tepi kiri/kanan kapsul.
     */
    private class BarScannerView(context: Context) : View(context) {
        var laserRatio = 0.5f
        var detectedFishRatio = -1f
        var detectedBarRatio = -1f

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.parseColor("#38BDF8")
        }
        private val laserTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.parseColor("#FF2D55")
        }
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rect = RectF(6f, 6f, width - 6f, height - 6f)
            canvas.drawRoundRect(rect, 45f, 45f, borderPaint)

            // Indikator Laser di sisi tepi kiri & kanan (tengah tetap jernih!)
            val laserY = 20f + (height - 40f) * laserRatio
            canvas.drawLine(8f, laserY, 26f, laserY, laserTickPaint)
            canvas.drawLine(width - 26f, laserY, width - 8f, laserY, laserTickPaint)

            // Titik Hijau di tepi kanan = posisi Bar Hijau yang terdeteksi
            if (detectedBarRatio in 0f..1f) {
                val by = 20f + (height - 40f) * detectedBarRatio
                markerPaint.color = Color.parseColor("#22C55E")
                canvas.drawCircle(width - 14f, by, 10f, markerPaint)
            }
            // Titik Kuning di tepi kiri = posisi Ikan Hitam yang terdeteksi
            if (detectedFishRatio in 0f..1f) {
                val fy = 20f + (height - 40f) * detectedFishRatio
                markerPaint.color = Color.parseColor("#FFD700")
                canvas.drawCircle(14f, fy, 10f, markerPaint)
            }
        }
    }
}
