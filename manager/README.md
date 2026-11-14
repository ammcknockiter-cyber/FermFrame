# FermFrame app (FermFrame.exe)

A Windows app for FermFrame clocks. One file, nothing to install: double-click `FermFrame.exe`.

- **Overview** – finds every FermFrame clock on your Wi-Fi and shows the brew, gravity, temperature, ABV, day and the graph.
- **Settings** – everything on the clock's own web page (Wi-Fi, Brewfather/RAPT, look & feel, graph, pages, time zone).
- **Firmware** – updates a clock (or all of them) to the version built into the app.
- **Set up a new clock** – does what the old installer did, for brand-new Geekmagic clocks.

Clock names live in `%APPDATA%\FermFrame\clocks.json`.

**First run on a friend's PC:** Windows may say "Windows protected your PC" because the app isn't signed.
Click **More info → Run anyway**. Setting up a new clock also needs Wi-Fi and Location turned on.

## Buy another clock

The **Buy another clock** button shows which model works and links to shop searches (`shops.json`,
built into the app; the shops for the PC's own country come first).

To change the links without sending friends a new app: put `shops.json` somewhere public that gives a
plain https link to the raw file (e.g. a GitHub Gist, using its "Raw" link without the long code in the
middle so it always points to the newest version), save that link as the only line of `shops-url.txt` next
to `build.ps1`, and rebuild once. From then on the app downloads the list each time the window opens,
keeps the last good copy, and falls back to the built-in list if it's offline. Only http(s) links are used.

## Building

```
powershell -ExecutionPolicy Bypass -File build.ps1
```

Uses the C# compiler that comes with Windows (.NET Framework 4.x, C# 5) and builds the firmware from
`..\installer\files\` into `..\FermFrame.exe`. Rebuild the app after building new firmware.
