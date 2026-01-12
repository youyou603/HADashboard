package com.example.hadashboard

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.*
import android.net.http.SslError
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private var esphomeDiscovery: EsphomeDiscovery? = null
    private val ADMIN_INTENT_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Load layout immediately so Splash Screen is the first thing seen
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("HADashboardPrefs", MODE_PRIVATE)
        webView = findViewById(R.id.webView)
        val splashView = findViewById<View>(R.id.splashLayout)

        // Standard setup for Kiosk mode / Wake screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // 2. Run the Splash Animation sequence
        Handler(Looper.getMainLooper()).postDelayed({
            splashView.animate().alpha(0f).setDuration(1000).withEndAction {
                splashView.visibility = View.GONE

                // 3. AFTER splash disappears, decide where to go
                if (!prefs.getBoolean("setup_complete", false)) {
                    val intent = Intent(this@MainActivity, SetupActivity::class.java)
                    startActivity(intent)
                    finish()
                } else {
                    val url = prefs.getString("url", "http://homeassistant.local:8123")!!
                    startDashboard(url)
                    checkAndRequestPermissions()
                }
            }
        }, 2000)

        // Handle Back Button for WebView Navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Reset Setup on long click (and Emergency Unlock)
        splashView.setOnLongClickListener {
            try { stopLockTask() } catch (e: Exception) {} // Unlock if stuck
            prefs.edit().putBoolean("setup_complete", false).apply()
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            true
        }
    }

    private fun startDashboard(url: String) {
        webView.visibility = View.VISIBLE
        setupWebView()
        webView.loadUrl(url)

        val useEsphome = prefs.getBoolean("use_esphome", true)

        if (useEsphome) {
            Log.d("DASHBOARD", "Starting ESPHome Discovery & API Engine...")
            esphomeDiscovery = EsphomeDiscovery(this)
            esphomeDiscovery?.broadcastDevice()

            val apiIntent = Intent(this, EsphomeApiService::class.java)
            startService(apiIntent)
        } else {
            Log.d("DASHBOARD", "Starting MQTT Service...")
            val serviceIntent = Intent(this, MqttService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        }

        val filter = IntentFilter("DASHBOARD_COMMAND")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }
    }

    private fun setupWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val failingUrl = request.url.toString()
                    if (failingUrl.startsWith("http://")) {
                        val httpsUrl = failingUrl.replace("http://", "https://")
                        view?.loadUrl(httpsUrl)
                    }
                }
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgentString.replace("wv", "")
        }

        webView.setBackgroundColor(0)
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.getStringExtra("action")
            Log.d("MainActivity", "Received Action: $action")

            when (action) {
                "RELOAD_URL", "RELOAD" -> webView.reload()
                "SCREEN_ON" -> turnScreenOn()
                "SCREEN_OFF" -> turnScreenOff()
                "SET_BRIGHTNESS" -> {
                    val value = intent.getIntExtra("value", 128)
                    setSystemBrightness(value)
                }
                "LOCK_APP" -> {
                    try {
                        startLockTask()
                        Toast.makeText(this@MainActivity, "Kiosk Mode Active", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Lock Task failed: ${e.message}")
                    }
                }
                "UNLOCK_APP" -> {
                    try {
                        stopLockTask()
                        Toast.makeText(this@MainActivity, "Kiosk Mode Disabled", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Unlock Task failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun setSystemBrightness(value: Int) {
        if (Settings.System.canWrite(this)) {
            try {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
                val lp = window.attributes
                lp.screenBrightness = value / 255.0f
                window.attributes = lp
            } catch (e: Exception) {
                Log.e("MainActivity", "Brightness Error: ${e.message}")
            }
        }
    }

    private fun turnScreenOn() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // Fix: Request the keyguard to dismiss so the dashboard shows immediately
            km.requestDismissKeyguard(this@MainActivity, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        hideSystemUI()
    }

    private fun turnScreenOff() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            try {
                dpm.lockNow()
            } catch (e: Exception) {
                Log.e("MainActivity", "Lock failed: ${e.message}")
            }
        } else {
            Toast.makeText(this, "Admin permission required to lock screen", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        esphomeDiscovery?.stopBroadcast()
        stopService(Intent(this, EsphomeApiService::class.java))
        stopService(Intent(this, MqttService::class.java))
        try { unregisterReceiver(commandReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun checkAndRequestPermissions() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Dashboard needs this to turn off the screen.")
            startActivityForResult(intent, ADMIN_INTENT_REQUEST_CODE)
        }
        checkBrightnessPermission()
    }

    private fun checkBrightnessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }
}