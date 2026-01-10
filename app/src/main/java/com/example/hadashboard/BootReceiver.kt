package com.example.hadashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("HADashboardPrefs", Context.MODE_PRIVATE)

            if (prefs.getBoolean("setup_complete", false)) {
                val useEsphome = prefs.getBoolean("use_esphome", false)

                if (useEsphome) {
                    // Start ESPHome API background service
                    val serviceIntent = Intent(context, EsphomeApiService::class.java)
                    context.startService(serviceIntent)
                } else {
                    // Start MQTT background service
                    val serviceIntent = Intent(context, MqttService::class.java)
                    ContextCompat.startForegroundService(context, serviceIntent)
                }

                // Launch the Dashboard UI
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(activityIntent)
            }
        }
    }
}