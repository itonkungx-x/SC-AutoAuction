# Easylife in Siamcraft (Minecraft 26.3 / Fabric)

**ผู้พัฒนา / Authors:** น้องต้นสุดหล่อ & Chatgpt

มอดช่วยใช้ชีวิตใน Siamcraft: ลงประมูลแบบอัตโนมัติทีละช่อง ปรับค่าผ่าน Mod Menu และอัปเดตได้เอง
Quality-of-life client mod for Siamcraft: sequential auction listings, a bilingual config screen, and automatic updates.

## วิธีติดตั้งครั้งแรก / First installation

1. ติดตั้ง Fabric Loader สำหรับ Minecraft 26.3 และ Fabric API.
2. **ลบ SC-AutoAuction เวอร์ชันเก่าออกจาก mods ก่อน** เพื่อป้องกัน mod ID ซ้ำ.
3. ใส่ `Easylife-in-Siamcraft-26.3-0.2.11.jar` ในโฟลเดอร์ `.minecraft/mods`.
4. ติดตั้ง [Mod Menu 21.0.0](https://modrinth.com/mod/modmenu) หากต้องการหน้า Settings.
5. เข้าเกม → Mod Menu → Easylife in Siamcraft → Settings / ตั้งค่า.

## ตั้งค่าการประมูล / Auction configuration

| Setting | Default |
| --- | --- |
| Starting price | `100` |
| Minimum increase | `10%` |
| Buyout price | `0` |
| Auction duration | `60` seconds |
| Overtime | `11` seconds |
| Start | `after1m` |
| Currency | Vault |
| Override | Off |
| Delay between items | 20 ticks |

- กด **F8** เพื่อเริ่ม/หยุด, **F9** เพื่อหยุดฉุกเฉิน.
- ช่อง Inventory/Hotbar ที่มีไอเท็ม 1 ช่อง = คำขอลงประมูล 1 รายการ (หนึ่ง stack นับเป็นหนึ่งรายการ).
- เซิร์ฟเวอร์ไม่ดึงของออกจากมือเมื่อส่งเข้าคิว มอดจึงรอหน้า Dialog ปิดแล้วดำเนินการช่องต่อไป.
- ไอเท็มในช่อง Inventory จะถูกสลับมาถือชั่วคราวและคืนช่องเดิมหลังส่ง.
- ไฟล์ตั้งค่า: `config/easylife-in-siamcraft.json`.

## อัปเดตอัตโนมัติ / Automatic updates

1. มอดตรวจเวอร์ชันใหม่จาก **[GitHub Releases](https://github.com/itonkungx-x/Easylife/releases)** ของโครงการเท่านั้น.
2. ดาวน์โหลดไฟล์ `.jar` ของ Minecraft 26.3 จาก GitHub เมื่อมีเวอร์ชันที่ใหม่กว่า และเปิด Automatic updates.
3. ตรวจ SHA-256 digest, ขนาดไฟล์, Minecraft version, Fabric mod ID, และเวอร์ชันใน JAR ก่อนติดตั้ง.
4. เก็บ JAR ใหม่แยกจาก `mods` แล้วรอเกมปิด เพื่อให้ตัวช่วย Java ภายนอกติดตั้งแทนที่ไฟล์เดิมหลัง Minecraft ปิดสมบูรณ์.
5. เปิด Minecraft ใหม่เพื่อใช้เวอร์ชันล่าสุด; ดูสถานะใน Mod Menu Settings และบันทึกที่ `config/easylife-updater/install.log`.

**หมายเหตุ:** ต้องติดตั้ง 0.2.5 ด้วยตนเอง *ครั้งแรก* เนื่องจาก 0.1.1 ไม่มีระบบอัปเดตตัวเอง. การอัปเดตครั้งถัดไปไม่ต้องดาวน์โหลด JAR ด้วยตนเอง (ถ้าเกม/เครื่องอนุญาตให้เขียนไฟล์). ระบบไม่สามารถอัปเดต Minecraft, Fabric หรือ Mod Menu แทนได้.

**Notes:** Install v0.2.6 manually once; older v0.1.1 builds have no self-updater. Future versioned GitHub Releases auto-install on game shutdown. The mod only manages its own JAR.

## Release workflow

Update `mod_version` in `gradle.properties` and push to `main`. GitHub Actions builds and publishes a versioned release once for every new version. Published release assets are immutable; later commits with the same mod version do not overwrite a release.

## Safety

การปิดหน้า Dialog ไม่ได้ยืนยันว่าเซิร์ฟเวอร์รับคิวสำเร็จจริง ควรทดสอบทีละ 1–2 ชิ้นราคาถูก แล้วตรวจสอบรายการด้วยตัวเองก่อนใช้งานจำนวนมาก.
Closing the dialog only indicates the submit action happened; verify server-side queue acceptance separately. Test a couple of inexpensive items first.

Source / Issues: https://github.com/itonkungx-x/Easylife

## Modular architecture / โครงสร้างสำหรับระบบอื่น

`SCAutoAuctionClient` is now the lifecycle bootstrap rather than the auction algorithm.

- `AutomationModule`: common contract (`id`, `initialize`, `tick`, `stop`).
- `ModuleRegistry`: keeps registered modules independent and runs each tick.
- `AuctionModule`: auction-specific state machine; future workflow automation can be a new module without editing auction code.
- `EasyLifeConfig`: persistent shared configuration; new modules can introduce their own keys or dedicated config classes.
- `EasyLifeSettingsScreen`: staff-first compact Thai settings; new modules should have their own settings section.
- `EasyLifeUpdater`: common GitHub Releases updater and post-exit installer.

### Auction reliability change / แก้ปัญหาไอเท็มในมือเปลี่ยน
After server-side queue submission, Minecraft may update item components or stack count.
The auction module no longer requires byte-for-byte equivalence after submission.
Inventory swaps are still restored only when the current source and staging slot
have compatible item identities (or an empty slot after server consumption).
The mod stops if an unexpected different item occupies the staging slot, to avoid moving it into the wrong slot.

**ข้อจำกัด:** หน้าประมูลปิดไม่ได้ยืนยันว่าเซิร์ฟเวอร์รับรายการ จึงควรตรวจคิวจริง และทดสอบกับของราคาถูกก่อนลงทั้งหมด

## v0.2.5 — Crash fix & Price Extractor
- แก้ `IncompatibleClassChangeError: FabricLoader.getInstance() must be InterfaceMethodref` จากไฟล์ 0.2.4 ที่เคยประกอบ JAR ด้วย Stub API ผิดประเภท
- Rebuild with the **real** Fabric Loader API using Fabric Loom and Java 25. GitHub Actions verifies the `InterfaceMethodref` bytecode and refuses JARs that bundle FabricLoader.
- Preserves Auction / Price Extractor selector in Mod Menu. Selecting **Price Extractor** disables F8 auction submissions.
- Hover items on the **/worth** inventory. Open `http://127.0.0.1:8787` for live item and price details; `/data` for the latest item; `/items` for collected entries. These endpoints are local only.
- ⚠️ **Must manually install this fixed JAR once if you are on broken 0.2.4**, because that version crashes before its built-in updater can run. Remove old `0.2.4.jar` to prevent duplicate mod IDs.
- Build/test limitation: Java compilation, Fabric API linkage, and shutdown updater smoke tests pass in CI; live-game hover scanning is not fully validated on a Siamcraft server yet.

## v0.2.6 — โหมดแยก + โลโก้ Siam Craft

- ส่วนหัว Mod Menu มีเฉพาะ **โลโก้ Siam Craft** ตามภาพที่แนบ ไม่มีข้อความอื่น
- หน้าแรกแยก Auction และ Price Extractor พร้อมเปิด/ปิดอิสระ ใช้พร้อมกันก็ได้
- ปุ่มตั้งค่าแต่ละโมดูลเปิดหน้าแยก; แก้ปุ่มลัดด้วยหน้าควบคุมของ Minecraft (native Key Binds)
- F8 เริ่ม/หยุด Auction, F9 หยุดฉุกเฉิน, F7 เปิด/ปิด Price Extractor (ทั้งหมดเปลี่ยนปุ่มได้)
- Price Extractor สแกนไอเท็มที่เห็นในหน้า /worth โดยไม่ต้อง Hover โดยอ่าน component minecraft:lore จากข้อมูลที่เซิร์ฟเวอร์ส่ง
- แจ้งเตือนสรุปของใหม่ ราคาที่เปลี่ยน และยอดสะสมในแชต; ปิดแจ้งเตือนได้
- เว็บภายในเครื่อง: http://127.0.0.1:8787 มีเพียงรูปไอเท็มและราคา เรียงชื่อ A–Z, ราคาน้อยไปมาก หรือมากไปน้อย
- JSON: /items รายการสะสม และ /data รายการล่าสุด
- รูปบนเว็บใช้ asset ของ Minecraft 26.3 จาก mcasset.cloud (อาจไม่มีรูปของ Custom Item)
- **การเปลี่ยนหน้า /worth ยังต้องทำเอง**; หลังเปลี่ยนหน้า มอดสแกนช่องที่เห็นให้อัตโนมัติ
- ข้อมูลสแกนเก็บในหน่วยความจำขณะเกมเปิด ไม่อัปโหลดออกไปเซิร์ฟเวอร์ภายนอก
- หากเซิร์ฟเวอร์ไม่ส่งราคาผ่าน item lore จะยังไม่แสดงราคา ไม่สร้างตัวเลขขึ้นเอง
- Java 25 / Fabric Loader / updater ผ่าน CI แล้ว; ต้องทดสอบความถูกต้องกับปลั๊กอิน /worth ในเกมจริง

## v0.2.7 — แก้สแกนราคา + Settings แนว ChatTools

- **แก้สแกนไม่พบราคา**: เดิมอ่านเฉพาะ Item Lore; ใหม่ดึงข้อความ Tooltip ที่หน้า `/worth` แสดงจริง รวมถึงข้อมูลจากมอดฝั่ง Client และใช้ Lore เป็น Fallback
- ตัวอ่านลองประมวลผลทุกรายการที่แสดงใน /worth โดยไม่ต้อง Hover; หากเซิร์ฟเวอร์หรือมอดอื่นแสดงราคาเฉพาะเมาส์ชี้จริง ๆ อาจยังต้องตรวจสอบข้อมูลเพิ่มเติม
- **แก้ F7 ใน GUI** ผ่าน Fabric ScreenKeyboardEvents: กด F7 ในหน้า /worth ได้ และรองรับปุ่มที่เปลี่ยนผ่าน Key Binds
- **F7 เป็น Toggle:** หากเปิดระบบไว้แล้ว F7 อีกครั้งคือปิด ไม่ใช่สั่งเริ่มใหม่; หน้า Settings แสดงสถานะชัดเจน
- เพิ่มข้อความแชตแจ้งเปิด /worth, พบไอเท็มใหม่, พบช่องแต่ไม่พบราคา และสถานะรายละเอียดใน Settings
- หน้า Settings ใหม่สไตล์ ChatTools: แถบ General / Auction / Price Scan / Keybinds / Updates, ช่องค้นหาที่กรองรายการได้ และ Save & Quit ด้านล่าง
- **ส่วนหัวมีแค่โลโก้ Siamcraft** ไม่มีชื่อซ้ำ
- ภาษาไทยเป็นค่าเริ่มต้น **ภายในมอดเอง** และสลับ English ได้จากแท็บ General → ภาษาของมอด โดยไม่ต้องเปลี่ยนภาษาทั้งเกม
- ราคาดูที่ http://127.0.0.1:8787/ (ขณะเปิดสแกน); /items แสดงทั้งหมดที่เก็บ และ /data แสดงไอเท็มล่าสุด
- CI เพิ่ม PriceTextParserTest ที่ทดสอบรูปแบบราคาไทย/อังกฤษและไม่อ่านตัวเลขอื่นเป็นราคา
- ต้องอัปเกรด JAR รุ่นก่อนเป็น **0.2.7** (Minecraft 26.3 Java 25 Fabric) และเอารุ่นเก่าออกจาก mods เพื่อเลี่ยง mod ID ซ้ำ
- **การทดสอบจริง:** การคอมไพล์ CI และ parser ผ่านแล้ว แต่ไม่สามารถเชื่อมต่อเซิร์ฟเวอร์ Siamcraft เพื่อยืนยันการดึง Tooltip ที่สร้างโดยเซิร์ฟเวอร์ได้ในสภาพแวดล้อมนี้

 
## v0.2.8 — Price Scan diagnostic fix (Minecraft 26.3)

- **เปลี่ยน F7 ในหน้า /worth เป็นปุ่มเปิด/เช็กสถานะ:** หากเปิดจาก Mod Menu แล้ว F7 จะไม่ปิดระบบโดยไม่ตั้งใจ (หากต้องการปิด ให้ใช้ปุ่มปิดใน Mod Menu หรือกด F7 นอกหน้า /worth)
- **Captured Tooltip:** สแกนจาก Item Lore, ข้อความ Tooltip ของ Container และใช้ Fabric ItemTooltipCallback ร่วมกับ Mixin แบบ read-only เพื่อจับข้อความที่เกมแสดงจริงตอนเอาเมาส์ชี้
- ยังคงสแกนไอเท็มในหน้าที่เปิดอัตโนมัติ; ถ้าราคามีเฉพาะ Tooltip ตอน Hover จะดึงเมื่อ Hover ได้ด้วย
- ตรวจสอบกรณีเมนูแบบ Custom มีช่องน้อยกว่า 36 (เดิมจะสรุปว่ามี 0 ช่อง)
- เมื่อเจอเมนู /worth จะมีข้อความในแชตและสถานะแสดงใน Mod Menu (จำนวนช่อง, ราคาที่พบ, จำนวนครั้งที่จับ Tooltip)
- ถ้าสแกน 5 รอบแล้วยังไม่มีราคา จะบันทึกไฟล์ **`config/easylife-price-diagnostic.json`** อัตโนมัติ พร้อมตัวอย่างไอเท็มสูงสุด 8 ชิ้นและ Tooltip สูงสุด 16 บรรทัด/ชิ้น (ไม่มี IP, username, chat)
- วิธีส่งข้อมูลแก้บั๊ก: เปิด /worth ค้างไว้สักครู่ แล้วส่งไฟล์ easylife-price-diagnostic.json พร้อมส่วนที่มีคำว่า [Easylife] ใน logs/latest.log เพื่อให้ตรวจรูปแบบราคาที่ส่งมาได้ตรงกับของจริง
- Web Dashboard ยังคงเป็น localhost ที่ http://127.0.0.1:8787 ไม่ส่งออกภายนอก
- Compiler, Fabric Loader linkage, and installer smoke tests run in GitHub Actions. **No live connection to Siamcraft server available in CI** — real tooltip compatibility must be verified on the user's client.

 
## v0.2.9 — /worth inventory slot diagnostics and scan source fallback
- Minecraft 26.3 Fabric Java 25, replaces v0.2.8.
- Compared both `AbstractContainerScreen.getMenu().slots` and the active `Minecraft.player.containerMenu.slots`; uses the menu with visible item listings when their container IDs agree.
- When `/worth` opens, chat reports both GUI slot count and active-player slot count. This helps diagnose menu desynchronization.
- Fixed the silent `Slots 0` case: after 3 scans without sale slots, writes `config/easylife-price-diagnostic.json` with slot counts and menu source, even if no item is visible to the scanner.
- Preserves last scan result after closing the menu; Price Scan settings now display concise `GUI … | Inv … | Item … | Price … | Hover …` counters without truncation.
- Still reads actual tooltips and server-provided lore (v0.2.8) and will never synthesize prices when data cannot be read.
- Important: the attached latest.log ended before the provided Settings screenshot. Do not interpret missing subsequent scan log lines as proof the scan never ran.
- Development status: compiled and smoke-tested with Fabric Loom via CI; not validated on a live Siamcraft /worth screen. If still reporting zero, inspect the newly generated diagnostic JSON.

 
## v0.2.10 — วิธีสแกนราคา 3 แบบ (Minecraft 26.3 Fabric)

- **Mod Menu → Easylife → สแกนราคา → วิธีสแกนราคา** มีปุ่มให้วนเลือกระหว่าง `อัตโนมัติ`, `Hover เท่านั้น`, และ `ทั้งคู่ (Both)`.
- **อัตโนมัติ / Auto:** สแกนไอเท็มที่อยู่ในช่องรายการของหน้า `/worth` ทุก 12 tick โดยไม่ต้องเลื่อนเมาส์ โหมดนี้ไม่เก็บรายการจาก Hover events.
- **Hover เท่านั้น:** ไม่สแกนทั้งหน้า; รับ ItemTooltipCallback และ Container Tooltip Mixin เมื่อมีการ Hover จริง พร้อมอ่านช่องที่เมาส์ชี้ผ่านฟิลด์ `hoveredSlot` เป็น fallback ทุก 4 tick.
- **ทั้งคู่ / Both:** ใช้สองวิธีพร้อมกัน ยุบไอเท็มซ้ำตาม item ID + ชื่อไอเท็ม และอัปเดตราคาเมื่อมีการเปลี่ยนแปลง.
- ค่าเริ่มต้น: **Both**. บันทึกค่าที่ `config/easylife-systems.json` เพื่อจำโหมดข้ามการเปิดเกม.
- UI รองรับภาษาไทยเป็นค่าเริ่มต้นและภาษาอังกฤษ ตามการตั้งค่าเดิม.
- **ข้อจำกัด:** หาก /worth ของเซิร์ฟเวอร์ไม่ส่งข้อมูลราคาที่มอดเข้าถึงได้ ก็ยังอาจสแกนไม่พบราคา; Build ผ่าน แต่ยังไม่ได้ทดสอบบน Siamcraft จริง. ถ้า Hover/Both ยัง 0 ให้ส่ง `config/easylife-price-diagnostic.json` และ `logs/latest.log` เพื่อระบุจุดที่ข้อมูลไม่ถูกส่งต่อ.

 
## v0.2.11 — Integrates archived ItemPriceExtractor v1.2.0 sources

Source basis: user-provided `ItemPriceExtractor-26.3-Source-v1.2.0.zip`.
The old code had these ideas relevant to a Hover scanner:
1. Mixin hooks `AbstractContainerScreen.getTooltipFromContainerItem(ItemStack)` at `RETURN` to read final rendered tooltip text rather than only a slot's item lore.
2. Strict Thai/English per-unit-price regex, avoiding a stack-price number or tooltip component count.
3. `SystemToast` visible when a container GUI covers chat.
4. SDL `KeyboardHandler.keyPress` hook that handles the scanner hotkey while a container GUI is open.

Easylife now adapts all four ideas to the existing Price Extractor module. The SDL fallback is **only** used for the configured Price Scan shortcut inside /worth, and duplicate events are debounced. The existing Auto / Hover / Both selector is preserved.

### Testing
- Run `gradle --no-daemon clean build` with Java 25 and Fabric Loom. CI also tests `PriceTextParserTest` and `LegacyUnitPriceParserTest`.
- Java compilation and bytecode linkage checks are not a substitute for actually opening /worth in Minecraft.
- Manual test: Mod Menu → Easylife → Price Scan → Enable → **Hover only** → Save. Open /worth and hover Dragon Egg. The correct expected sample is **1,000,000 per item** and **64,000,000 per stack**.
- If the user does not see the Toast/chat event, inspect `config/easylife-price-diagnostic.json` plus the Easylife lines in `logs/latest.log`; a Mixin may not run in custom container tooltips.
- Do not package any stubs from the old source zip in the new mod. They were compilation scaffolds, not runtime classes.


## v0.2.12 — Price Extractor web redesign, fixed icon image source

- Updated `assets/scautoauction/web/index.html` to a responsive dark/amber Siamcraft dashboard.
- Fixed missing item images caused by `mcasset.cloud/26.3/assets/...` texture paths; icons now try the BlockRender render/item and render/block endpoints with a readable fallback if an icon cannot be fetched.
- Page search, A–Z / lowest price / highest price sort, cached polling, lazy-load and 48-card pagination support 1,100+ items without rebuilding 1,100 image nodes each second.
- Serves the existing Siamcraft logo from `/logo.png` on the local Price Extractor HTTP server so the logo works offline.
- Preserves /worth scanner, Auction, Auto / Hover / Both, Thai/English Mod Menu UI and auto updater behavior.
- Release asset must be published on GitHub as `Easylife-in-Siamcraft-26.3-0.2.12.jar` for existing v0.2.11 auto-updaters to discover it.
- Image icons use a third-party online render API; custom server-specific items may use text fallback. Actual in-game Price Extractor end-to-end testing is separate from build checks.
