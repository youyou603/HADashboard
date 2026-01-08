package com.example.hadashboard

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.*
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

class MqttService : Service() {

    private var mqttClient: MqttClient? = null
    private val TAG = "MQTT_LOG"
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminName: ComponentName
    private val handler = Handler(Looper.getMainLooper())

    // Clean, professional Device ID based on hardware model
    private val deviceId = "hadashboard_" + Build.MODEL.lowercase().replace(Regex("[^a-z0-9]"), "_")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminName = ComponentName(this, MyDeviceAdminReceiver::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(1, NotificationCompat.Builder(this, "mqtt_channel")
            .setContentTitle("HADashboard Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build())

        setupMqtt()
        startStatusReporting()
        return START_STICKY
    }

    private fun setupMqtt() {
        val prefs = getSharedPreferences("HADashboardPrefs", MODE_PRIVATE)
        val brokerIp = prefs.getString("broker", "") ?: return
        val user = prefs.getString("user", "")
        val pass = prefs.getString("pass", "")

        val serverUri = "tcp://$brokerIp:1883"

        try {
            mqttClient = MqttClient(serverUri, deviceId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                if (!user.isNullOrEmpty()) userName = user
                if (!pass.isNullOrEmpty()) password = pass.toCharArray()
            }

            mqttClient?.setCallback(object : MqttCallback {
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    handleCommand(message?.toString() ?: "")
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                override fun connectionLost(cause: Throwable?) { Log.e(TAG, "Connection Lost") }
            })

            Thread {
                try {
                    Log.d(TAG, "Connecting to $serverUri...")
                    mqttClient?.connect(options)

                    if (mqttClient?.isConnected == true) {
                        mqttClient?.subscribe("hadashboard/control", 1)

                        // 1. Send Discovery
                        sendDiscovery()

                        // 2. Wait for HA to register the device
                        Thread.sleep(1500)

                        // 3. Force first update immediately
                        reportInstantStatus()
                        Log.d(TAG, "Connected and reported status successfully")
                    }
                } catch (e: Exception) { Log.e(TAG, "Connection Error: ${e.message}") }
            }.start()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun sendDiscovery() {
        val deviceJson = JSONObject().apply {
            put("identifiers", arrayOf(deviceId))
            put("name", "HADashboard")
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("sw_version", "1.1")
        }

        fun discover(component: String, name: String, key: String, extra: JSONObject? = null) {
            val config = JSONObject().apply {
                put("name", name)
                put("unique_id", "${deviceId}_$key")
                put("device", deviceJson)
                if (component == "button" || component == "number") {
                    put("command_topic", "hadashboard/control")
                } else {
                    put("state_topic", "hadashboard/status/$key")
                }
                extra?.keys()?.forEach { put(it, extra.get(it)) }
            }
            publishSafe("homeassistant/$component/$deviceId/$key/config", config.toString(), true)
        }

        // Entities
        discover("button", "Reload Dashboard", "reload", JSONObject().apply { put("payload_press", "RELOAD") })
        discover("button", "Screen On", "screen_on", JSONObject().apply { put("payload_press", "SCREEN_ON") })
        discover("button", "Screen Off", "screen_off", JSONObject().apply { put("payload_press", "SCREEN_OFF") })
        discover("sensor", "Battery", "battery", JSONObject().apply { put("unit_of_measurement", "%"); put("device_class", "battery") })
        discover("sensor", "Storage", "storage", JSONObject().apply { put("unit_of_measurement", "GB"); put("icon", "mdi:database") })
        discover("sensor", "Screen State", "screen_state", JSONObject().apply { put("icon", "mdi:monitor") })
        discover("number", "Brightness", "brightness", JSONObject().apply {
            put("command_template", "BRIGHTNESS:{{ value }}")
            put("min", 0); put("max", 255); put("step", 1)
            put("icon", "mdi:brightness-6")
        })
    }

    private fun handleCommand(payload: String) {
        val intent = Intent("DASHBOARD_COMMAND")
        when {
            payload == "RELOAD" -> intent.putExtra("action", "RELOAD")
            payload == "SCREEN_OFF" -> {
                if (dpm.isAdminActive(adminName)) dpm.lockNow()
                reportScreenState("OFF")
                return
            }
            payload == "SCREEN_ON" -> {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "HA:Wake")
                wl.acquire(3000)
                intent.putExtra("action", "SCREEN_ON")
                reportScreenState("ON")
            }
            payload.startsWith("BRIGHTNESS:") -> {
                val level = payload.substringAfter("BRIGHTNESS:").toIntOrNull() ?: 128
                setBrightness(level)
                return
            }
        }
        sendBroadcast(intent)
    }

    private fun setBrightness(value: Int) {
        if (Settings.System.canWrite(this)) {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
        }
    }

    private fun startStatusReporting() {
        handler.removeCallbacksAndMessages(null)
        handler.post(object : Runnable {
            override fun run() {
                if (mqttClient?.isConnected == true) reportInstantStatus()
                handler.postDelayed(this, 30000) // 30 seconds
            }
        })
    }

    private fun reportInstantStatus() {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            publishSafe("hadashboard/status/battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString(), true)

            val stat = StatFs(Environment.getDataDirectory().path)
            val available = (stat.blockSizeLong * stat.availableBlocksLong) / (1024 * 1024 * 1024)
            publishSafe("hadashboard/status/storage", available.toString(), true)

            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            reportScreenState(if (pm.isInteractive) "ON" else "OFF")
        } catch (e: Exception) { Log.e(TAG, "Report failed: ${e.message}") }
    }

    private fun reportScreenState(state: String) {
        publishSafe("hadashboard/status/screen_state", state, true)
    }

    private fun publishSafe(topic: String, payload: String, retained: Boolean = false) {
        try {
            val message = MqttMessage(payload.toByteArray()).apply { isRetained = retained; qos = 1 }
            mqttClient?.publish(topic, message)
        } catch (e: Exception) { Log.e(TAG, "Publish Error: ${e.message}") }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("mqtt_channel", "MQTT", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { mqttClient?.disconnect(); mqttClient?.close() } catch (e: Exception) {}
    }
}