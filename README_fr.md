![Banner](img/banner.png)

# GRID0 ofw
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.txt)
[![Chat on Discord](https://img.shields.io/badge/Discord-5865f2?logo=discord&logoColor=white)](https://discordapp.com/invite/splatfest)

**Play Switch games online with friends over a virtual LAN.<br>
Original Firm-Ware (ofw) build for stock Switch 1 and 2, for Unmodded Switch Users.**

<sub>Replaces the old LAN-play relays with better stability, faster speeds, and an easier setup.</sub><br>
<sub>Works for cross-play with banned switch users and emulator users. Fork of switch-lan-play, runs on ZeroTier. More info at [GRID0](https://github.com/GRID0-net/GRID0).</sub>


## Setup Guide

Stock consoles cannot execute background custom modules. `GRID0-ofw` runs on a PC connected to the same home network, capturing and translating LAN-Play packets automatically.

1. Download the latest release from the **[releases page](https://github.com/GRID0-net/GRID0-ofw/releases)** and open `GRID0-ofw`.
2. `GRID0-ofw` will automatically install ZeroTier One and npcap.
   <small><details><summary>For certainty:</summary>Make sure they are installed (it should say ZeroTier One and npcap are installed in the `GRID0-ofw` settings).<br>
   <img src="img/relay-connection.gif" width="300" alt="Choosing the ZeroTier adapter in the relay Settings tab">
   </details></small>
3. `GRID0-ofw` will automatically launch ZeroTier and connect to the GRID0 network.
   <small><details><summary>For certainty:</summary>**Windows:** Click the Up arrow at the bottom right of your screen and right-click the ZeroTier tray icon, make sure the status is OK in `8bd5124fd68185ec` GRID0.<br>
   <img src="img/tray-video.gif" width="300" alt="Windows tray ZeroTier config for the PC app and the relay">
   </details></small>
4. `GRID0-ofw` will automatically select the correct ZeroTier adapter.
   <small><details><summary>For certainty:</summary>In `GRID0-ofw`, open **Settings**, the relay will automatically connect to the presumed ZeroTier connection, but make sure the ZeroTier connection selected looks like the correct one (should be named something similar to ZeroTier).<br>
   <img src="img/relay-choose-adapter.gif" width="300" alt="The relay Settings tab with the ZeroTier adapter selected">
   </details></small>

<small><details><summary>Automatic Mode (Easier, Windows and Hotspot Capable Only):</summary>

5. Go back to the **Play** section of `GRID0-ofw`, make sure **Automatic (DHCP)** is selected, click **Set up PC hotspot** if it isn't already set up and click **Start relay**.

[image: the relay Play tab with Automatic (DHCP) selected and Start relay visible]

6. Connect your Switch or Switch 2 to the PC Hotspot normally. If your Switch or Switch 2 was already connected, simply turn on and off either **Sleep Mode** or **Airplane Mode** (both work).
</details></small>

<small><details><summary>Manual Mode:</summary>

5. Go back to the **Play** section of `GRID0-ofw`, make sure **Manual IP settings** is selected, click **Start relay** and notice the Switch IP settings it provides you.
6. On your Switch or Switch 2, enter **Network Settings**, **Change Settings** on the same network your PC is connected to, and enter the provided IP settings. (Primary DNS can be set to **8.8.8.8** and Secondary DNS can be left blank).

[image: the Switch internet settings screen with the manual IP, subnet, gateway, and DNS fields filled in]
7. Make sure to save and connect to the network.
</details></small>

Then just start the relay and play with your friends over LAN!

---

<sub>Built by 3 humans with limited AI assistance, tested on real Switches over many days and restless nights.
