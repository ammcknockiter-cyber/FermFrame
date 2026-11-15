# Is FermFrame safe?

Short answer: yes, for what it does. This page explains what FermFrame can and can't get at, where the risks are,
and what you can do about them, in plain words.

## What FermFrame never touches

- **Nothing else on your PC.** The Windows app only talks to FermFrames on your home network, the FermFrame project
  on GitHub (for the shop list and, once a day, to check for a new version) and nothing else. It doesn't read your
  files, your browser, your email or your bank, and it doesn't collect or send anything about you.
- **No passwords or keys on your PC.** The app stores your FermFrame names and addresses in `%APPDATA%\FermFrame`.
  If you give a FermFrame a settings password, the app keeps it encrypted so only your Windows account on that PC can
  read it.
- **No passwords or keys shown back.** A FermFrame keeps your Wi-Fi password and Brewfather/RAPT key on itself and
  never shows them again – not on its settings page, not to the app.

## What's at stake

The only things a FermFrame holds are your **brewing readings**, your **Wi-Fi password** and your
**Brewfather or RAPT key**. That's the worst case if something goes wrong. If you ever think a key has leaked, make a new
one in Brewfather or RAPT – the old one stops working straight away.

## The protections (FermFrame 2.3 and app 1.1)

| Risk | Protection |
|---|---|
| Someone else on your Wi-Fi changes a FermFrame or installs other firmware on it | **Settings password** (optional): set it in the app's Settings tab, during setup, or on the FermFrame's own settings page. Without it nobody can open the settings page, save settings or install firmware. User name in a browser: `fermframe`. |
| Someone nearby joins the FermFrame's setup Wi-Fi during setup | The setup Wi-Fi (*FermFrame-Setup*) has its own **password, shown only on the FermFrame's screen** and inside its QR code. Each FermFrame makes up its own. |
| Someone listens in on the connection to Brewfather or RAPT | The connection is **encrypted (HTTPS)**. |
| A fake download | Download only from the [Releases page](https://github.com/ammcknockiter-cyber/FermFrame/releases). The app's updater checks every download against its published SHA-256 checksum before installing. |

**Forgot the settings password?** Plug the FermFrame in **3 times in a row**, unplugging it each time within
10 seconds. It opens **Setup mode**: join the Wi-Fi shown on its screen (scan the QR code) and the settings page opens
without the password, so you can change or remove it. This needs the password on the FermFrame's screen, so only
someone standing next to it can do it. If nobody joins within 3 minutes it goes back to normal.

## What's still not perfect (honestly)

- **Certificates aren't checked.** The FermFrame encrypts its connection to Brewfather and RAPT but doesn't check
  their certificates, because the clock has very little memory. Someone who controlled your home network or internet
  connection could, in theory, pretend to be Brewfather and capture the key. This is hard to do and we may add the check
  in a later version if it fits.
- **The settings page uses plain HTTP on your home network.** A settings password typed into a browser travels
  unencrypted inside your home network (not over the internet). Someone would need to be on your Wi-Fi and listening
  at that moment.
- **Readings and status are open.** Anyone on your Wi-Fi can see the graph data and the FermFrame's status (version,
  Wi-Fi name, whether a password is set). They contain no passwords or keys.
- **The app isn't signed by a company,** which is why Windows says "Windows protected your PC". Signing costs money
  every year; the code is open for anyone to read instead.

## Good habits

1. When you make your Brewfather API key, tick **only "Read batches"** (and readings) – then even a stolen key can't
   change anything in your account.
2. Set a **settings password** if other people use your Wi-Fi (housemates, guests, a shared network).
3. Keep FermFrames on a Wi-Fi network you trust. A separate guest network for gadgets is even better.
4. Download FermFrame only from the GitHub Releases page.

## Reporting a problem

Found a security problem? Please open an issue on the
[project page](https://github.com/ammcknockiter-cyber/FermFrame/issues) – without the details of how to exploit it – and
we'll arrange how to share them.
