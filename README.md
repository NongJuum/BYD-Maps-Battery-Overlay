# ⚡ BYD Smart EV Route & Battery Overlay

<p align="center">
  <a href="https://github.com/NongJuum/BYD-Maps-Battery-Overlay/releases/latest">
    <img src="https://img.shields.io/badge/Download-Latest%20APK%20(v1.0.0)-00C853?style=for-the-badge&logo=android&logoColor=white" />
  </a>
</p>

<p align="center">
  <a href="README.md"><b>English</b></a> | <a href="README_TH.md"><b>🇹🇭 ภาษาไทย</b></a>
</p>

<p align="center">
  <b>A smart, real-time floating overlay application that predicts destination battery percentage (SoC %) and actual remaining range for all BYD electric vehicles.</b><br>
  Works seamlessly alongside Google Maps and directly reads telemetry from vehicle internal systems (OBD / BMS / CAN bus) without requiring any extra OBD dongles or external hardware.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Developer-Nontarat%20Hasitapong-00E676?style=for-the-badge&logo=android" />
  <img src="https://img.shields.io/badge/Platform-BYD%20DiLink%20%7C%20Android-green?style=for-the-badge&logo=android" />
  <img src="https://img.shields.io/badge/Google%20Maps-Supported-blue?style=for-the-badge&logo=googlemaps" />
  <img src="https://img.shields.io/badge/Hardware-Direct%20CAN%20%2F%20OBD-orange?style=for-the-badge" />
  <img src="https://img.shields.io/badge/License-MIT-purple?style=for-the-badge" />
</p>

---

### 👨‍💻 Developer & Author
- **Developer:** Nontarat Hasitapong (นาย นนทรัฐ หะสิตะพงษ์)
- **Contact / Support Email:** [canon.non.7@gmail.com](mailto:canon.non.7@gmail.com)

---

## 🌟 Key Features

- **🏁🔋 Destination Battery Prediction (Destination SoC %)**:
  - Calculated using actual remaining battery energy (kWh) from the BMS + real-time rolling average consumption of the last 50 km (Past 50 km) + remaining distance from Google Maps turn-by-turn navigation.
  - Displays as a **safe prediction range (with a built-in 2% safety buffer)**, e.g. `88-90%` or `48-50%`, accounting for traffic jams, heavy AC usage, and uphill driving conditions.
  - Automatically recalculates in real-time on route changes, traffic reroutes, or missed turns.
- **🚗 Real-World Remaining Range**:
  - Displays accurate kilometers based on actual recent driving style and consumption, avoiding misleading dashboard NEDC figures.
- **⚠️ Smart Tri-Color Alert System**:
  - 🟢 **Green**: Battery is well sufficient for the planned route.
  - 🟡 **Yellow (`⚠️🪫`)**: Warning when current or destination battery is expected to drop below 20%.
  - 🔴 **Red (`⚠️🔌`)**: Critical alert when battery is insufficient to reach the destination (<2% at arrival), urging the driver to plan a charging stop.
- **📡 Direct OBD / BMS Integration**:
  - Communicates directly with the BYD vehicle subsystem without needing third-party OBD2 Bluetooth/Wi-Fi adapters.
  - Supports all BYD models: **Dolphin** (Standard/Extended), **Atto 3**, **Seal** (Dynamic/Premium/Performance), **M6**, **Sealion 6 DM-i**, **Sealion 7**, and more.
  - Includes presets for various battery capacities as well as custom battery size (kWh) and Battery Health (SOH %) override.
- **🗺️ Universal Google Maps Compatibility**:
  - Compatible with Google Play Store, Aurora Store, Morphe, Vanced/ReVanced, MicroG, GBox, Gspace, and GPad.
  - Works on standard BYD DiLink infotainment screens and custom launchers like **DUDUOS Launcher (including Picture-in-Picture / PIP mode)**.
- **🖐️ Fully Customizable Floating Pill (Movable & Pinch-to-Scale)**:
  - Clean, minimal floating pill that doesn't obstruct navigation.
  - Drag and drop anywhere on the screen (even over the top system status bar).
  - Use 2-finger pinch gesture to resize smoothly.

---

## 📊 Floating Pill Symbol Guide

> `⠿` | `🏁🔋 88-90%` | `🚗 410 km` | `📍 25.4 km` | `⚡ 10.2` | `⚠️🔌`

| Symbol | Description |
| :---: | :--- |
| **`⠿`** | Drag handle: touch and drag to reposition the overlay anywhere on screen |
| **`🏁🔋`** | Expected battery level at destination with a 2% safety margin |
| **`🚗`** | Real-world remaining driving range based on BMS data and recent 50km consumption |
| **`📍`** | Remaining distance to destination provided live by Google Maps |
| **`⚡`** | Vehicle's average energy consumption for the last 50 km (kWh/100km) |
| **`⚠️🪫`** | Yellow Warning: Battery is below 20% |
| **`⚠️🔌`** | Red Warning: Insufficient power to reach destination. Charging stop needed! |

---

## 📥 Installation Guide for BYD Vehicles

1. Download the prebuilt **[BYD-Maps-Battery-Overlay-Universal.apk (Direct Download)](https://github.com/NongJuum/BYD-Maps-Battery-Overlay/releases/download/v1.0.0/BYD-Maps-Battery-Overlay-Universal.apk)** from [GitHub Releases](https://github.com/NongJuum/BYD-Maps-Battery-Overlay/releases/latest).
2. Copy the `.apk` file to a USB flash drive (FAT32 or NTFS formatted).
3. Plug the USB flash drive into the BYD infotainment USB port in your car.
4. Open the built-in **File Manager** on the BYD screen and tap the APK to install.
5. Launch **BYD Maps Battery**:
   - The app automatically grants and requests system overlay permission and Google Maps notification listener access.
   - Choose your vehicle model, or leave default as **"📡 Auto: Direct OBD / BMS Sync (Recommended)"**.
6. Open Google Maps and start navigation — the battery overlay pill will appear automatically!

> **Tip:** You can test or position the overlay anytime by tapping **"👁️ Test / Show Overlay"** in the app settings screen.

---

## ☕ Support & Donations

This project was developed and open-sourced to give all BYD EV drivers around the world a safer, more reliable road trip experience. If this app helps your journeys, feel free to support future development with a cup of coffee! ❤️

### 🌐 International Supporters (Skrill)
- **Skrill Account:** `canon.non.7@gmail.com

---

## 📄 License & Attribution

- **Developer:** Nontarat Hasitapong
- **License:** MIT License. Free for personal EV community use.
