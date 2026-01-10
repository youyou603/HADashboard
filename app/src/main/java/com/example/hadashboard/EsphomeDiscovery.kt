package com.example.hadashboard

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class EsphomeDiscovery(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun broadcastDevice() {
        stopBroadcast()

        val prefs = context.getSharedPreferences("HADashboardPrefs", Context.MODE_PRIVATE)
        val savedId = prefs.getString("unique_device_id", "HADashboard") ?: "HADashboard"

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = savedId
            serviceType = "_esphomelib._tcp."
            port = 6053

            // HA looks for these specific TXT records to identify ESPHome devices
            setAttribute("api_version", "1.9") // Updated to a more modern version
            setAttribute("version", "2024.1.0")
            setAttribute("platform", "Android")
            setAttribute("board", "Tablet")
            setAttribute("mac", "001122334455") // Standard fake MAC if real one is unavailable
            setAttribute("project", "HADashboard")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d("ESPHome", "Discovery active as: ${info.serviceName}")
            }
            override fun onRegistrationFailed(p0: NsdServiceInfo?, p1: Int) {
                Log.e("ESPHome", "Discovery failed: $p1")
            }
            override fun onServiceUnregistered(p0: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(p0: NsdServiceInfo?, p1: Int) {}
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("ESPHome", "Registration error: ${e.message}")
        }
    }

    fun stopBroadcast() {
        try {
            registrationListener?.let {
                nsdManager.unregisterService(it)
            }
            registrationListener = null
        } catch (e: Exception) {
            Log.e("ESPHome", "Stop broadcast error: ${e.message}")
        }
    }
}