# 🚗 BYD Vehicle Data Overlay 2.0 (Standalone HUD)

<p align="center">
  <b>หน้าต่างลอยแสดงผลข้อมูลรถยนต์ไฟฟ้า BYD (Standalone Floating HUD)</b><br>
  ทำงานแยกอิสระ 100% ไม่ขึ้นกับ Google Maps พร้อมระบบเลือกข้อมูลแสดงผลแบบโมดูลาร์ และดึงข้อมูล CAN-Bus / BMS สดจากตัวรถโดยตรง
</p>

---

## 🌟 ฟีเจอร์ใหม่ในเวอร์ชัน 2.0 (Major Release)

1. **อิสระสมบูรณ์ (Decoupled Architecture)**: ไม่ต้องเปิด Google Maps หรือพึ่งพา Notification อีกต่อไป สามารถเปิดเป็น Floating HUD ค้างไว้บนหน้าจอได้ตลอดเวลา ใช้งานร่วมกับทุก Launcher (DUDU, KINEX, KIM, หรือหน้าจอเดิมของรถ)
2. **ปรับแต่งข้อมูลแสดงผลได้อิสระ (Modular Pill Capsule)**:
   - ⚡ **ระดับแบตเตอรี่ (SoC %)**
   - 🔋 **พลังงานคงเหลือจริงจาก BMS (Remaining kWh)**
   - 🚗 **ระยะทางประเมินจริงตามการกินไฟ (Real Range km)**
   - 📉 **อัตราสิ้นเปลืองเฉลี่ย 50 km ล่าสุด (kWh/100km)**
   - ❄️ **อุณหภูมิในห้องโดยสาร (Cabin Temperature °C)** ดึงสดจาก `BYDAutoAcDevice`
   - 🛡️ **สุขภาพแบตเตอรี่ (Battery SOH %)**
   - 🔋 **แรงดันแบตเตอรี่ลูกเล็ก 12V (Aux Battery Voltage)**
   - 🕹️ **ตำแหน่งเกียร์ (P/R/N/D) & ความเร็วดิจิทัล (km/h)**
3. **ระบบอุดรูรั่ว ป้องกันแอปเด้ง 100% (Zero-Hole Guard)**:
   - ดักจับ Null Pointer และ Security Exception อัตโนมัติ หากกล่องแอร์หรือเซนเซอร์ยังไม่พร้อมส่งข้อมูล ตัวแอปจะคงสถานะการทำงานได้อย่างต่อเนื่องโดยไม่เกิดการ Force Close
4. **ลากวางได้อิสระ (Drag & Drop Memory)**:
   - มีจุดจับลาก (⠿) ย้ายตำแหน่งไปไว้มุมใดของจอก็ได้ พร้อมระบบจดจำพิกัดล่าสุดอัตโนมัติ
   - ปุ่ม **"🎯 รีเซ็ตตำแหน่ง Overlay"** ในหน้าตั้งค่า กดปุ๊บหน้าต่างจะดีดกลับมาที่มุมจอทันที

---

## 📦 วิธีการติดตั้งบนรถ BYD (Installation Guide)

### วิธีที่ 1: ติดตั้งผ่านแฟลชไดรฟ์ USB (ง่ายที่สุด ไม่ต้องใช้คอม)
1. ดาวน์โหลดไฟล์ `BYD-Maps-Battery-Overlay-Beta.apk` จากหน้า Releases หรือใน Repository นี้
2. คัดลอกไฟล์ APK ลง Flash Drive (แนะนำฟอร์แมตเป็น FAT32)
3. เสียบ Flash Drive เข้าที่พอร์ต USB ด้านหน้าของรถ BYD
4. เปิดแอป **"ตัวจัดการไฟล์" (File Manager)** บนหน้ารถ แล้วกดติดตั้งไฟล์ APK
5. เปิดแอปแล้วเปิดสวิตช์ **"เปิด Overlay"** พร้อมอนุญาตสิทธิ์การแสดงทับแอปอื่น (Display over other apps)

### วิธีที่ 2: ติดตั้งผ่าน ADB ไร้สาย (สำหรับผู้ใช้ขั้นสูง)
```bash
# เชื่อมต่อกับรถผ่าน Wi-Fi
adb connect <IP_ของรถ>:5555

# ติดตั้งไฟล์ APK
adb install -r -d BYD-Maps-Battery-Overlay-Beta.apk

# เปิดใช้งานแอป
adb shell am start -n com.byd.mapsoverlay.beta/com.byd.mapsoverlay.MainActivity
```

---

## 👨‍💻 สถาปัตยกรรมและการพัฒนาแบบ Multi-Agent (AI Collaborative Engineering)
โปรเจกต์นี้ได้รับการตรวจสอบและออกแบบร่วมกันภายใต้ข้อตกลง **AI Engineering Office Protocol**:
- **Qwen-7B**: สถาปัตยกรรมโมดูลาร์ Drag-and-Drop และตัวกรองข้อมูล
- **DeepSeek**: ตรวจสอบความปลอดภัย สิทธิ์ DiLink และมาตรการ Zero-Hole ป้องกัน SecurityException
- **MiMo-9B**: ออกแบบอินเทอร์เฟซผู้ใช้ โหมดมืด OLED และการยศาสตร์สำหรับจอรถยนต์
- **Gemini**: อินทิเกรตโค้ด ตรวจสอบการคอมไพล์ และติดตั้งทดสอบบนฮาร์ดแวร์จริง
