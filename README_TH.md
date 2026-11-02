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
  <img src="https://img.shields.io/badge/Hardware-Direct%20CAN%20%2F%20OBD-orange?style=for-the-badge" />
  <img src="https://img.shields.io/badge/License-MIT-purple?style=for-the-badge" />
</p>

---

### 👨‍💻 ข้อมูลผู้พัฒนา (Author & Developer)
- **ผู้พัฒนา (Developer):** นาย นนทรัฐ หะสิตะพงษ์ (Nontarat Hasitapong)
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

## 📥 วิธีติดตั้งลงบนหน้าจอรถ BYD (Installation Guide)

1. ดาวน์โหลดไฟล์ **[BYD-Maps-Battery-Overlay-Universal.apk (ลิงก์ตรง)](https://github.com/NongJuum/BYD-Maps-Battery-Overlay/releases/download/v1.0.0/BYD-Maps-Battery-Overlay-Universal.apk)** หรือเข้าผ่านหน้า [Releases ล่าสุด](https://github.com/NongJuum/BYD-Maps-Battery-Overlay/releases/latest)
2. ก๊อปปี้ไฟล์ `.apk` ใส่แฟลชไดรฟ์ USB (ฟอร์แมต FAT32 หรือ NTFS)
3. นำแฟลชไดรฟ์ไปเสียบที่พอร์ต USB ของตัวรถ BYD
4. เปิดแอป **จัดการไฟล์ (File Manager)** บนหน้าจอรถ แล้วกดติดตั้ง APK
5. เปิดแอป **BYD Maps Battery** ขึ้นมา:
   - ระบบจะดำเนินการขอและเปิดสิทธิ์การวางทับหน้าจอ (Overlay) และสิทธิ์อ่านแจ้งเตือน Google Maps อัตโนมัติ
   - เลือกรุ่นรถของคุณ หรือปล่อยเป็นค่าเริ่มต้น **"📡 อัตโนมัติ: ดึงขนาดแบตและรุ่นรถจาก OBD / BMS โดยตรง"**
6. เปิด Google Maps แล้วกดเริ่มนำทาง แถบลอยจะปรากฏขึ้นมาบนหน้าจอทันที!

> **หมายเหตุ:** หากต้องการลากแถบลอยไปวางตำแหน่งที่ต้องการหรือทดสอบขนาด สามารถกดปุ่ม **"👁️ แสดง/ทดสอบแถบลอย"** ในหน้าแอปได้ทันที

---

## ☕ ร่วมสนับสนุนผู้พัฒนา (Support & Donate)

โปรเจกต์นี้พัฒนาขึ้นเพื่อแบ่งปันให้เพื่อนๆ ผู้ขับขี่รถยนต์ไฟฟ้า BYD ทุกท่านได้ใช้งานเดินทางท่องเที่ยวได้อย่างมั่นใจ หากแอปพลิเคชันนี้มีประโยชน์และช่วยให้คุณวางแผนการเดินทางได้สะดวกขึ้น สามารถร่วมสนับสนุนค่าน้ำชา / กาแฟ เพื่อเป็นกำลังใจในการพัฒนาต่อไปได้ครับ ❤️

### 🌐 ช่องทางสำหรับผู้สนับสนุนต่างประเทศ (International Donation):
- **Skrill Email:** `canon.non.7@gmail.com`
---

## 📄 License & Attribution

Copyright (c) 2026 Nontarat Hasitapong.  
Distributed under the MIT License.
