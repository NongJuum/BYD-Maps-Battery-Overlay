# ⚡ BYD Smart EV Route & Battery Overlay (Reserve Channel)

<p align="center">
  <img src="assets/hero_banner.jpg" alt="BYD Smart EV Route &amp; Battery Overlay Hero Banner" width="100%" style="border-radius: 14px; box-shadow: 0 10px 30px rgba(0,0,0,0.5);" />
</p>

<p align="center">
  <a href="https://github.com/NongJuum/BYD-Maps-Battery-Overlay/tree/reserve">
    <img src="https://img.shields.io/badge/Release-v1.3.0--reserve%20(PC%20ADB)-EA580C?style=for-the-badge&logo=android&logoColor=white" />
  </a>
  <a href="https://raw.githubusercontent.com/NongJuum/BYD-Maps-Battery-Overlay/beta/BYD-Maps-Battery-Overlay-Beta.apk">
    <img src="https://img.shields.io/badge/Download-APK%20Direct-00E676?style=for-the-badge&logo=googleplay&logoColor=black" />
  </a>
  <a href="https://github.com/NongJuum/BYD-Maps-Battery-Overlay/tree/main">
    <img src="https://img.shields.io/badge/Channel-Main%20(One--Tap)-0284C7?style=for-the-badge&logo=git&logoColor=white" />
  </a>
</p>

<p align="center">
  <a href="README.md"><b>English</b></a> | <a href="README_TH.md"><b>🇹🇭 ภาษาไทย</b></a>
</p>

<p align="center">
  <b>An ultra-smart, floating telemetry HUD designed specifically for BYD electric vehicles (DiLink 3 / 4 / 5).</b><br>
  Accurately predicts <b>Destination Battery % (SoC)</b> and <b>Real-world Remaining Range</b> alongside Google Maps in real-time — directly tapping into BYD CAN-Bus & BMS without requiring extra OBD dongles!
</p>

> [!NOTE]
> **📌 About the Reserve Edition:**  
> This channel contains **identical codebase, features, and UI enhancements as the new Beta / Main releases** (including all 3 layout themes, night auto-dimming, status bar placement, and stabilized dest SoC).  
> **The only difference:** This reserve channel documents activation via **computer (PC/Laptop terminal via ADB)** for users whose vehicle security policy restricts in-car localhost port 5555.

---

## ✨ What's New in v1.3.0-reserve (Full Modern Feature Set)

<table>
  <tr>
    <td width="50%">
      <h3>🔄 Dynamic Screen Rotation Memory</h3>
      Auto-detects DiLink screen rotation (Portrait ↔ Landscape). Memorizes separate coordinates per orientation and auto-snaps to your preferred layout.
    </td>
    <td width="50%">
      <h3>📌 Top Status Bar HUD Placement</h3>
      Unlocked safe-zone clamping allowing the overlay capsule to sit cleanly directly on top of the native status bar, maximizing map view.
    </td>
  </tr>
  <tr>
    <td width="50%">
      <h3>🌙 Night Vision Auto-Dimming</h3>
      Syncs seamlessly with vehicle headlights / Android Night Mode. Softens opacity to 72% low-glow to prevent driver glare during night drives or tunnels.
    </td>
    <td width="50%">
      <h3>🔌 Smart Charging Stops Counter</h3>
      Intelligently calculates if and how many DC Fast Charge stops (10% ➔ 80% curve) are needed to reach your distant destination.
    </td>
  </tr>
  <tr>
    <td width="50%">
      <h3>🎯 Single-Number Dest SoC (~XX%)</h3>
      Clean, instant-glance percentage with a 1.0% deadband window to eliminate jittering during hard acceleration or downhill regen.
    </td>
    <td width="50%">
      <h3>📲 In-Place In-App OTA Updates</h3>
      One-tap background updates directly from your car screen. No PC, ADB, or USB re-plugging needed for future releases!
    </td>
  </tr>
</table>

---

## 🎨 6-Tier Dynamic Battery Color Gradient

The destination SoC display dynamically adapts its accent color based on EV safety thresholds:

| Remaining Battery | Color Code | Visual Cue | Driving Implication |
| :---: | :---: | :---: | :--- |
| **0% – 10%** | `#FF1744` | 🔴 **Crimson Red** | Critical! Approaching Turtle Mode or imminent power cutoff |
| **10% – 30%** | `#FF6D00` | 🟠 **Deep Amber** | Low Battery — Plan your next charging stop |
| **30% – 50%** | `#FFD600` | 🟡 **Electric Yellow** | Medium Battery — Monitor consumption over hills |
| **50% – 75%** | `#AEEA00` | 🟢 **Lime Green** | Healthy Reserve — Safe cruising |
| **75% – 95%** | `#00E676` | 🟢 **Vibrant Emerald** | Plentiful Energy — Stress-free travel |
| **95% – 100%** | `#00E5FF` | 🔵 **Electric Cyan** | Full Charge / Short Trip |

---

## 🏎️ Compatible BYD Vehicle Models

<p align="center">
  <img src="https://img.shields.io/badge/BYD-Dolphin%20Standard%20%2F%20Extended-blue?style=flat-square" />
  <img src="https://img.shields.io/badge/BYD-Atto%203%20Standard%20%2F%20Extended-blue?style=flat-square" />
  <img src="https://img.shields.io/badge/BYD-Seal%20Dynamic%20%2F%20Premium%20%2F%20Performance-blue?style=flat-square" />
  <img src="https://img.shields.io/badge/BYD-Sealion%206%20%2F%20DM--i-blue?style=flat-square" />
  <img src="https://img.shields.io/badge/BYD-M6%20%2F%20Tang%20%2F%20Han-blue?style=flat-square" />
  <img src="https://img.shields.io/badge/Custom-User%20Battery%20Override-green?style=flat-square" />
</p>

Works out-of-the-box with **DiLink 3.0 (Android 10)**, **DiLink 4.0**, and **DiLink 5.0** head units. Custom model name, nominal battery capacity, and Battery State of Health (SOH %) can also be freely customized in Settings.

---

## 📦 Installation Guide (Reserve Channel: PC ADB Terminal Activation)

### Step 1: Install APK on Car Head Unit
1. Download **[BYD-Maps-Battery-Overlay-Beta.apk](https://raw.githubusercontent.com/NongJuum/BYD-Maps-Battery-Overlay/beta/BYD-Maps-Battery-Overlay-Beta.apk)**.
2. Transfer to a USB drive, plug into vehicle front USB port, and install via native **File Manager**.
3. Launch the app and grant **Overlay** and **Notification Access** permissions when prompted.

### Step 2: Grant Permissions via Computer (PC / Laptop Terminal)
> 💻 **One-Time Setup:** Connect your computer to the same Wi-Fi network as the vehicle (or connect via USB-A to USB-A cable).

1. **Find Car Head Unit IP Address**:
   - Open car `Settings` ➔ `Wi-Fi` ➔ Tap the connected Wi-Fi SSID to view IP Address (e.g. `192.168.1.150`).

2. **Open Terminal (Mac/Linux) or PowerShell (Windows) and run:**
   ```bash
   # Connect to vehicle head unit via ADB Wi-Fi
   adb connect 192.168.1.150:5555

   # Grant required CAN-Bus telemetry permissions
   adb shell pm grant com.byd.mapsoverlay android.permission.DUMP
   adb shell appops set com.byd.mapsoverlay SYSTEM_ALERT_WINDOW allow
   adb shell cmd notification allow_listener com.byd.mapsoverlay/com.byd.mapsoverlay.MapsNotificationListener
   ```

3. If the car screen prompts *`Allow USB debugging from this computer?`*, check **☑️ Always allow** and press **[OK]**.

### Step 3: Ready to Drive!
Launch Google Maps and the smart HUD overlay will automatically appear, streaming live telemetry according to your selected layout theme!

---

## ☕ Support & Community Spirit

> **💡 100% Free & Open-Source:**  
> Anyone is welcome to use, fork, and improve this project freely.  
> If this project helps you drive your BYD with peace of mind, treating the developer to a cup of coffee or a small treat is warmly welcomed and deeply appreciated! 🙏🍬☕❤️

<p align="center">
  <a href="https://www.buymeacoffee.com/nongjuum" target="_blank">
    <img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" height="52" />
  </a><br><br>
  <img src="assets/qr_buymeacoffee.png" width="180" alt="Buy Me A Coffee QR Code" style="border-radius: 8px; box-shadow: 0 4px 15px rgba(0,0,0,0.3);" /><br>
  <b><a href="https://www.buymeacoffee.com/nongjuum">buymeacoffee.com/nongjuum</a></b><br>
  <i>(Supports Debit/Credit cards, Apple Pay, Google Pay)</i><br>
  <b>Skrill:</b> <code>canon.non.7@gmail.com</code>
</p>

---

## 👨‍💻 Author & License
- **Lead Developer:** Nontarat Hasitapong ([canon.non.7@gmail.com](mailto:canon.non.7@gmail.com))
- **License:** MIT License — Open for all EV enthusiasts.
