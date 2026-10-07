![Banner](img/banner.png)

# GRID0 ofw
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.txt)
[![Chat on Discord](https://img.shields.io/badge/Discord-5865f2?logo=discord&logoColor=white)](https://discordapp.com/invite/splatfest)

**ورچوئل LAN پر دوستوں کے ساتھ آن لائن سوئچ گیمز کھیلیں۔<br>
اسٹاک سوئچ 1 اور 2 کے لیے اوریجنل فرم ویئر (ofw) بلڈ، بغیر موڈ والے سوئچ صارفین کے لیے۔**

<sub>پرانے LAN-play ریلے کی جگہ بہتر استحکام، تیز رفتار اور آسان سیٹ اپ کے ساتھ۔</sub><br>
<sub>بینڈ سوئچ صارفین اور ایمولیٹر صارفین کے ساتھ کراس پلے کے لیے کام کرتا ہے۔ switch-lan-play کا فورک ہے، ZeroTier پر چلتا ہے۔ مزید معلومات [GRID0](https://github.com/GRID0-net/GRID0) پر۔</sub>


## سیٹ اپ گائیڈ

اسٹاک کنسولز پس منظر میں کسٹم ماڈیولز نہیں چلا سکتے۔ `GRID0-ofw` اسی ہوم نیٹ ورک سے منسلک PC پر چلتا ہے، LAN-Play پیکٹس کو خودکار طور پر کیپچر اور ترجمہ کرتا ہے۔

1. **[ریلیز پیج](https://github.com/GRID0-net/GRID0-ofw/releases)** سے تازہ ترین ریلیز ڈاؤن لوڈ کریں اور `GRID0-ofw` کھولیں۔
2. `GRID0-ofw` خودکار طور پر ZeroTier One اور npcap انسٹال کرے گا۔
   <small><details><summary>یقین کے لیے:</summary>یقینی بنائیں کہ وہ انسٹال ہیں (`GRID0-ofw` کی سیٹنگز میں لکھا ہونا چاہیے کہ ZeroTier One اور npcap انسٹال ہیں)۔<br>
   <img src="img/relay-connection.gif" width="300" alt="ریلے کے Settings ٹیب میں ZeroTier اڈاپٹر کا انتخاب">
   </details></small>
3. `GRID0-ofw` خودکار طور پر ZeroTier لانچ کرے گا اور GRID0 نیٹ ورک سے منسلک ہوگا۔
   <small><details><summary>یقین کے لیے:</summary>**ونڈوز:** اسکرین کے نیچے دائیں جانب اوپر والے تیر پر کلک کریں اور ZeroTier ٹرے آئیکن پر رائٹ کلک کریں، یقینی بنائیں کہ `8bd5124fd68185ec` GRID0 میں اسٹیٹس OK ہے۔<br>
   <img src="img/tray-video.gif" width="300" alt="PC ایپ اور ریلے کے لیے ونڈوز ٹرے میں ZeroTier کنفیگریشن">
   </details></small>
4. `GRID0-ofw` خودکار طور پر درست ZeroTier اڈاپٹر منتخب کرے گا۔
   <small><details><summary>یقین کے لیے:</summary>`GRID0-ofw` میں **Settings** کھولیں، ریلے خودکار طور پر متوقع ZeroTier کنکشن سے منسلک ہوگا، لیکن یقینی بنائیں کہ منتخب کردہ ZeroTier کنکشن درست لگ رہا ہے (اس کا نام ZeroTier جیسا ہونا چاہیے)۔<br>
   <img src="img/relay-choose-adapter.gif" width="300" alt="منتخب کردہ ZeroTier اڈاپٹر کے ساتھ ریلے کا Settings ٹیب">
   </details></small>

<small><details><summary>خودکار موڈ (آسان، صرف ونڈوز اور ہاٹ اسپاٹ کی صلاحیت والا):</summary>

5. `GRID0-ofw` کے **Play** سیکشن میں واپس جائیں، یقینی بنائیں کہ **Automatic (DHCP)** منتخب ہے، اگر PC ہاٹ اسپاٹ پہلے سے سیٹ اپ نہیں ہے تو **Set up PC hotspot** پر کلک کریں اور **Start relay** پر کلک کریں۔

[image: ریلے کا Play ٹیب جس میں Automatic (DHCP) منتخب ہے اور Start relay نظر آرہا ہے]

6. اپنے سوئچ یا سوئچ 2 کو عام طریقے سے PC ہاٹ اسپاٹ سے منسلک کریں۔ اگر آپ کا سوئچ یا سوئچ 2 پہلے سے منسلک تھا، تو بس **Sleep Mode** یا **Airplane Mode** کو آن اور آف کریں (دونوں کام کرتے ہیں)۔
</details></small>

<small><details><summary>دستی موڈ:</summary>

5. `GRID0-ofw` کے **Play** سیکشن میں واپس جائیں، یقینی بنائیں کہ **Manual IP settings** منتخب ہے، **Start relay** پر کلک کریں اور سوئچ کی IP سیٹنگز دیکھیں جو وہ آپ کو فراہم کرتا ہے۔
6. اپنے سوئچ یا سوئچ 2 پر **Network Settings** میں جائیں، اسی نیٹ ورک پر **Change Settings** جس سے آپ کا PC منسلک ہے، اور فراہم کردہ IP سیٹنگز درج کریں۔ (پرائمری DNS **8.8.8.8** رکھا جا سکتا ہے اور سیکنڈری DNS خالی چھوڑا جا سکتا ہے)۔

[image: سوئچ کی انٹرنیٹ سیٹنگز اسکرین جس میں دستی IP، سب نیٹ، گیٹ وے اور DNS فیلڈز بھرے ہوئے ہیں]
7. محفوظ کرنا اور نیٹ ورک سے منسلک ہونا یقینی بنائیں۔
</details></small>

پھر بس ریلے شروع کریں اور LAN پر اپنے دوستوں کے ساتھ کھیلیں!

---

<sub>3 انسانوں کی جانب سے محدود AI مدد کے ساتھ بنایا گیا، کئی دنوں اور بے خواب راتوں میں اصلی سوئچز پر ٹیسٹ کیا گیا۔
