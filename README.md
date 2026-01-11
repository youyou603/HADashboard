# HADashboard an Home Assistant Kiosk App 

A minimal, high-performance Android Kiosk for Home Assistant dashboards.

![Screenshot_20260108_172352_HADashboard](https://github.com/user-attachments/assets/00f4f198-3d48-44de-964a-6a2030161573)


Markdown

# HADashboard

HADashboard turns your Android tablet into a dedicated Home Assistant kiosk with integrated hardware controls. It now supports the ESPHome API for automatic discovery and setup.

## Features

* **ESPHome Auto-Discovery:** HADashboard acts as an ESPHome device. Home Assistant will find the tablet automatically on your local network.
* **MQTT Support:** The app still supports MQTT. You can switch between ESPHome and MQTT modes via a toggle on the setup screen.
* **Remote Screen Control:** Lock the screen or wake the device remotely via Home Assistant.
* **Brightness Control:** Adjust the physical screen brightness directly from Home Assistant.
* **Device Telemetry:** Reports battery levels, screen state, and device info.
* **True Fullscreen:** Immersive mode hides status and navigation bars automatically.
* **WebView Navigation:** The back button navigates through dashboard pages instead of closing the app.

## Prerequisites

* **For ESPHome:** Ensure the ESPHome Integration is active in Home Assistant.
* **For MQTT:** An MQTT broker must be running on your Home Assistant instance.

## Setup Instructions

### 1. Installation
Download **HADashboard.apk** from the Releases section and install it on your Android device.

### 2. Connection and Permissions
1. Open HADashboard. 
2. The app will automatically prompt you to enable **Device Administrator** and **Modify System Settings**. Grant these when asked to enable hardware controls.
3. Complete the setup screen to choose between ESPHome (default) or MQTT.
4. **If using ESPHome:** Go to Home Assistant > Settings > Devices & Services. Click **Configure** on the discovered HADashboard notification.
5. **If using MQTT:** Enter your broker details manually in the app setup screen.

## Development

To build from source:
1. Open the project in Android Studio.
2. Build via Build > Build APK(s).