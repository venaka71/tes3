package com.assistant.fishingclicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
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

        // Top Control Header
        val controlCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 32, 36, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
            }
        }

        val title = TextView(this).apply {
            text = "🎣 Auto Mancing Overlay (Sesuai Preview)"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }

        val subtitle = TextView(this).apply {
            text = "Aktifkan langkah 1 & 2, lalu tekan tombol 3 untuk memunculkan Pop-up Menu + Bulatan 1:KLIK (Merah) & 2:BAR (Biru dengan Laser Scanner)."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, 16)
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
            text = "3. MUNCULKAN POP-UP & 2 BULATAN DI LAYAR"
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
                    return@setOnClickListener
                }
                startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_PROJECTION)
            }
        }

        controlCard.addView(title)
        controlCard.addView(subtitle)
        controlCard.addView(btnRow)
        controlCard.addView(btnStart)

        // Built-in Simulator WebView so user can test the overlay right here or switch to the real game
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
        if (requestCode == REQUEST_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, FloatingOverlayService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            startForegroundService(serviceIntent)
            Toast.makeText(this, "Pop-up & 2 Bulatan Aktif! Anda bisa mengujinya di bawah atau buka Game.", Toast.LENGTH_LONG).show()
        }
    }
}
