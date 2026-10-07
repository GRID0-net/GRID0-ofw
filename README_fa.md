![Banner](img/banner.png)

# GRID0 ofw
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.txt)
[![Chat on Discord](https://img.shields.io/badge/Discord-5865f2?logo=discord&logoColor=white)](https://discordapp.com/invite/splatfest)

**بازی‌های سوییچ را به‌صورت آنلاین با دوستان خود روی یک LAN مجازی بازی کنید.<br>
نسخه Original Firm-Ware (ofw) برای سوییچ ۱ و ۲ دست‌نخورده.**

<sub>جایگزین رله‌های قدیمی LAN-play با پایداری بهتر، سرعت بیشتر و راه‌اندازی آسان‌تر.</sub><br>
<sub>برای کراس‌پلی با کاربران سوییچ بن‌شده و کاربران شبیه‌ساز کار می‌کند. فورک switch-lan-play، روی ZeroTier اجرا می‌شود. اطلاعات بیشتر در [GRID0](https://github.com/GRID0-net/GRID0).</sub>


## راهنمای راه‌اندازی

کنسول‌های دست‌نخورده نمی‌توانند ماژول‌های سفارشی را در پس‌زمینه اجرا کنند. `GRID0-ofw` روی یک PC متصل به همان شبکه خانگی اجرا می‌شود و بسته‌های LAN-Play را به‌صورت خودکار دریافت و ترجمه می‌کند.

1. آخرین نسخه را از **[صفحه انتشارها](https://github.com/GRID0-net/GRID0-ofw/releases)** دانلود کنید و `GRID0-ofw` را باز کنید.
2. `GRID0-ofw` به‌صورت خودکار ZeroTier One و npcap را نصب می‌کند.
   <small><details><summary>برای اطمینان:</summary>مطمئن شوید نصب شده‌اند (باید در تنظیمات `GRID0-ofw` نوشته شده باشد که ZeroTier One و npcap نصب هستند).<br>
   <img src="img/relay-connection.gif" width="300" alt="انتخاب آداپتور ZeroTier در تب تنظیمات رله">
   </details></small>
3. `GRID0-ofw` به‌صورت خودکار ZeroTier را اجرا می‌کند و به شبکه GRID0 متصل می‌شود.
   <small><details><summary>برای اطمینان:</summary>**ویندوز:** روی فلش بالا در پایین سمت راست صفحه کلیک کنید و روی آیکون ZeroTier در سینی سیستم راست‌کلیک کنید، مطمئن شوید وضعیت در `8bd5124fd68185ec` GRID0 روی OK است.<br>
   <img src="img/tray-video.gif" width="300" alt="تنظیمات ZeroTier در سینی ویندوز برای اپ PC و رله">
   </details></small>
4. `GRID0-ofw` به‌صورت خودکار آداپتور درست ZeroTier را انتخاب می‌کند.
   <small><details><summary>برای اطمینان:</summary>در `GRID0-ofw` وارد **Settings** شوید؛ رله به‌صورت خودکار به اتصال ZeroTier شناسایی‌شده متصل می‌شود، اما مطمئن شوید اتصال ZeroTier انتخاب‌شده همان مورد درست به نظر می‌رسد (باید نامی شبیه ZeroTier داشته باشد).<br>
   <img src="img/relay-choose-adapter.gif" width="300" alt="تب تنظیمات رله با آداپتور ZeroTier انتخاب‌شده">
   </details></small>

<small><details><summary>حالت خودکار (آسان‌تر، فقط ویندوز و با قابلیت هات‌اسپات):</summary>

5. به بخش **Play** در `GRID0-ofw` برگردید، مطمئن شوید **Automatic (DHCP)** انتخاب شده است، اگر هات‌اسپات PC هنوز راه‌اندازی نشده روی **Set up PC hotspot** کلیک کنید و سپس روی **Start relay** کلیک کنید.

[image: تب Play رله با انتخاب Automatic (DHCP) و نمایش Start relay]

6. سوییچ یا سوییچ ۲ خود را به‌صورت عادی به هات‌اسپات PC متصل کنید. اگر سوییچ یا سوییچ ۲ شما از قبل متصل بود، کافی است **Sleep Mode** یا **Airplane Mode** را خاموش و روشن کنید (هر دو جواب می‌دهند).
</details></small>

<small><details><summary>حالت دستی:</summary>

5. به بخش **Play** در `GRID0-ofw` برگردید، مطمئن شوید **Manual IP settings** انتخاب شده است، روی **Start relay** کلیک کنید و به تنظیمات IP سوییچ که به شما می‌دهد توجه کنید.
6. در سوییچ یا سوییچ ۲ خود، وارد **Network Settings** شوید، در همان شبکه‌ای که PC شما به آن متصل است **Change Settings** را انتخاب کنید و تنظیمات IP داده‌شده را وارد کنید. (DNS اصلی می‌تواند **8.8.8.8** باشد و DNS فرعی را می‌توان خالی گذاشت).

[image: صفحه تنظیمات اینترنت سوییچ با فیلدهای IP دستی، ساب‌نت، گیت‌وی و DNS پرشده]
7. حتماً ذخیره کنید و به شبکه متصل شوید.
</details></small>

سپس فقط رله را اجرا کنید و با دوستان خود روی LAN بازی کنید!

---

<sub>ساخته‌شده توسط ۳ انسان با کمک محدود هوش مصنوعی، تست‌شده روی سوییچ‌های واقعی طی روزهای زیاد و شب‌های بی‌خواب.
