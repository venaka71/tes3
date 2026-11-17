package com.assistant.fishingclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var menuLayout: LinearLayout
    private lateinit var statusLabel: TextView
    private lateinit var subStatusLabel: TextView
    private lateinit var btnStartStop: Button

    private lateinit var circle1View: TargetCircleView // 1: KLIK (Merah #FF3B30)
    private lateinit var circle2View: BarScannerView   // 2: BAR (Biru #007AFF + Laser Scanner)

    private lateinit var paramsMenu: WindowManager.LayoutParams
    private lateinit var paramsCircle1: WindowManager.LayoutParams
    private lateinit var paramsCircle2: WindowManager.LayoutParams

    private var isRunning = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var loopJob: Job? = null
    private var animJob: Job? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 400

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        createFloatingMenu()
        createCircleOverlays()
        startScannerAnimation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val data: Intent? = intent?.getParcelableExtra("DATA")
        if (resultCode != -1 && data != null) {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, data)
            setupScreenCapture()
        }
        return START_NOT_STICKY
    }

    private fun setupScreenCapture() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AutoMancingScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun createFloatingMenu() {
        menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 22, 28, 22)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EC0F172A"))
                cornerRadius = 30f
                setStroke(3, Color.parseColor("#38BDF8"))
            }
        }

        val titleView = TextView(this).apply {
            text = "🎣 Auto Mancing Overlay"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }

        statusLabel = TextView(this).apply {
            text = "Status: Siap (Geser 2 Bulatan)"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 11.5f
            setPadding(0, 6, 0, 2)
        }

        subStatusLabel = TextView(this).apply {
            text = "1:KLIK (Merah) | 2:BAR (Biru)"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            setPadding(0, 0, 0, 10)
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

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }

        val btnSnap = Button(this).apply {
            text = "🎯 Snap Posisi"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7"))
                cornerRadius = 16f
            }
            setOnClickListener {
                snapIdealPositions()
            }
        }

        val btnClose = Button(this).apply {
            text = "✕ TUTUP"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DC3545"))
                cornerRadius = 16f
            }
            setOnClickListener {
                stopSelf()
            }
        }

        bottomRow.addView(btnSnap)
        bottomRow.addView(btnClose)

        menuLayout.addView(titleView)
        menuLayout.addView(statusLabel)
        menuLayout.addView(subStatusLabel)
        menuLayout.addView(btnStartStop)
        menuLayout.addView(bottomRow)

        paramsMenu = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 36
            y = 140
        }

        makeDraggable(menuLayout, paramsMenu)
        windowManager.addView(menuLayout, paramsMenu)
    }

    private fun createCircleOverlays() {
        // Bulatan 1: Merah (1: KLIK) persis seperti di Preview
        circle1View = TargetCircleView(this)
        paramsCircle1 = WindowManager.LayoutParams(
            160, 160,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth * 0.76).toInt()
            y = (screenHeight * 0.76).toInt()
        }
        makeDraggable(circle1View, paramsCircle1)
        windowManager.addView(circle1View, paramsCircle1)

        // Bulatan 2: Biru (2: BAR) dengan Garis Laser Pemindai persis seperti di Preview
        circle2View = BarScannerView(this)
        paramsCircle2 = WindowManager.LayoutParams(
            170, 290,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth * 0.54).toInt()
            y = (screenHeight * 0.35).toInt()
        }
        makeDraggable(circle2View, paramsCircle2)
        windowManager.addView(circle2View, paramsCircle2)
    }

    private fun snapIdealPositions() {
        paramsCircle1.x = (screenWidth * 0.76).toInt()
        paramsCircle1.y = (screenHeight * 0.76).toInt()
        windowManager.updateViewLayout(circle1View, paramsCircle1)

        paramsCircle2.x = (screenWidth * 0.54).toInt()
        paramsCircle2.y = (screenHeight * 0.35).toInt()
        windowManager.updateViewLayout(circle2View, paramsCircle2)

        statusLabel.text = "Status: Posisi Di-Snap 🎯"
    }

    private fun toggleBot(run: Boolean) {
        isRunning = run
        if (isRunning) {
            btnStartStop.text = "⏹ HENTIKAN BOT"
            btnStartStop.background = GradientDrawable().apply {
                setColor(Color.parseColor("#EAB308"))
                cornerRadius = 18f
            }
            // Agar sentuhan Accessibility menembus bulatan langsung ke dalam game
            setCirclePassThrough(true)
            startFishingLoop()
        } else {
            loopJob?.cancel()
            btnStartStop.text = "▶ MULAI AUTO MANCING"
            btnStartStop.background = GradientDrawable().apply {
                setColor(Color.parseColor("#28A745"))
                cornerRadius = 18f
            }
            circle1View.isHolding = false
            circle1View.invalidate()
            setCirclePassThrough(false)
            statusLabel.text = "Status: Berhenti (Bisa Digeser)"
        }
    }

    private fun setCirclePassThrough(passThrough: Boolean) {
        val flags = if (passThrough) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        paramsCircle1.flags = flags
        paramsCircle2.flags = flags
        windowManager.updateViewLayout(circle1View, paramsCircle1)
        windowManager.updateViewLayout(circle2View, paramsCircle2)
    }

    private fun startScannerAnimation() {
        animJob = serviceScope.launch {
            var laserProgress = 0f
            var dir = 1f
            while (isActive) {
                laserProgress += 0.06f * dir
                if (laserProgress >= 1f) {
                    laserProgress = 1f
                    dir = -1f
                } else if (laserProgress <= 0f) {
                    laserProgress = 0f
                    dir = 1f
                }
                circle2View.laserRatio = laserProgress
                circle2View.invalidate()
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
            if (isRunning && (view === circle1View || view === circle2View)) {
                return@setOnTouchListener false
            }
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

    private fun startFishingLoop() {
        loopJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive && isRunning) {
                val frame = captureScreenBitmap()
                if (frame == null) {
                    delay(90L)
                    continue
                }

                val loc1 = IntArray(2)
                val loc2 = IntArray(2)
                withContext(Dispatchers.Main) {
                    circle1View.getLocationOnScreen(loc1)
                    circle2View.getLocationOnScreen(loc2)
                }

                val btnX = (loc1[0] + circle1View.width / 2).coerceIn(10, frame.width - 10)
                val btnY = (loc1[1] + circle1View.height / 2).coerceIn(10, frame.height - 10)

                // Ambil sampel warna tombol di dalam Bulatan 1 (sedikit di atas tengah agar tidak terkena teks putih)
                val sampleX = (btnX + 28).coerceIn(0, frame.width - 1)
                val sampleY = (btnY - 34).coerceIn(0, frame.height - 1)
                val pixel = frame.getPixel(sampleX, sampleY)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                when (classifyButtonState(r, g, b)) {
                    ButtonState.MANCING_BLUE -> {
                        updateUiStatus("1. Mancing (Ketuk)", "Melempar kail...", true)
                        FishingAccessibilityService.instance?.performTap(btnX.toFloat(), btnY.toFloat(), 65L)
                        delay(380L)
                    }

                    ButtonState.LEPAS_ORANGE -> {
                        updateUiStatus("2. Lepas! (Oranye)", "Lepas lemparan kail!", true)
                        FishingAccessibilityService.instance?.performTap(btnX.toFloat(), btnY.toFloat(), 60L)
                        delay(520L)
                    }

                    ButtonState.ANGKAT_WAIT -> {
                        updateUiStatus("3. Tunggu Strike...", "Menunggu ikan menyambar", false)
                        delay(80L)
                    }

                    ButtonState.STRIKE_RED_ORANGE -> {
                        updateUiStatus("4. STRIKE! Ketuk!", "Menyentak pancingan!", true)
                        FishingAccessibilityService.instance?.performTap(btnX.toFloat(), btnY.toFloat(), 55L)
                        delay(180L)
                    }

                    ButtonState.TARIK_GREEN -> {
                        val holdMs = scanBarAndCalculateHold(
                            frame,
                            loc2[0],
                            loc2[1],
                            circle2View.width,
                            circle2View.height
                        )
                        if (holdMs > 0L) {
                            updateUiStatus("5. TARIK (Tahan ${holdMs}ms)", "Ikan di atas -> Naikkan Bar", true)
                            FishingAccessibilityService.instance?.performHold(
                                btnX.toFloat(),
                                btnY.toFloat(),
                                holdMs
                            )
                            delay(holdMs + 25L)
                        } else {
                            updateUiStatus("5. LEPAS (Turun)", "Ikan di bawah -> Turunkan Bar", false)
                            delay(65L)
                        }
                    }

                    ButtonState.UNKNOWN_OR_POPUP -> {
                        val centerPixel = frame.getPixel(frame.width / 2, frame.height / 2)
                        if (Color.red(centerPixel) > 230 && Color.green(centerPixel) > 220 && Color.blue(centerPixel) > 190) {
                            updateUiStatus("6. Ikan Tertangkap!", "Menutup pop-up hasil...", true)
                            FishingAccessibilityService.instance?.performTap(btnX.toFloat(), btnY.toFloat(), 60L)
                            delay(480L)
                        } else {
                            updateUiStatus("Memindai Layar...", "RGB($r,$g,$b)", false)
                            delay(110L)
                        }
                    }
                }
                frame.recycle()
            }
        }
    }

    private fun scanBarAndCalculateHold(
        bitmap: Bitmap,
        boxX: Int,
        boxY: Int,
        boxW: Int,
        boxH: Int
    ): Long {
        val centerX = (boxX + boxW / 2).coerceIn(0, bitmap.width - 1)
        val topY = boxY.coerceIn(0, bitmap.height - 1)
        val bottomY = (boxY + boxH).coerceIn(0, bitmap.height - 1)

        var fishYSum = 0
        var fishCount = 0
        var barYSum = 0
        var barCount = 0

        for (y in topY until bottomY step 3) {
            val p = bitmap.getPixel(centerX, y)
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            // Deteksi siluet ikan hitam (#1C262B)
            if (r < 55 && g < 65 && b < 65) {
                fishYSum += y
                fishCount++
            }
            // Deteksi bar hijau/kuning (#8CE063 / #E5C846)
            else if (r in 120..245 && g in 180..255 && b < 145) {
                barYSum += y
                barCount++
            }
        }

        if (fishCount == 0 || barCount == 0) {
            return 65L
        }

        val fishY = fishYSum / fishCount
        val barY = barYSum / barCount

        // Update indikator visual di Bulatan 2
        val normFish = ((fishY - topY).toFloat() / boxH.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)
        val normBar = ((barY - topY).toFloat() / boxH.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)
        serviceScope.launch(Dispatchers.Main) {
            circle2View.detectedFishRatio = normFish
            circle2View.detectedBarRatio = normBar
        }

        val diff = barY - fishY // Positif berarti posisi ikan di ATAS bar

        return when {
            diff > 65 -> 250L
            diff > 25 -> 145L
            diff in -12..25 -> 70L
            else -> 0L
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

    private fun classifyButtonState(r: Int, g: Int, b: Int): ButtonState {
        return when {
            b > 175 && g in 105..195 && r < 90 -> ButtonState.MANCING_BLUE
            g > 135 && r in 45..135 && b < 100 -> ButtonState.TARIK_GREEN
            r > 210 && g in 70..138 && b < 85 -> ButtonState.STRIKE_RED_ORANGE
            r > 210 && g > 138 && b < 85 -> ButtonState.LEPAS_ORANGE
            b in 115..185 && g in 80..150 && r in 40..115 -> ButtonState.ANGKAT_WAIT
            else -> ButtonState.UNKNOWN_OR_POPUP
        }
    }

    private fun captureScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bmp = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun updateUiStatus(mainMsg: String, subMsg: String, holding: Boolean) {
        serviceScope.launch(Dispatchers.Main) {
            statusLabel.text = "Status: $mainMsg"
            subStatusLabel.text = subMsg
            circle1View.isHolding = holding
            circle1View.invalidate()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "auto_mancing_overlay_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Auto Mancing Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("🎣 Auto Mancing Overlay Aktif")
            .setContentText("Bulatan 1:KLIK & 2:BAR siap mendeteksi permainan")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        loopJob?.cancel()
        animJob?.cancel()
        serviceScope.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        if (::menuLayout.isInitialized) windowManager.removeView(menuLayout)
        if (::circle1View.isInitialized) windowManager.removeView(circle1View)
        if (::circle2View.isInitialized) windowManager.removeView(circle2View)
    }

    /**
     * Custom View untuk Bulatan 1 (`1: KLIK` - Merah #FF3B30 dengan Crosshair & Efek Tahan)
     */
    private class TargetCircleView(context: Context) : View(context) {
        var isHolding = false
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.parseColor("#FF3B30")
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (width.coerceAtMost(height) / 2f) - 10f

            fillPaint.color = if (isHolding) {
                Color.parseColor("#AAFF3B30")
            } else {
                Color.parseColor("#55FF3B30")
            }

            canvas.drawCircle(cx, cy, radius, fillPaint)
            canvas.drawCircle(cx, cy, radius, strokePaint)

            // Crosshair lines
            canvas.drawLine(cx - 22f, cy, cx + 22f, cy, strokePaint)
            canvas.drawLine(cx, cy - 22f, cx, cy + 22f, strokePaint)

            // Label "1: KLIK"
            canvas.drawText("1: KLIK", cx, cy + 8f, textPaint)
        }
    }

    /**
     * Custom View untuk Bulatan 2 (`2: BAR` - Biru #007AFF dengan Laser Pemindai Bergerak)
     */
    private class BarScannerView(context: Context) : View(context) {
        var laserRatio = 0.5f
        var detectedFishRatio = -1f
        var detectedBarRatio = -1f

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44007AFF")
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.parseColor("#38BDF8")
        }
        private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#FF2D55")
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rect = RectF(8f, 8f, width - 8f, height - 8f)
            canvas.drawRoundRect(rect, 80f, 80f, bgPaint)
            canvas.drawRoundRect(rect, 80f, 80f, borderPaint)

            // Garis Laser Pemindai Naik-Turun
            val laserY = 24f + (height - 48f) * laserRatio
            canvas.drawLine(18f, laserY, width - 18f, laserY, laserPaint)

            // Indikator posisi Bar Hijau & Ikan Hitam yang terdeteksi
            if (detectedBarRatio in 0f..1f) {
                val by = 20f + (height - 40f) * detectedBarRatio
                markerPaint.color = Color.parseColor("#8CE063")
                canvas.drawCircle(width - 24f, by, 10f, markerPaint)
            }
            if (detectedFishRatio in 0f..1f) {
                val fy = 20f + (height - 40f) * detectedFishRatio
                markerPaint.color = Color.parseColor("#FFD700")
                canvas.drawCircle(24f, fy, 10f, markerPaint)
            }

            canvas.drawText("2: BAR", width / 2f, 42f, textPaint)
        }
    }
}
