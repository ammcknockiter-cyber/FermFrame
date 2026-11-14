# Developing FermFrame

FermFrame was called BrewScreen before; the firmware still answers on `/brewscreen.json` and the app still
recognises BrewScreen devices.

## Layout

| Path | What |
|---|---|
| `src/main.cpp`, `platformio.ini` | The firmware (PlatformIO, Arduino core for ESP8266) |
| `installer/files/fermframe.bin`, `fermframe-lite.bin` | The built firmware the app embeds |
| `manager/` | The Windows app (`FermFrameApp.cs`), its build script and the shop list |
| `installer/` (the `.bat` / `.ps1`) | The original PowerShell installer, replaced by the app; kept for reference |
| `dist/` | Release files made by the build (not committed) |

## Firmware

```
python -m platformio run
```

- `geekmagic` → `.pio/build/geekmagic/firmware.bin` (full, ~595 KB, with `fermframe.local`)
- `geekmagic_lite` → `.pio/build/geekmagic_lite/firmware.bin` (setup only: Wi-Fi, `/save`, `/update`).
  Must stay **under 507,904 bytes** so a brand-new clock's own update page accepts it; setup always follows it
  with the full build.

Copy them to `installer/files/fermframe.bin` and `installer/files/fermframe-lite.bin`, then rebuild the app.
Bump `FERMFRAME_VERSION` in `platformio.ini` for each release; the app offers an update when a display's
version is lower than the one built in.

Hardware: Geekmagic "Smart Weather Clock" / SmallTV / SmallTV-Ultra, ESP8266 (ESP-12F), 4 MB flash,
ST7789 240×240 (MOSI 13, SCLK 14, DC 0, RST 2, SPI mode 3), backlight GPIO 5 active LOW.
Free heap must stay around 37 KB for BearSSL; keep web page strings in flash (`F()` / `PROGMEM`).

### Endpoints

| Path | |
|---|---|
| `/` | Settings page (with live preview from `/graph.json`) |
| `/save` (POST) | Saves settings; restarts only if the Wi-Fi changed |
| `/update` | Firmware upload (`ESP8266HTTPUpdateServer`) |
| `/fermframe.json` | Status: firmware, build, version, MAC, mode, IP, Wi-Fi, account, problem, heap, readings, brews |
| `/graph.json` | Current brew and the bucketed gravity/temperature series |

### How setup works (new clock, over Wi-Fi only - the USB port is power-only)

1. Find the clock: its own Wi-Fi (`Smart weather clock…` when new, `FermFrame-Setup` when FermFrame is
   waiting for setup) or on the home network (LAN scan).
2. New clock: upload `fermframe-lite.bin` to the stock firmware's `/update_ota` (limit 507,904 bytes,
   field `update`, file name starting `SDP-`).
3. Upload the full `fermframe.bin` through FermFrame's own `/update`.
4. Post Wi-Fi + account details to `/save`, put the PC back on its Wi-Fi, then check `/fermframe.json`.

### On the display

| Situation | Screen |
|---|---|
| No Wi-Fi saved | **Set me up!** + QR code to join `FermFrame-Setup`; the setup page pops up on the phone |
| Wi-Fi saved but missing | **Can't find Wi-Fi** + QR; after 3 minutes with nobody using it, it restarts and tries again |
| No account details | **Almost there!** + QR to the settings page |
| A problem and no data yet | Plain-English problem + what to do + QR to the settings page |
| A problem after data was shown | Last good graph stays, problem in red under the numbers |

### If the screen looks wrong on other hardware

| Symptom | Fix in `platformio.ini` |
|---|---|
| Screen stays black | `BACKLIGHT_ON=LOW` → `HIGH` |
| Colours inverted / odd | add `-D TFT_INVERSION_OFF=1` or `-D TFT_RGB_ORDER=TFT_BGR` |
| Picture garbled | `-D SPI_FREQUENCY=27000000` |

## Windows app

```
powershell -ExecutionPolicy Bypass -File manager\build.ps1
```

Uses the C# compiler that ships with Windows (.NET Framework 4.x), so the code must stay **C# 5**
(no `$"..."`, `?.`, `=>` members). Output: `FermFrame.exe`; with `-Release` also `dist/FermFrame-<version>.exe` and its checksum (refused if that version already exists).
The app version is `AppInfo.Version` plus the assembly versions at the top of `FermFrameApp.cs`.

Optional one-line files next to `build.ps1`:

- `project-url.txt` – the project page (shown in About and in Windows' Apps list)
- `shops-url.txt` – a raw https link to `shops.json`, so the shop list can change without a new app

Testing tip: set `FERMFRAME_SCALE=1.5` to make the app draw as it would on a 150% display.
Run with `/portable` to skip the install window, or `/demo` for made-up FermFrames (used for the manual and video: nothing real is contacted or saved).
