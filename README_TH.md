# ⚡ BYD Smart EV Route & Battery Overlay

<p align="center">
  <img src="assets/hero_banner.jpg" alt="BYD Smart EV Route &amp; Battery Overlay Hero Banner" width="100%" style="border-radius: 14px; box-shadow: 0 10px 30px rgba(0,0,0,0.5);" />
</p>

<p align="center">
  <a href="https://github.com/NongJuum/BYD-Maps-Battery-Overlay/tree/beta">
    <img src="https://img.shields.io/badge/เวอร์ชันล่าสุด-v1.2.0--beta-0284C7?style=for-the-badge&logo=android&logoColor=white" />
  </a>
  <a href="https://raw.githubusercontent.com/NongJuum/BYD-Maps-Battery-Overlay/beta/BYD-Maps-Battery-Overlay-Beta.apk">
    <img src="https://img.shields.io/badge/ดาวน์โหลด-ไฟล์%20APK%20ตรง-00E676?style=for-the-badge&logo=googleplay&logoColor=black" />
  </a>
  <a href="https://github.com/NongJuum/BYD-Maps-Battery-Overlay/tree/beta">
    <img src="https://img.shields.io/badge/OTA-อัปเดตผ่านจอรถได้ทันที-FFD600?style=for-the-badge&logo=git&logoColor=black" />
  </a>
</p>

<p align="center">
  <a href="README.md"><b>English</b></a> | <a href="README_TH.md"><b>🇹🇭 ภาษาไทย</b></a>
</p>

<p align="center">
  <b>แอปพลิเคชัน HUD แถบลอยอัจฉริยะ (Floating Overlay) ออกแบบพิเศษสำหรับรถยนต์ไฟฟ้า BYD (DiLink 3 / 4 / 5) โดยเฉพาะ</b><br>
  คำนวณ <b>% แบตเตอรี่คงเหลือเมื่อถึงจุดหมายปลายทาง (SoC %)</b> และ <b>ระยะทางวิ่งได้จริง (Real Range)</b> แสดงควบคู่กับ Google Maps แบบ Real-time ดึงข้อมูลตรงจากกล่อง CAN-Bus และ BMS ของรถโดยไม่ต้องใช้อุปกรณ์ OBD ภายนอกเสริม!
</p>

---

## ✨ มีอะไรใหม่ในเวอร์ชัน v1.2.0-beta (อัปเกรดใหญ่ล่าสุด)

<table>
  <tr>
    <td width="50%">
      <h3>🔄 จำตำแหน่งแยกแนวนอน/แนวตั้ง</h3>
      ตรวจจับการหมุนจอ DiLink อัตโนมัติ (Portrait ↔ Landscape) บันทึกพิกัดแยกกัน และดีดกลับเข้าตำแหน่งที่คนขับตั้งไว้อย่างแม่นยำ ไม่หลุดขอบจอ
    </td>
    <td width="50%">
      <h3>📌 วางทับ Status Bar ด้านบนสุด</h3>
      ปลดล็อกขอบเขตบนสุด ให้สามารถลากแคปซูลขึ้นไปวางบนแถบ Status Bar ด้านบนได้เนียนตา ช่วยประหยัดพื้นที่แสดงผลแผนที่ได้สูงสุด
    </td>
  </tr>
  <tr>
    <td width="50%">
      <h3>🌙 Night Vision หรี่แสงอัตโนมัติ</h3>
      ซิงก์กับไฟหน้ารถและโหมดกลางคืนของระบบ DiLink หรี่แสงลงเหลือ 72% แบบ Low-Glow ทันทีเมื่อเข้าอุโมงค์หรือขับเวลากลางคืน ไม่แยงตาคนขับ
    </td>
    <td width="50%">
      <h3>🔌 คำนวณจำนวนครั้งที่ควรแวะชาร์จ</h3>
      วิเคราะห์ระยะทาง Maps และความจุแบตเตอรี่ พร้อมบอกจำนวนครั้งที่ควรแวะชาร์จด่วน DC Fast Charge (ช่วง 10% ➔ 80%) สำหรับทริปทางไกล
    </td>
  </tr>
  <tr>
    <td width="50%">
      <h3>🎯 ตัวเลขเดี่ยวอ่านง่าย (~XX%)</h3>
      แสดงตัวเลขแบตปลายทางแบบค่าเดี่ยวชัดเจน พร้อม Deadband Window ป้องกันตัวเลขกระตุกหรือวูบวาบเวลาเหยียบคันเร่งแซง
    </td>
    <td width="50%">
      <h3>📲 อัปเดตผ่านจอรถได้ทันที (OTA)</h3>
      กดอัปเดตเวอร์ชันใหม่ได้ในคลิกเดียวจากในแอปบนหน้าจอรถ ไม่ต้องใช้คอมพิวเตอร์ ไม่ต้องรัน ADB หรือเสียบแฟลชไดรฟ์ใหม่อีกต่อไป
    </td>
  </tr>
</table>

---

## 🎨 ระบบสีแบตเตอรี่แบบไล่เฉด 6 ระดับ (Dynamic 6-Tier Gradient)

สีของตัวเลขแบตเตอรี่ปลายทางจะเปลี่ยนไปตามระดับความปลอดภัยของการขับขี่ EV:

| ช่วง % แบตเตอรี่ | รหัสสี (Hex) | สัญลักษณ์สี | ความหมายเชิงการขับขี่จริง |
| :---: | :---: | :---: | :--- |
| **0% – 10%** | `#FF1744` | 🔴 **แดงสด (Crimson Red)** | วิกฤต! เสี่ยงเข้าสู่ Turtle Mode หรือรถดับกลางทาง |
| **10% – 30%** | `#FF6D00` | 🟠 **ส้มเข้ม (Deep Amber)** | แบตเตอรี่ต่ำ ต้องเตรียมวางแผนแวะชาร์จ |
| **30% – 50%** | `#FFD600` | 🟡 **เหลืองสด (Electric Yellow)** | ระดับปานกลาง วิ่งได้สบายแต่ต้องคอยสังเกต |
| **50% – 75%** | `#AEEA00` | 🟢 **เขียวตองอ่อน (Lime Green)** | แบตเตอรี่พอเหมาะ ปลอดภัยสำหรับการเดินทาง |
| **75% – 95%** | `#00E676` | 🟢 **เขียวมรกต (Vibrant Emerald)** | แบตเตอรี่เหลือเฟือ ขับขี่ได้อย่างไร้กังวล |
| **95% – 100%** | `#00E5FF` | 🔵 **ฟ้าสว่าง (Electric Cyan)** | แบตเตอรี่เต็มเปี่ยม (เพิ่งชาร์จเต็มหรือวิ่งระยะใกล้) |

---

## 🏎️ รุ่นรถยนต์ BYD ที่รองรับ

<p align="center">
  <img src="https://img.shields.io/badge/BYD-Dolphin%20Standard%20%2F%20Extended-blue?style=flat-square" />
  <img src="https://img.shields.io/badge/BYD-Atto%203%20Standard%20%2F%20Extended-blue?style=flat-square" />
  <img src="https://img.shields.io/badge/BYD-Seal%20Dynamic%20%2F%20Premium%20%2F%20Performance-blue?style=flat-square" />
  <img src="https://img.shields.io/badge/BYD-Sealion%206%20%2F%20DM--i-blue?style=flat-square" />
  <img src="https://img.shields.io/badge/BYD-M6%20%2F%20Tang%20%2F%20Han-blue?style=flat-square" />
  <img src="https://img.shields.io/badge/Custom-ตั้งค่าขนาดแบตเองได้-green?style=flat-square" />
</p>

รองรับระบบหน้าจอ **DiLink 3.0 (Android 10)**, **DiLink 4.0**, และ **DiLink 5.0** ครบทุกรุ่น และสามารถปรับเปลี่ยนขนาดความจุแบตเตอรี่ (kWh) รวมถึงสุขภาพแบต (SOH %) ได้อย่างอิสระในเมนูตั้งค่า

---

## 📦 ขั้นตอนการติดตั้งง่ายๆ

### ขั้นตอนที่ 1: ติดตั้งแอปบนหน้าจอรถ
1. ดาวน์โหลดไฟล์ **[BYD-Maps-Battery-Overlay-Beta.apk](https://raw.githubusercontent.com/NongJuum/BYD-Maps-Battery-Overlay/beta/BYD-Maps-Battery-Overlay-Beta.apk)**
2. ก๊อปปี้ใส่ USB Flash Drive เสียบเข้าพอร์ต USB หน้ารถ และกดติดตั้งผ่านแอป **ตัวจัดการไฟล์ (File Manager)** ของรถ
3. เปิดแอปและกดเปิดสิทธิ์ **แสดงทับแอปอื่น (Overlay)** และ **การแจ้งเตือน (Notification)**

### ขั้นตอนที่ 2: ให้สิทธิ์เข้าถึง CAN-Bus รถ (ครั้งแรกครั้งเดียวผ่าน ADB ไร้สาย)
เชื่อมต่อคอมพิวเตอร์เข้ากับ Wi-Fi เดียวกับรถยนต์ และพิมพ์คำสั่งใน Terminal:
```bash
adb connect <ไอพีหน้าจอรถ>:5555
adb shell "appops set com.byd.mapsoverlay.beta SYSTEM_ALERT_WINDOW allow"
adb shell "cmd notification allow_listener com.byd.mapsoverlay.beta/com.byd.mapsoverlay.MapsNotificationListener"
```
*(หรือกดปุ่มตัวช่วยอัตโนมัติ AutoPermission ในหน้าแอปได้ทันที!)*

### ขั้นตอนที่ 3: พร้อมใช้งานและอัปเดตแบบไร้สาย!
ทุกครั้งที่เปิด Google Maps นำทาง ตัว Overlay จะลอยขึ้นมาอัตโนมัติ และเมื่อมีฟีเจอร์เวอร์ชันใหม่ เพียงกดปุ่ม **"ตรวจสอบอัปเดตออนไลน์ (OTA)"** ในแอป หน้าจอรถจะโหลดและติดตั้งเองทันที!

---

## ☕ ร่วมสนับสนุนผู้พัฒนา (Community Spirit)

> **💡 ฟรีและโอเพ่นซอร์ส 100%:**  
> ทุกท่านสามารถนำโปรเจกต์นี้ไปใช้งาน ปรับแต่ง หรือศึกษาได้อย่างอิสระครับ  
> หากแอปนี้ช่วยให้คุณขับขี่รถยนต์ไฟฟ้า BYD ได้อย่างมั่นใจและสบายใจยิ่งขึ้น การเลี้ยงกาแฟหรือขนมเล็กๆ น้อยๆ แก่ผู้พัฒนา ถือเป็นกำลังใจที่อบอุ่นและมีคุณค่าอย่างยิ่งครับ! 🙏🍬☕❤️

<p align="center">
  <a href="https://www.buymeacoffee.com/nongjuum" target="_blank">
    <img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" height="52" />
  </a><br><br>
  <img src="assets/qr_buymeacoffee.png" width="180" alt="Buy Me A Coffee QR Code" style="border-radius: 8px; box-shadow: 0 4px 15px rgba(0,0,0,0.3);" /><br>
  <b><a href="https://www.buymeacoffee.com/nongjuum">buymeacoffee.com/nongjuum</a></b><br>
  <i>(รองรับบัตรเดบิต/เครดิต, Apple Pay, Google Pay)</i><br>
  <b>Skrill:</b> <code>canon.non.7@gmail.com</code>
</p>

---

## 👨‍💻 ผู้พัฒนาและสัญญาอนุญาต
- **ผู้พัฒนาหลัก:** นนทราษฎร์ หะสิตะพงษ์ ([canon.non.7@gmail.com](mailto:canon.non.7@gmail.com))
- **สัญญาอนุญาต:** MIT License — เพื่อสังคมผู้ใช้รถ EV ทุกคน
