![Banner](img/banner.png)

# GRID0 ofw | گرد زیرو (اصل فرم ویئر)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.txt)
[![Chat on Discord](https://img.shields.io/badge/Discord-5865f2?logo=discord&logoColor=white)](https://discordapp.com/invite/splatfest)

**ورچوئل LAN کے ذریعے دوستوں کے ساتھ آن لائن سوئچ (Switch) گیمز کھیلیں۔<br>
اسٹاک (غیر ترمیم شدہ) Switch 1 اور 2 کے لیے اصل فرم ویئر (OFW) بلڈ، ان صارفین کے لیے جن کے کنسول میں کوئی ترمیم (mod) نہیں کی گئی ہے۔٭٭

<sub>یہ پرانے ریلے کی جگہ بہتر استحکام، تیز رفتار اور آسان سیٹ اپ کی سہولت فراہم کرتا ہے۔</sub><br>
<sub>ممنوعہ سوئچ صارفین اور ایمولیٹر صارفین کے ساتھ کراس پلے کے لیے کام کرتا ہے۔ سوئچ لین پلے کا فورک، زیرو ٹیر پر چلتا ہے۔ مزید معلومات لنک پر.</sub>
<sub>[GRID0](https://github.com/GRID0-net/GRID0).</sub>

## Setup Guide

اسٹاک کنسولز پسِ پردہ (background) کسٹم ماڈیولز کو چلانے کی صلاحیت نہیں رکھتے۔ `GRID0-ofw` اسی ہوم نیٹ ورک سے منسلک ایک پی سی (PC) پر چلتا ہے اور خودکار طور پر LAN-Play پیکٹس کو کیپچر اور ٹرانسلیٹ کرتا ہے۔

1. **[ریلیزز کے صفحے](https://github.com/GRID0-net/GRID0-ofw/releases)** سے تازہ ترین ریلیز ڈاؤن لوڈ کریں اور `GRID0-ofw` کو کھولیں۔
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
