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
    private var deviceId: String = "hadashboard_tablet"
    private lateinit var prefs: SharedPreferences

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        prefs = getSharedPreferences("HADashboardPrefs", MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        deviceId = prefs.getString("unique_device_id", "hadashboard_tablet") ?: "hadashboard_tablet"

        createNotificationChannel()
        startForeground(1, NotificationCompat.Builder(this, "mqtt_channel")
            .setContentTitle("HADashboard Active")
            .setContentText("Connected as: $deviceId")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build())

        stopMqtt()
        setupMqtt()
        startStatusReporting()

        return START_STICKY
    }

    private fun setupMqtt() {
        val brokerIp = prefs.getString("broker", "") ?: return
        val user = prefs.getString("user", "")
        val pass = prefs.getString("pass", "")
        val serverUri = "tcp://$brokerIp:1883"

        Thread {
            try {
                mqttClient = MqttClient(serverUri, deviceId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 15
                    keepAliveInterval = 60
                    if (!user.isNullOrEmpty()) userName = user
                    if (!pass.isNullOrEmpty()) password = pass.toCharArray()
                }

                mqttClient?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        mqttClient?.subscribe("hadashboard/$deviceId/control", 1)

                        // PERSISTENT GUARD: Check SharedPreferences so we don't spam HA
                        val alreadyDiscovered = prefs.getBoolean("mqtt_discovery_done_$deviceId", false)
                        if (!alreadyDiscovered) {
                            sendDiscovery()
                            prefs.edit().putBoolean("mqtt_discovery_done_$deviceId", true).apply()
                            Log.d(TAG, "First time discovery sent and saved.")
                        }

                        reportInstantStatus()
                    }
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        handleCommand(message?.toString() ?: "")
                    }
                    override fun connectionLost(cause: Throwable?) {
                        Log.e(TAG, "Connection Lost: ${cause?.message}")
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                mqttClient?.connect(options)
            } catch (e: Exception) {
                Log.e(TAG, "Mqtt Setup Error: ${e.message}")
            }
        }.start()
    }

    private fun sendDiscovery() {
        val deviceJson = JSONObject().apply {
            put("identifiers", arrayOf(deviceId))
            put("name", deviceId.replace("_", " ").uppercase())
            put("model", Build.MODEL)
            put("sw_version", "1.6")
        }

        fun discover(component: String, name: String, key: String, extra: JSONObject? = null) {
            val config = JSONObject().apply {
                put("name", name)
                put("unique_id", "${deviceId}_$key")
                put("device", deviceJson)
                if (component == "button" || component == "number") {
                    put("command_topic", "hadashboard/$deviceId/control")
                } else {
                    put("state_topic", "hadashboard/$deviceId/status/$key")
                }
                extra?.keys()?.forEach { put(it, extra.get(it)) }
            }
            // Discovery MUST be retained = true so HA sees it whenever it reboots
            publishSafe("homeassistant/$component/$deviceId/$key/config", config.toString(), true)
        }

        discover("button", "Reload", "reload", JSONObject().apply { put("payload_press", "RELOAD") })
        discover("button", "Screen On", "screen_on", JSONObject().apply { put("payload_press", "SCREEN_ON") })
        discover("button", "Screen Off", "screen_off", JSONObject().apply { put("payload_press", "SCREEN_OFF") })
        discover("sensor", "Battery", "battery", JSONObject().apply { put("unit_of_measurement", "%"); put("device_class", "battery") })
        discover("sensor", "Screen State", "screen_state", JSONObject().apply { put("icon", "mdi:monitor") })

        val brightnessConfig = JSONObject().apply {
            put("name", "Brightness")
            put("unique_id", "${deviceId}_brightness")
            put("device", deviceJson)
            put("command_topic", "hadashboard/$deviceId/control")
            put("state_topic", "hadashboard/$deviceId/status/brightness")
            put("command_template", "BRIGHTNESS:{{ value }}")
            put("min", 0)
            put("max", 100)
            put("step", 1)
            put("unit_of_measurement", "%")
            put("icon", "mdi:brightness-6")
        }
        publishSafe("homeassistant/number/$deviceId/brightness/config", brightnessConfig.toString(), true)
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
                intent.putExtra("action", "SCREEN_ON")
                reportScreenState("ON")
            }
            payload.startsWith("BRIGHTNESS:") -> {
                val pct = payload.substringAfter("BRIGHTNESS:").trim().toIntOrNull() ?: 50
                val systemValue = (pct * 2.55).toInt().coerceIn(0, 255)
                setBrightness(systemValue)
                publishSafe("hadashboard/$deviceId/status/brightness", pct.toString(), true)
                return
            }
        }
        sendBroadcast(intent)
    }

    private fun setBrightness(value: Int) {
        if (Settings.System.canWrite(this)) {
            try {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            } catch (e: Exception) { Log.e(TAG, "Brightness Error: ${e.message}") }
        }
    }

    private fun reportInstantStatus() {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            publishSafe("hadashboard/$deviceId/status/battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString(), true)

            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            reportScreenState(if (pm.isInteractive) "ON" else "OFF")

            val cur = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            val pct = (cur / 2.55).toInt().coerceIn(0, 100)
            publishSafe("hadashboard/$deviceId/status/brightness", pct.toString(), true)
        } catch (e: Exception) { }
    }

    private fun reportScreenState(state: String) {
        publishSafe("hadashboard/$deviceId/status/screen_state", state, true)
    }

    private fun publishSafe(topic: String, payload: String, retained: Boolean = false) {
        try {
            if (mqttClient?.isConnected == true) {
                val message = MqttMessage(payload.toByteArray()).apply { isRetained = retained; qos = 1 }
                mqttClient?.publish(topic, message)
            }
        } catch (e: Exception) { }
    }

    private fun stopMqtt() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            mqttClient = null
        } catch (e: Exception) { }
    }

    private fun startStatusReporting() {
        handler.removeCallbacksAndMessages(null)
        handler.post(object : Runnable {
            override fun run() {
                reportInstantStatus()
                handler.postDelayed(this, 30000)
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("mqtt_channel", "MQTT Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopMqtt()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}