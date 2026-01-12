package com.example.hadashboard

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import java.security.SecureRandom

class SetupActivity : AppCompatActivity() {

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private lateinit var nsdManager: NsdManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager

        val editUrl = findViewById<EditText>(R.id.editUrl)
        val editDeviceId = findViewById<EditText>(R.id.editDeviceId)
        val editBroker = findViewById<EditText>(R.id.editBroker)
        val editPort = findViewById<EditText>(R.id.editPort)
        val editUser = findViewById<EditText>(R.id.editUser)
        val editPass = findViewById<EditText>(R.id.editPass)

        val btnScan = findViewById<Button>(R.id.btnScan)
        val connectionToggle = findViewById<MaterialSwitch>(R.id.connectionToggle)
        val mqttGroup = findViewById<LinearLayout>(R.id.mqttInputGroup)
        val esphomeGroup = findViewById<LinearLayout>(R.id.esphomeInfoGroup)
        val apiKeyDisplay = findViewById<TextView>(R.id.esphomeApiKey)
        val btnSave = findViewById<Button>(R.id.saveButton)

        val prefs = getSharedPreferences("HADashboardPrefs", Context.MODE_PRIVATE)

        // Load or Generate ESPHome Key
        val currentKey = prefs.getString("esphome_key", null) ?: generateEsphomeKey()
        apiKeyDisplay.text = currentKey

        // INITIAL STATE: Switch is OFF (ESPHome mode)
        connectionToggle.isChecked = false
        mqttGroup.visibility = View.GONE
        esphomeGroup.visibility = View.VISIBLE

        connectionToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Switch ON = Show MQTT
                mqttGroup.visibility = View.VISIBLE
                esphomeGroup.visibility = View.GONE
            } else {
                // Switch OFF = Show ESPHome
                mqttGroup.visibility = View.GONE
                esphomeGroup.visibility = View.VISIBLE
            }
        }

        btnScan.setOnClickListener {
            Toast.makeText(this, "Scanning for Home Assistant...", Toast.LENGTH_SHORT).show()
            // If toggle is NOT checked, we are in ESPHome mode
            startDiscovery(editUrl, editBroker, editPort, !connectionToggle.isChecked)
        }

        btnSave.setOnClickListener {
            val deviceId = editDeviceId.text.toString().trim()
            if (deviceId.isEmpty()) {
                Toast.makeText(this, "Please enter a Device Name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val editor = prefs.edit()
            editor.putString("url", editUrl.text.toString())
            editor.putString("unique_device_id", deviceId)

            // isChecked == false means use_esphome is true
            val useEsphome = !connectionToggle.isChecked
            editor.putBoolean("use_esphome", useEsphome)

            if (useEsphome) {
                editor.putString("esphome_key", currentKey)
            } else {
                editor.putString("broker", editBroker.text.toString())
                editor.putString("port", editPort.text.toString())
                editor.putString("user", editUser.text.toString())
                editor.putString("pass", editPass.text.toString())
            }

            editor.putBoolean("setup_complete", true)
            editor.apply()

            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        }
    }

    private fun startDiscovery(urlField: EditText, brokerField: EditText, portField: EditText, isEsphome: Boolean) {
        stopDiscovery()
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("NSD", "Discovery started")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Checking for both standard HA and Hassio discovery types
                if (serviceInfo.serviceType.contains("home-assistant") || serviceInfo.serviceType.contains("hassio")) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            runOnUiThread {
                                val host = resolvedInfo.host.hostAddress
                                urlField.setText("http://$host:${resolvedInfo.port}")
                                if (!isEsphome) {
                                    brokerField.setText(host)
                                    portField.setText("1883")
                                }
                                // UPDATED: Changed from "Found HA!" to "Found Home Assistant"
                                Toast.makeText(this@SetupActivity, "Found Home Assistant", Toast.LENGTH_SHORT).show()
                                stopDiscovery()
                            }
                        }
                        override fun onResolveFailed(si: NsdServiceInfo, err: Int) {
                            Log.e("NSD", "Resolve failed: $err")
                        }
                    })
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(regType: String) {}
            override fun onStartDiscoveryFailed(s: String, err: Int) { stopDiscovery() }
            override fun onStopDiscoveryFailed(s: String, err: Int) {}
        }
        // Scan for both potential service types
        nsdManager.discoverServices("_home-assistant._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun stopDiscovery() {
        if (discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) {}
            discoveryListener = null
        }
    }

    private fun generateEsphomeKey(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
    }
}