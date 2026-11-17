package com.assistant.fishingclicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private val REQUEST_OVERLAY = 1001
    private val REQUEST_PROJECTION = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val controlCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
            }
        }

        val title = TextView(this).apply {
            text = "🎣 Auto Mancing v3.0 (Fix Deteksi & Fleksibel)"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 6)
        }

        val subtitle = TextView(this).apply {
            text = "• Geser bulatan kapan saja lewat label [✥ 1:KLIK] & [✥ 2:BAR] (bahkan saat bot jalan).
• Tengah bulatan 100% tembus klik & tidak menghalangi deteksi warna."
            textSize = 11.5f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 14)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnOverlay = Button(this).apply {
            text = "1. Izin Overlay"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, REQUEST_OVERLAY)
                } else {
                    Toast.makeText(this@MainActivity, "Izin Pop-up Overlay sudah aktif!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "2. Aksesibilitas"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        btnRow.addView(btnOverlay)
        btnRow.addView(btnAccessibility)

        val btnStart = Button(this).apply {
            text = "3. AKTIFKAN POP-UP & DETEKSI LAYAR"
            textSize = 12f
            setBackgroundColor(Color.parseColor("#28A745"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Aktifkan 1. Izin Overlay terlebih dahulu!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (FishingAccessibilityService.instance == null) {
                    Toast.makeText(this@MainActivity, "Aktifkan 2. Aksesibilitas (Auto Mancing Clicker Service) dulu!", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@setOnClickListener
                }
                startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_PROJECTION)
            }
        }

        controlCard.addView(title)
        controlCard.addView(subtitle)
        controlCard.addView(btnRow)
        controlCard.addView(btnStart)

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            loadUrl("file:///android_asset/preview_simulator.html")
        }

        root.addView(controlCard)
        root.addView(webView)

        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val serviceIntent = Intent(this, FloatingOverlayService::class.java).apply {
                    putExtra("HAS_PROJECTION", true)
                    putExtra("RESULT_CODE", resultCode)
                    putExtra("DATA", data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this, "Overlay Aktif! Silakan buka Game Memancing Anda.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Izin tangkapan layar dibatalkan.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
