# HADashboard

A minimal, high-performance Android Kiosk for Home Assistant dashboards with integrated hardware controls and ESPHome API support.

![Screenshot_20260108_172352_HADashboard](https://github.com/user-attachments/assets/00f4f198-3d48-44de-964a-6a2030161573)

## Features

* **ESPHome Auto-Discovery:** HADashboard acts as a native ESPHome device. Home Assistant will discover the tablet automatically on your local network.
* **Integrated Media Player (Speaker):** The tablet functions as a Media Player entity in Home Assistant.
    * Supports **Text-to-Speech (TTS)** for announcements.
    * Streams audio/music URLs directly via an internal player.
    * Remote volume control and transport commands (Play/Pause/Stop).
* **Dynamic Dashboard Scaling:** Remotely adjust the dashboard zoom level via Home Assistant. Uses CSS injection to scale the entire layout (cards, images, and fonts) for a perfect fit on any screen size.
* **MQTT Support:** Optional MQTT mode available via a toggle on the setup screen for legacy setups.
* **Remote Screen Control:** Wake the device, toggle the backlight, or lock/unlock the screen remotely.
* **Kiosk & Lock Mode:** Dedicated "Kiosk Mode" switch to pin the app and prevent users from accidental exits.
* **Device Telemetry:** Real-time reporting of battery levels, storage usage, RAM stats, and system uptime.
* **True Fullscreen:** Automatic immersive mode hides status and navigation bars.

## Prerequisites

* **For ESPHome:** Ensure the ESPHome Integration is active in Home Assistant.
* **For MQTT:** An MQTT broker must be running on your Home Assistant instance.

## Setup Instructions

### 1. Installation
Download **HADashboard.apk** from the Releases section and install it on your Android device.

### 2. Connection and Permissions
1.  Open HADashboard. 
2.  The app will prompt you to enable **Device Administrator** and **Modify System Settings**. Grant these to enable hardware and screen controls.
3.  Choose between ESPHome (default) or MQTT on the setup screen.
4.  **If using ESPHome:** Go to Home Assistant > Settings > Devices & Services. Click **Configure** on the discovered HADashboard notification.
5.  **If using MQTT:** Enter your broker details manually in the app setup screen.

### 3. Adjusting the Layout
After adding the device to Home Assistant, use the **Zoom In** and **Zoom Out** button entities to scale the dashboard to your liking. The zoom level is saved locally on the device and persists after reboots.

## Development

To build from source:
1.  Open the project in Android Studio.
2.  Ensure the Protobuf dependencies are synced for ESPHome API support.
3.  Build via **Build > Build APK(s)**.

---
*Created by Twan Jaarsveld*