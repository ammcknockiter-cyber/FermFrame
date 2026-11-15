# FermFrame

**See your fermentation at a glance.** FermFrame turns a cheap desktop "smart weather clock" into a small
display that shows your brew's gravity, temperature, ABV and a graph, straight from **Brewfather** or a
**RAPT Pill**. It runs on its own over Wi-Fi: no PC, hub or app needs to stay on.

The free Windows app sets up new displays, shows all of them in one place, changes their settings and keeps
their firmware up to date.

**[Read the full manual](docs/MANUAL.md)** · **[Watch the 3-minute video guide on YouTube](https://youtu.be/npmeVGuBcoU)**

## What you need

- A **"Smart Weather Desktop Clock"** – the small cube with a square colour screen, also sold as
  **GeekMagic SmallTV** or **SmallTV-Ultra** (ESP8266 chip). The app's **Buy another FermFrame** button links
  to the listing FermFrame was tested with.
  **Not** the SmallTV **Pro** or other ESP32 versions – they use a different chip.
- A **Windows 10 or 11** PC with Wi-Fi (only for setup; a laptop is ideal).
- **2.4 GHz Wi-Fi** at home (most routers have it).
- A **Brewfather Premium** account with an API key (Settings → API → Generate, tick *Read batches*), or a
  **RAPT** account with an API secret. Brewfather's API needs a **Premium** subscription (new accounts get a 30-day free trial). RAPT doesn't need a paid plan.

## Install the app

1. Download **`FermFrame-<version>.exe`** from the
   [Releases page](https://github.com/ammcknockiter-cyber/FermFrame/releases/latest).
2. Open it. Windows may say *"Windows protected your PC"* because the app isn't signed by a company –
   click **More info → Run anyway**.
3. Click **Install**. It installs just for you (no admin rights) and adds FermFrame to the Start menu.

To remove it: Windows **Settings → Apps → FermFrame → Uninstall**. Your FermFrames keep working.

## Set up a FermFrame

1. Plug the clock into power (any USB charger – it isn't connected to the PC).
2. In the app, click **Set up a new FermFrame**, check your Wi-Fi details, enter your Brewfather user ID and
   API key (or RAPT email and secret) and click **Set up my FermFrame**.
3. The PC leaves your Wi-Fi for a minute or two to talk to the clock, installs FermFrame, then comes back.
   About two minutes later your brew is on the screen.

Only batches with the status **Fermenting** in Brewfather are shown.

## Using it

- **Overview** – live gravity, temperature, ABV, day and the graph for each FermFrame.
- **Settings** – Wi-Fi, readings, themes and colours, fonts, brightness and night dimming, graph style and
  range, rotating pages (graph, big number, clock, other brews) and the "Ready to keg?" hint.
- **Firmware** – updates FermFrames to the version built into the app.

Each FermFrame also has its own settings page in a browser: click **Open the FermFrame's web page**, or scan
the QR code on its screen.

## Privacy and security

- The app talks only to FermFrames on your own network, fetches the shop list from this project and, once a day, asks
  GitHub whether there's a newer version (you can switch this off in **About**). It doesn't collect or send anything about you.
- Each FermFrame stores your Wi-Fi password and Brewfather/RAPT key on itself and fetches your readings from
  Brewfather or RAPT every few minutes.
- The connection to Brewfather/RAPT is encrypted, but certificates aren't checked (the ESP8266 has too little
  memory). Anyone on your home network can open a FermFrame's settings page; saved passwords and keys are
  never shown on it.

## Troubleshooting

| Problem | Try |
|---|---|
| Setup can't find the clock | Unplug it, wait 5 seconds, plug it back in, wait 30 seconds, try again |
| "Windows needs Location turned on" | Settings → Privacy → Location: turn on *Location services* and *Let desktop apps access your location* (Windows needs this to list Wi-Fi networks) |
| Screen says **Can't find Wi-Fi** | The Wi-Fi name or password is wrong, or it's 5 GHz only: scan the QR code on the screen with your phone to fix it |
| Screen says the ID or key is wrong | Check them in the app's Settings tab |
| No brew showing | Set the batch's status to **Fermenting** in Brewfather |

## Building from source

See [docs/DEVELOPING.md](docs/DEVELOPING.md). The firmware builds with PlatformIO; the app builds with the
C# compiler that comes with Windows.

## Licence

FermFrame is free, open-source software under the [MIT licence](LICENSE). It is built with other open-source
software listed in [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt).

**No warranty.** Installing firmware on a device is at your own risk and can stop it working.
FermFrame is not made by or connected with GeekMagic, Brewfather or KegLand (RAPT); their names and products
belong to their owners.
