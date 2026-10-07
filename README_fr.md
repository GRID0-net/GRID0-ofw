![Banner](img/banner.png)

# GRID0 ofw
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.txt)
[![Chat on Discord](https://img.shields.io/badge/Discord-5865f2?logo=discord&logoColor=white)](https://discordapp.com/invite/splatfest)

**Jouez à des jeux Switch en ligne avec vos amis via un LAN virtuel.<br>
Version Original Firm-Ware (ofw) pour Switch 1 et 2 non modifiées.**

<sub>Remplace les anciens relais LAN-play avec une meilleure stabilité, des vitesses plus rapides et une configuration plus simple.</sub><br>
<sub>Fonctionne pour le cross-play avec les utilisateurs de Switch bannies et les utilisateurs d'émulateurs. Fork de switch-lan-play, fonctionne sur ZeroTier. Plus d'infos sur [GRID0](https://github.com/GRID0-net/GRID0).</sub>


## Guide d'installation

Les consoles non modifiées ne peuvent pas exécuter de modules personnalisés en arrière-plan. `GRID0-ofw` fonctionne sur un PC connecté au même réseau domestique, capturant et traduisant automatiquement les paquets LAN-Play.

1. Téléchargez la dernière version depuis la **[page des versions](https://github.com/GRID0-net/GRID0-ofw/releases)** et ouvrez `GRID0-ofw`.
2. `GRID0-ofw` installera automatiquement ZeroTier One et npcap.
   <small><details><summary>Pour en être sûr :</summary>Assurez-vous qu'ils sont installés (cela devrait indiquer que ZeroTier One et npcap sont installés dans les paramètres de `GRID0-ofw`).<br>
   <img src="img/relay-connection.gif" width="300" alt="Choix de l'adaptateur ZeroTier dans l'onglet des paramètres du relais">
   </details></small>
3. `GRID0-ofw` lancera automatiquement ZeroTier et se connectera au réseau GRID0.
   <small><details><summary>Pour en être sûr :</summary>**Windows :** Cliquez sur la flèche vers le haut en bas à droite de votre écran et faites un clic droit sur l'icône ZeroTier dans la barre des tâches, assurez-vous que le statut est OK dans `8bd5124fd68185ec` GRID0.<br>
   <img src="img/tray-video.gif" width="300" alt="Configuration ZeroTier dans la barre des tâches Windows pour l'app PC et le relais">
   </details></small>
4. `GRID0-ofw` sélectionnera automatiquement le bon adaptateur ZeroTier.
   <small><details><summary>Pour en être sûr :</summary>Dans `GRID0-ofw`, ouvrez **Settings**, le relais se connectera automatiquement à la connexion ZeroTier détectée, mais assurez-vous que la connexion ZeroTier sélectionnée semble être la bonne (elle devrait avoir un nom ressemblant à ZeroTier).<br>
   <img src="img/relay-choose-adapter.gif" width="300" alt="L'onglet des paramètres du relais avec l'adaptateur ZeroTier sélectionné">
   </details></small>

<small><details><summary>Mode automatique (plus simple, Windows uniquement avec capacité hotspot) :</summary>

5. Retournez à la section **Play** de `GRID0-ofw`, assurez-vous que **Automatic (DHCP)** est sélectionné, cliquez sur **Set up PC hotspot** si ce n'est pas déjà fait et cliquez sur **Start relay**.

[image : l'onglet Play du relais avec Automatic (DHCP) sélectionné et Start relay visible]

6. Connectez votre Switch ou Switch 2 au hotspot du PC normalement. Si votre Switch ou Switch 2 était déjà connectée, activez puis désactivez simplement le **Sleep Mode** ou le **Airplane Mode** (les deux fonctionnent).
</details></small>

<small><details><summary>Mode manuel :</summary>

5. Retournez à la section **Play** de `GRID0-ofw`, assurez-vous que **Manual IP settings** est sélectionné, cliquez sur **Start relay** et notez les paramètres IP pour la Switch qu'il vous fournit.
6. Sur votre Switch ou Switch 2, allez dans **Network Settings**, **Change Settings** sur le même réseau que celui auquel votre PC est connecté, et entrez les paramètres IP fournis. (Le DNS primaire peut être **8.8.8.8** et le DNS secondaire peut rester vide).

[image : l'écran des paramètres internet de la Switch avec les champs IP manuelle, sous-réseau, passerelle et DNS remplis]
7. Assurez-vous d'enregistrer et de vous connecter au réseau.
</details></small>

Ensuite, lancez simplement le relais et jouez avec vos amis en LAN !

---

<sub>Conçu par 3 humains avec une assistance limitée de l'IA, testé sur de vraies Switch pendant de nombreux jours et nuits sans repos.
