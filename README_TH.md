# ⚡ BYD Smart EV Route & Battery Overlay

<p align="center">
  <a href="https://github.com/NongJuum/BYD-Maps-Battery-Overlay/releases/latest">
    <img src="https://img.shields.io/badge/ดาวน์โหลด-APK%20เวอร์ชันล่าสุด%20(v1.0.0)-00C853?style=for-the-badge&logo=android&logoColor=white" />
  </a>
</p>

<p align="center">
  <a href="README.md"><b>English</b></a> | <a href="README_TH.md"><b>🇹🇭 ภาษาไทย</b></a>
</p>

<p align="center">
  <b>แอปพลิเคชันแถบลอยอัจฉริยะ (Floating Overlay) แสดง % แบตเตอรี่ปลายทาง และระยะวิ่งจริงแบบ Real-Time สำหรับรถยนต์ไฟฟ้า BYD ทุกรุ่น</b><br>
  ทำงานร่วมกับ Google Maps และซิงค์ข้อมูลสดจากกล่องควบคุมรถ (OBD / BMS) โดยไม่ต้องซื้ออุปกรณ์ฮาร์ดแวร์เสริม
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Developer-Nontarat%20Hasitapong-00E676?style=for-the-badge&logo=android" />
  <img src="https://img.shields.io/badge/Platform-BYD%20DiLink%20%7C%20Android-green?style=for-the-badge&logo=android" />
  <img src="https://img.shields.io/badge/Google%20Maps-Supported-blue?style=for-the-badge&logo=googlemaps" />
  <a href="https://www.buymeacoffee.com/nongjuum"><img src="https://img.shields.io/badge/Buy%20Me%20A%20Coffee-nongjuum-FFDD00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black" /></a>
  <img src="https://img.shields.io/badge/License-MIT-purple?style=for-the-badge" />
</p>

<p align="center">
  <img src="assets/hero_banner.jpg" alt="BYD Smart EV Route &amp; Battery Overlay Hero Banner" width="100%" style="border-radius: 12px;" />
</p>

---

### 👨‍💻 ข้อมูลผู้พัฒนา (Author & Developer)
- **ผู้พัฒนา (Developer):** Nontarat Hasitapong
- **อีเมลติดต่อ / Support:** [canon.non.7@gmail.com](mailto:canon.non.7@gmail.com)

---

## 🌟 ฟีเจอร์เด่น (Key Features)

- **🏁🔋 คำนวณ % แบตเตอรี่ปลายทางล่วงหน้า (Destination SoC %)**:
  - คำนวณจากพลังงานคงเหลือจริงในแบตเตอรี่ (kWh) จาก BMS + อัตรากินไฟเฉลี่ย 50 กม. ล่าสุด (Past 50 km) + ระยะทางรวมถึงจุดหมายจาก Google Maps
  - แสดงผลเป็น **ช่วงคาดการณ์ปลอดภัย (เผื่อ Safety Buffer 2%)** เช่น `88-90%` หรือ `48-50%` เพื่อรองรับกรณีรถติด แอร์สู้แดด หรือการขับขึ้นทางชัน
  - คำนวณใหม่ทันทีแบบเรียลไทม์เมื่อมีการเปลี่ยนเส้นทาง (Reroute) หรือเลี้ยวผิด
- **🚗 ระยะทางวิ่งได้จริงคงเหลือ (Real Remaining Range)**:
  - บอกระยะกิโลเมตรที่รถวิ่งได้จริงตามสภาพการขับขี่ปัจจุบัน (ไม่ใช่ตัวเลข NEDC หลอกตาบนหน้าปัด)
- **⚠️ ระบบแจ้งเตือนอัจฉริยะ (Smart Tri-Color Alert)**:
  - 🟢 **เขียว**: แบตเตอรี่เพียงพอต่อการเดินทาง
  - 🟡 **เหลือง (`⚠️🪫`)**: เตือนล่วงหน้าเมื่อแบตเตอรี่ปัจจุบันหรือที่ปลายทางจะเหลือต่ำกว่า 20%
  - 🔴 **แดง (`⚠️🔌`)**: เตือนทันทีเมื่อแบตเตอรี่ไม่พอถึงปลายทาง (หรือเหลือน้อยกว่า 2%) แนะนำให้หาจุดชาร์จระหว่างทาง
- **📡 เชื่อมต่อ OBD / BMS ของรถโดยตรง**:
  - ดึงข้อมูลจากระบบรถยนต์ BYD โดยตรง ไม่ต้องเสียบดองเกิล OBD2 เพิ่มเติม
  - รองรับรถ BYD ทุกรุ่น (Dolphin Standard/Extended, Atto 3, Seal, M6, Sealion 6 DM-i, Sealion 7 ฯลฯ)
  - มีเมนูเลือกรุ่นรถ หรือระบุขนาดแบตเตอรี่ (kWh) และค่าสุขภาพแบตเตอรี่ (SOH) ได้เอง
- **🗺️ รองรับ Google Maps ทุกเวอร์ชัน**:
  - Google Play Store, Aurora Store, Morphe, Vanced / ReVanced, MicroG, GBox, Gspace, GPad
  - รองรับทั้งหน้าจอเดิมติดรถ (DiLink) และหน้าจอแต่ง **Dudu Launcher (รวมถึงโหมด PIP ย่อหน้าต่าง)**
- **🖐️ หน้าต่างลอยปรับแต่งอิสระ (Movable & Pinch-to-Scale)**:
  - สัญลักษณ์มินิมอล ไม่บดบังแผนที่
  - แตะลากวางตำแหน่งใดก็ได้บนหน้าจอ (รวมถึงวางทับแถบ Status Bar ด้านบน)
  - ใช้ 2 นิ้วถ่างเข้า-ออกเพื่อย่อ/ขยายขนาดแถบลอยได้อิสระ

---

## 📊 ความหมายของสัญลักษณ์บนแถบลอย (Pill Overlay)

> `⠿` | `🏁🔋 88-90%` | `🚗 410 km` | `📍 25.4 km` | `⚡ 10.2` | `⚠️🔌`

| สัญลักษณ์ | ความหมาย |
| :---: | :--- |
| **`⠿`** | จุดจับสำหรับแตะลากย้ายตำแหน่งแถบลอย |
| **`🏁🔋`** | เปอร์เซ็นต์แบตเตอรี่ที่จะเหลือเมื่อถึงจุดหมายปลายทาง (พร้อม Safety Buffer 2%) |
| **`🚗`** | ระยะทางที่รถสามารถวิ่งได้จริงคงเหลือในขณะนี้ (คำนวณจาก BMS + Past 50km) |
| **`📍`** | ระยะทางรวมจากตำแหน่งปัจจุบันไปยังจุดหมายปลายทาง (จาก Google Maps) |
| **`⚡`** | อัตรากินไฟเฉลี่ย 50 กม. ล่าสุดของตัวรถ (kWh/100km) ดึงสดจากหน้าปัด |
| **`⚠️🪫`** | ป้ายเตือนสีเหลือง: แบตเตอรี่ต่ำกว่า 20% |
| **`⚠️🔌`** | ป้ายเตือนสีแดง: พลังงานไม่เพียงพอถึงจุดหมาย กรุณาวางแผนแวะชาร์จระหว่างทาง |

---

## 📦 ความต้องการของระบบและแอปที่เกี่ยวข้อง (Prerequisites & Dependencies)

- **🚗 ยานพาหนะที่รองรับ:** รถยนต์ไฟฟ้า BYD ทุกรุ่นที่ใช้หน้าจอระบบ Android DiLink (DiLink 3.0, 4.0, 5.0) เช่น Dolphin, Atto 3, Seal, M6, Sealion 6 DM-i, Sealion 7 ฯลฯ
- **🔌 อุปกรณ์ฮาร์ดแวร์เสริม:** **ไม่ต้องใช้!** (ไม่ต้องเสียบกล่อง OBD2 หรือหัวแปลง Bluetooth/Wi-Fi Dongle เสริมใดๆ ทั้งสิ้น ดึงข้อมูลสดจากระบบรถโดยตรง)
- **📱 แอปอื่นๆ เพิ่มเติม:** **ไม่จำเป็นต้องพึ่งพาแอปเสริมใดๆ! (ทำงานอิสระ 100%)**
  - ไม่จำเป็นต้องติดตั้ง OverDrive, DUDU Launcher, Tasker หรือ Shizuku ก็ใช้งานได้สมบูรณ์
  - รองรับทั้งหน้าจอเดิมติดรถ (DiLink Default Launcher) และหน้าจอแต่ง (เช่น DUDU Launcher)
- **🗺️ แอปนำทาง (Navigation):** ติดตั้ง **Google Maps** บนจอรถ (รองรับทุกเวอร์ชัน: Google Play Store, Aurora Store, MicroG, Vanced, GBox, Gspace) เพื่อให้ตัวระบบสามารถอ่านระยะทางคงเหลือของเส้นทางมาคำนวณ % แบตเตอรี่ปลายทางได้อย่างแม่นยำ

---

## 📥 วิธีติดตั้งและเปิดใช้งาน (Installation & Activation Guide)

### ขั้นตอนที่ 1: ติดตั้งแอป
1. ดาวน์โหลดไฟล์ **[BYD-Maps-Battery-Overlay-Universal.apk (ลิงก์ตรง)](https://github.com/NongJuum/BYD-Maps-Battery-Overlay/releases/download/v1.0.0/BYD-Maps-Battery-Overlay-Universal.apk)** หรือเข้าผ่านหน้า [Releases ล่าสุด](https://github.com/NongJuum/BYD-Maps-Battery-Overlay/releases/latest)
2. ก๊อปปี้ไฟล์ `.apk` ใส่แฟลชไดรฟ์ USB นำไปเสียบที่พอร์ตคอนโซลรถ แล้วเปิดติดตั้งผ่านแอป **จัดการไฟล์ (File Manager)**
3. เปิดแอป **BYD Maps Battery** แล้วกดเปิดสิทธิ์ Overlay และ Notification Listener

---

### ขั้นตอนที่ 2: ปลดล็อกการอ่านข้อมูลรถ (One-Time Activation via ADB)
> ⚠️ **ทำไมต้องทำขั้นตอนนี้?**  
> บนระบบของ BYD (DiLink 3.0 / 4.0 / 5.0) สิทธิ์การอ่านข้อมูลแบตเตอรี่และ CAN-Bus (`BYDAUTO_STATISTIC_GET`) ถูกล็อกไว้เป็น **Signature Permission** ของโรงงาน BYD (เช่นเดียวกับแอปชื่อดังอย่าง **OverDrive** และ **DUDU Launcher**) จึงต้องรัน Service ผ่าน ADB Shell (UID 2000) เพื่อเชื่อมต่อข้อมูล:

1. ให้คอมพิวเตอร์และหน้าจอรถเชื่อมต่อ Wi-Fi วงเดียวกัน (หรือแชร์ Wi-Fi Hotspot)
2. ดู IP Address ของจอรถในการตั้งค่า Wi-Fi ของรถ (เช่น `10.51.77.234` หรือ `192.168.1.xxx`)
3. เปิด Terminal หรือ PowerShell ในคอมพิวเตอร์ แล้วรันคำสั่ง:
   ```bash
   adb connect <IP_ของจอรถ>:5555
   adb shell "CLASSPATH=\$(pm path com.byd.mapsoverlay | cut -d: -f2) app_process / com.byd.mapsoverlay.BydTelemetryDaemon </dev/null >/dev/null 2>&1 &"
   ```
4. ที่หน้าจอรถ ข้อมูล % แบตเตอรี่ (SoC), ระยะทางวิ่งจริง และอัตรากินไฟเฉลี่ยจะเริ่มซิงค์สดทันที!

> **💡 หมายเหตุ:**
> - แอปใช้พอร์ต **19890** จึงสามารถเปิดทำงานพร้อมกันกับ **OverDrive** (พอร์ต 19878) ได้อย่างราบรื่นโดยไม่ชนกัน
> - เมื่อเปิด Google Maps ระบบจะคำนวณและแสดงแถบลอยบนหน้าจออัตโนมัติ

---

### ขั้นตอนที่ 3: ตั้งค่าให้แอปเริ่มทำงานอัตโนมัติและไม่ถูกระบบปิด (Auto Start & Background Keep-Alive)
เพื่อให้แอปทำงานได้อย่างต่อเนื่องเมื่อสตาร์ตรถ และไม่ถูกระบบประหยัดพลังงานของ BYD DiLink ปิดการทำงานในพื้นหลัง:

#### 1. อนุญาตผ่านคำสั่ง ADB (รวดเร็วที่สุดในคำสั่งเดียว)
รันคำสั่งต่อไปนี้ใน Terminal / PowerShell:
```bash
adb shell "dumpsys deviceidle whitelist +com.byd.mapsoverlay"
adb shell "appops set com.byd.mapsoverlay RUN_IN_BACKGROUND allow"
adb shell "appops set com.byd.mapsoverlay RUN_ANY_IN_BACKGROUND allow"
adb shell "appops set com.byd.mapsoverlay START_FOREGROUND allow"
```

#### 2. เปิดสิทธิ์เริ่มทำงานอัตโนมัติในหน้าจอรถ (BYD App Start Management)
1. เปิดเมนู **การตั้งค่ารถ (Vehicle Settings)** -> **การจัดการแอปพลิเคชัน (App Management)** -> **การจัดการเริ่มทำงานอัตโนมัติ (App Start Management)**
2. เลื่อนหาแอป **BYD Maps Battery** แล้วแตะเปิดสวิตช์อนุญาต (Allow Auto Start)
   *(หรือสามารถเปิดหน้านี้ผ่าน ADB ได้ทันทีด้วยคำสั่ง: `adb shell "am start -n com.byd.appstartmanagement/.frame.AppStartManagement"`)*

---

> **หมายเหตุ:** หากต้องการลากแถบลอยไปวางตำแหน่งที่ต้องการหรือทดสอบขนาด สามารถกดปุ่ม **"👁️ แสดง/ทดสอบแถบลอย"** ในหน้าแอปได้ทันที

---

## ☕ ร่วมสนับสนุนผู้พัฒนา (Support & Donate)

> **💡 นโยบายการนำไปใช้งานต่อ (Open Source & Community Spirit):**  
> ใครจะนำโปรเจกต์นี้ โค้ด หรือตัวแอปไปใช้งาน ดัดแปลง แก้ไข หรือนำไปพัฒนาปรับปรุงต่อ **สามารถนำไปใช้ได้อย่างอิสระเต็มที่เลยครับ!**  
> แต่หากมีความเมตตา และเห็นว่าสิ่งนี้มีประโยชน์ อยากแบ่งปัน **ค่าขนม / ค่าลูกอม / ค่าน้ำชา** ให้ผมสักนิดเพื่อเป็นกำลังใจเล็กๆ น้อยๆ ในการสร้างสรรค์ผลงานต่อไป ก็ยินดีและขอบพระคุณจากใจจริงครับ 🙏🍬❤️

<p align="center">
  <a href="https://www.buymeacoffee.com/nongjuum" target="_blank">
    <img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" height="50" />
  </a><br><br>
  <img src="assets/qr_buymeacoffee.png" width="180" alt="Buy Me A Coffee QR Code" /><br>
  <b><a href="https://www.buymeacoffee.com/nongjuum">buymeacoffee.com/nongjuum</a></b><br>
  <i>(รองรับทั้งบัตรเครดิต/เดบิต, Apple Pay, Google Pay ทั่วโลก ปลอดภัย 100%)</i>
</p>

### 🌐 ช่องทางอื่นๆ:
- **Skrill (ต่างประเทศ / International):** `canon.non.7@gmail.com`
---

## 📄 License & Attribution

Copyright (c) 2026 Nontarat Hasitapong.  
Distributed under the MIT License.


<!-- Rebuild trigger: verify legacy APK signature compatibility for Android 10 IVI installers. -->
