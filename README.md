# Aries Control

Android app for the original **AURALiC Aries** streamer (2014, FemtoClock), now that AURALiC has closed and Lightning DS is no longer developed.

## What it does (step 1)
- **Finds the Aries by itself** on the home network, so you never have to type the IP address.
  1. SSDP/UPnP search: asks the network which media players are there and picks the one made by AURALiC.
  2. If nothing answers: checks every address on the home network for the Aries web page.
  3. Last resort: type the IP address (shown on the Aries display). It is remembered.
- **Shows the Aries settings page** (Lightning web interface) inside the app, with zoom, back button and file picking.
- If the Aries gets a new IP address, tap **Search again** and the app finds it.
- **Updates itself** from this project's "Latest version" (menu ⋮ → Check for updates). No app store needed.

## Requirements
Android 10 or newer, and the phone on the same Wi-Fi as the Aries.

## Building
Every change pushed to GitHub builds a new `AriesControl.apk` under **Releases → Latest version**, and runs an automatic test in an Android emulator (screenshots under **Releases → Test**).
