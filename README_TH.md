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

## 📥 วิธีติดตั้งลงบนหน้าจอรถ BYD (Installation Guide)

1. ดาวน์โหลดไฟล์ **[BYD-Maps-Battery-Overlay-Universal.apk (ลิงก์ตรง)](https://github.com/NongJuum/BYD-Maps-Battery-Overlay/releases/download/v1.0.0/BYD-Maps-Battery-Overlay-Universal.apk)** หรือเข้าผ่านหน้า [Releases ล่าสุด](https://github.com/NongJuum/BYD-Maps-Battery-Overlay/releases/latest)
2. ก๊อปปี้ไฟล์ `.apk` ใส่แฟลชไดรฟ์ USB (ฟอร์แมต FAT32 หรือ NTFS)
3. นำแฟลชไดรฟ์ไปเสียบที่พอร์ต USB ของตัวรถ BYD
4. เปิดแอป **จัดการไฟล์ (File Manager)** บนหน้าจอรถ แล้วกดติดตั้ง APK
5. ก่อนเปิดแอป ให้เปิด **Wireless ADB / ADB over TCP (พอร์ต 5555)** บนหน้าจอรถ DiLink
6. เปิดแอป **BYD Maps Battery**:
   - ครั้งแรก Android จะแสดงหน้าต่าง **Allow USB debugging? / อนุญาตการแก้ไขข้อบกพร่อง USB?** ให้กดยอมรับ และเลือก Always allow หากมีตัวเลือก
   - แอปจะใช้ ADB Shell เพื่อเริ่มตัวอ่านข้อมูล BYD SDK ใน UID 2000 แล้วส่งค่า SoC, ระยะทาง และพลังงานคงเหลือให้แถบ Overlay ผ่าน localhost
   - อนุญาตสิทธิ์ Overlay และ Notification Listener ของ Google Maps
   - เลือกรุ่นรถของคุณ หรือปล่อยเป็นค่าเริ่มต้น **"📡 อัตโนมัติ"**
7. เปิด Google Maps แล้วเริ่มนำทาง แถบลอยจะใช้ข้อมูลรถจริงเมื่อ Daemon เชื่อมต่อสำเร็จ

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
