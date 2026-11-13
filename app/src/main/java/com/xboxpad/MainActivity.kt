package com.xboxpad

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvOverlay: TextView
    private lateinit var tvShizuku: TextView
    private lateinit var tvPad: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnShizuku: Button
    private lateinit var btnStart: Button

    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> handler.post { refresh() } }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 700)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun shizukuAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    private fun shizukuGranted(): Boolean = try {
        shizukuAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "XboxPad"
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "A floating Xbox 360 controller for emulators. Tap the floating X to show or hide it."
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        })

        tvOverlay = TextView(this).apply { textSize = 16f }
        root.addView(tvOverlay)
        btnOverlay = Button(this).apply {
            text = "Allow display over other apps"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        root.addView(btnOverlay)

        tvShizuku = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(tvShizuku)
        btnShizuku = Button(this).apply {
            setOnClickListener {
                if (!shizukuAlive()) {
                    val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (launch != null) {
                        startActivity(launch)
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                    }
                } else {
                    try {
                        Shizuku.requestPermission(100)
                    } catch (t: Throwable) {
                        Toast.makeText(this@MainActivity, "Could not ask Shizuku: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        root.addView(btnShizuku)

        tvPad = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(tvPad)

        btnStart = Button(this).apply {
            setOnClickListener {
                if (OverlayService.running) {
                    stopService(Intent(this@MainActivity, OverlayService::class.java))
                } else if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Allow display over other apps first", Toast.LENGTH_LONG).show()
                } else if (!shizukuGranted()) {
                    Toast.makeText(this@MainActivity, "Start Shizuku and allow access first", Toast.LENGTH_LONG).show()
                } else {
                    startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                }
                handler.postDelayed({ refresh() }, 300)
            }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(16)
        root.addView(btnStart, lp)

        root.addView(slider("Button size", 40, 160, "scale", 100, prefs))
        root.addView(slider("Opacity", 20, 100, "alpha", 70, prefs))

        root.addView(Button(this).apply {
            text = "Reset layout positions"
            setOnClickListener {
                val e = prefs.edit()
                for (k in prefs.all.keys) {
                    if (k.startsWith("pos_") || k.startsWith("bubble_")) e.remove(k)
                }
                e.putLong("layout_rev", System.currentTimeMillis())
                e.apply()
                Toast.makeText(this@MainActivity, "Layout reset", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(TextView(this).apply {
            text = "How to use\n" +
                "1. Start Shizuku (it can start itself with Wireless debugging).\n" +
                "2. Allow overlay + Shizuku access above, then tap Start.\n" +
                "3. Open your emulator AFTER starting this, so it sees the controller.\n" +
                "4. Tap the floating X to show/hide the buttons. Drag the X to move it.\n" +
                "5. Long-press the X to move or hide-and-place controls; tap X again to save."
            textSize = 13f
            setPadding(0, dp(20), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })

        Shizuku.addRequestPermissionResultListener(permListener)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun slider(label: String, min: Int, max: Int, key: String, def: Int, prefs: android.content.SharedPreferences): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val title = TextView(this).apply { textSize = 14f }
        val bar = SeekBar(this)
        bar.max = max - min
        val cur = prefs.getInt(key, def).coerceIn(min, max)
        bar.progress = cur - min
        title.text = "$label: $cur%"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                title.text = "$label: ${progress + min}%"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putInt(key, (sb?.progress ?: 0) + min).apply()
            }
        })
        box.addView(title)
        box.addView(bar)
        return box
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val alive = shizukuAlive()
        val granted = shizukuGranted()

        tvOverlay.text = (if (overlayOk) "✔ " else "✘ ") + "Display over other apps"
        btnOverlay.visibility = if (overlayOk) android.view.View.GONE else android.view.View.VISIBLE

        tvShizuku.text = when {
            !alive -> "✘ Shizuku is not running"
            !granted -> "✘ Shizuku is running, access not allowed yet"
            else -> "✔ Shizuku connected"
        }
        btnShizuku.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        btnShizuku.text = if (!alive) "Open Shizuku" else "Allow Shizuku access"

        val st = GamepadBackend.state
        val log = GamepadBackend.log
        tvPad.text = "Virtual controller: " + when (st) {
            GamepadBackend.State.OFF -> "off"
            GamepadBackend.State.STARTING -> "starting..."
            GamepadBackend.State.READY -> "ready ✔"
            GamepadBackend.State.ERROR -> "error ✘"
        } + (if (log.isNotEmpty()) "\n$log" else "")

        btnStart.text = if (OverlayService.running) "Stop floating controller" else "Start floating controller"
        btnStart.gravity = Gravity.CENTER
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
