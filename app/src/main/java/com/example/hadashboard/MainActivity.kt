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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private var esphomeDiscovery: EsphomeDiscovery? = null
    private val ADMIN_INTENT_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("HADashboardPrefs", MODE_PRIVATE)

        // Ensure we go to setup if not configured
        if (!prefs.getBoolean("setup_complete", false)) {
            val intent = Intent(this, SetupActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

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

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        checkAndRequestPermissions()

        webView = findViewById(R.id.webView)
        val splashView = findViewById<View>(R.id.splashLayout)

        // Reset Setup on long click of the splash screen
        splashView.setOnLongClickListener {
            prefs.edit().putBoolean("setup_complete", false).apply()
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            true
        }

        Handler(Looper.getMainLooper()).postDelayed({
            splashView.animate().alpha(0f).setDuration(1000).withEndAction {
                splashView.visibility = View.GONE
            }
        }, 2000)

        val url = prefs.getString("url", "http://homeassistant.local:8123")!!
        startDashboard(url)
    }

    private fun startDashboard(url: String) {
        webView.visibility = View.VISIBLE
        setupWebView()
        webView.loadUrl(url)

        // UPDATED: Default is TRUE because Toggle OFF in Setup = ESPHome
        val useEsphome = prefs.getBoolean("use_esphome", true)

        if (useEsphome) {
            Log.d("DASHBOARD", "Starting ESPHome Discovery & API Engine...")

            // Start mDNS Discovery so it shows up in HA
            esphomeDiscovery = EsphomeDiscovery(this)
            esphomeDiscovery?.broadcastDevice()

            // Start the ESPHome API Service
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
                handler?.proceed() // Allow local self-signed certs
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
            // Clean up User Agent to prevent HA login issues
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
                // Supports both ESPHome ("RELOAD_URL") and MQTT ("RELOAD") formats
                "RELOAD_URL", "RELOAD" -> {
                    Log.d("MainActivity", "Reloading Dashboard...")
                    webView.reload()
                }
                "SCREEN_ON" -> turnScreenOn()
                "SCREEN_OFF" -> turnScreenOff()
                "SET_BRIGHTNESS" -> {
                    val value = intent.getIntExtra("value", 128)
                    setSystemBrightness(value)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
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
        // Stop discovery so it doesn't linger in HA sidebar after app closes
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