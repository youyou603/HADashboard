# HADashboard an Home Assistant Kiosk App 

A minimal, high-performance Android Kiosk for Home Assistant dashboards.

![Screenshot_20260108_172352_HADashboard](https://github.com/user-attachments/assets/00f4f198-3d48-44de-964a-6a2030161573)


## Features
- **Auto-Discovery:** Automatically finds your Home Assistant instance on the local network.
- **MQTT Integration:** Connects to your MQTT Broker for remote hardware control.
- **Remote Screen Control:** Lock the screen or wake the device via MQTT commands.
- **Brightness Control:** Adjust the physical screen brightness from Home Assistant.
- **Device Telemetry:** Reports battery level and device info to Home Assistant.
- **True Fullscreen:** Hides status and navigation bars (Immersive Mode).

## Prerequisites
1. **MQTT Broker:** You must have an MQTT broker (like Mosquitto) running on your Home Assistant instance.
2. **MQTT Integration:** Ensure the MQTT integration is enabled in Home Assistant so the app can "talk" to your dashboard.

## Setup Instructions

### 1. Installation
Download the APK from the [Releases](../../releases/latest) section and install it on your Android device.

### 2. Required Permissions
For hardware controls to work, you must manually grant these permissions in Android Settings:

* **Device Administrator:** Required for the **Screen Lock** feature.
  * *Settings > Security > Device Admin Apps > Enable HADashboard*
* **Modify System Settings:** Required for **Brightness Control**.
  * *Settings > Apps > HADashboard > Advanced > Modify system settings > Allow*
* **Display Over Other Apps:** Ensures the app remains in the foreground.
  * *Settings > Apps > Special App Access > Display over other apps > Enable*

### 3. Connection
Upon first launch, the app will attempt to **Auto-Discover** your Home Assistant instance. 
- Ensure your tablet is on the same Wi-Fi network as your Home Assistant.
- Enter your MQTT credentials when prompted to link the hardware sensors and controls.

## Development
To build from source:
1. Open the project in Android Studio.
2. Build via `Build > Build APK(s)`.
