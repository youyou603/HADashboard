package com.example.hadashboard

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.*
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
    private lateinit var setupLayout: View
    private lateinit var prefs: SharedPreferences
    private lateinit var nsdManager: NsdManager

    private lateinit var editBroker: EditText
    private lateinit var editUrl: EditText
    private lateinit var editUser: EditText
    private lateinit var editPass: EditText
    private lateinit var editPort: EditText
    private lateinit var editDeviceId: EditText

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val ADMIN_INTENT_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        prefs = getSharedPreferences("HADashboardPrefs", MODE_PRIVATE)
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        webView = findViewById(R.id.webView)
        setupLayout = findViewById(R.id.setupLayout)
        val splashView = findViewById<View>(R.id.splashLayout)

        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnScan = findViewById<Button>(R.id.btnScan)

        editBroker = findViewById(R.id.editBroker)
        editUrl = findViewById(R.id.editUrl)
        editUser = findViewById(R.id.editUser)
        editPass = findViewById(R.id.editPass)
        editPort = findViewById(R.id.editPort)
        editDeviceId = findViewById(R.id.editDeviceId)

        editBroker.setText(prefs.getString("broker", ""))
        editUrl.setText(prefs.getString("url", ""))
        editUser.setText(prefs.getString("user", ""))
        editPass.setText(prefs.getString("pass", ""))
        editPort.setText(prefs.getString("port", ""))
        editDeviceId.setText(prefs.getString("unique_device_id", ""))

        Handler(Looper.getMainLooper()).postDelayed({
            splashView.animate().alpha(0f).setDuration(1000).withEndAction {
                splashView.visibility = View.GONE
            }
        }, 2000)

        btnScan.setOnClickListener {
            Toast.makeText(this, "Scanning for Home Assistant...", Toast.LENGTH_SHORT).show()
            startDiscovery()
        }

        if (!prefs.getString("broker", "").isNullOrEmpty() && !prefs.getString("unique_device_id", "").isNullOrEmpty()) {
            startDashboard(prefs.getString("url", "https://google.com")!!)
        }

        btnSave.setOnClickListener {
            val deviceId = editDeviceId.text.toString().trim()
            if (deviceId.isEmpty()) {
                Toast.makeText(this, "Please enter a Device ID first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("unique_device_id", deviceId)
                .putString("broker", editBroker.text.toString())
                .putString("url", editUrl.text.toString())
                .putString("user", editUser.text.toString())
                .putString("pass", editPass.text.toString())
                .putString("port", editPort.text.toString())
                .apply()

            startDashboard(editUrl.text.toString())
        }
    }

    private fun checkAndRequestPermissions() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to lock the screen via Home Assistant.")
            startActivityForResult(intent, ADMIN_INTENT_REQUEST_CODE)
        } else {
            checkBrightnessPermission()
        }
    }

    private fun checkBrightnessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ADMIN_INTENT_REQUEST_CODE) {
            checkBrightnessPermission()
        }
    }

    private fun startDiscovery() {
        stopDiscovery()
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("home-assistant")) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            runOnUiThread {
                                val host = resolvedInfo.host.hostAddress
                                editUrl.setText("http://$host:${resolvedInfo.port}")
                                editBroker.setText(host)
                                editPort.setText("1883")
                                if(editDeviceId.text.isEmpty()) editDeviceId.setText("tablet_kiosk")
                                stopDiscovery()
                            }
                        }
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    })
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(regType: String) {}
            override fun onStartDiscoveryFailed(serviceInfo: String, errorCode: Int) { stopDiscovery() }
            override fun onStopDiscoveryFailed(serviceInfo: String, errorCode: Int) {}
        }
        nsdManager.discoverServices("_home-assistant._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) {}
            discoveryListener = null
        }
    }

    private fun startDashboard(url: String) {
        setupLayout.visibility = View.GONE
        webView.visibility = View.VISIBLE
        setupWebView()
        webView.loadUrl(url)

        val serviceIntent = Intent(this, MqttService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

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

            // 1. Handle SSL/HTTPS certificates (Crucial for Home Assistant)
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed() // Trust the certificate and load the page
            }

            // 2. Only upgrade to HTTPS if HTTP actually fails to connect
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // Only trigger this if the main page fails, not just a random image or script
                if (request?.isForMainFrame == true) {
                    val failingUrl = request.url.toString()
                    if (failingUrl.startsWith("http://")) {
                        Log.w("WEBVIEW", "HTTP Failed, trying HTTPS fallback...")
                        val httpsUrl = failingUrl.replace("http://", "https://")
                        view?.loadUrl(httpsUrl)
                    }
                }
            }
        }
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        // Added UserAgent fix for HA Card compatibility
        s.userAgentString = s.userAgentString.replace("wv", "")
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
            when (intent?.getStringExtra("action")) {
                "RELOAD" -> webView.reload()
                "SCREEN_ON" -> {
                    // FIX 2: RE-APPLY WAKE LOGIC
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
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
        try { unregisterReceiver(commandReceiver) } catch (e: Exception) {}
    }
}