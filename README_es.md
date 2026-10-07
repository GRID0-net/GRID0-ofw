![Banner](img/banner.png)

# GRID0 ofw
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.txt)
[![Chat on Discord](https://img.shields.io/badge/Discord-5865f2?logo=discord&logoColor=white)](https://discordapp.com/invite/splatfest)

**Juega juegos de Switch en línea con amigos a través de una LAN virtual.<br>
Versión Original Firm-Ware (ofw) para Switch 1 y 2 sin modificar.**

<sub>Reemplaza los antiguos relays de LAN-play con mejor estabilidad, mayor velocidad y una configuración más fácil.</sub><br>
<sub>Funciona para jugar con usuarios con Switch baneadas y usuarios de emuladores. Fork de switch-lan-play, funciona sobre ZeroTier. Más información en [GRID0](https://github.com/GRID0-net/GRID0).</sub>


## Guía de instalación

Las consolas sin modificar no pueden ejecutar módulos personalizados en segundo plano. `GRID0-ofw` se ejecuta en una PC conectada a la misma red doméstica, capturando y traduciendo los paquetes de LAN-Play automáticamente.

1. Descarga la última versión desde la **[página de versiones](https://github.com/GRID0-net/GRID0-ofw/releases)** y abre `GRID0-ofw`.
2. `GRID0-ofw` instalará automáticamente ZeroTier One y npcap.
   <small><details><summary>Para estar seguro:</summary>Asegúrate de que estén instalados (debería decir que ZeroTier One y npcap están instalados en los ajustes de `GRID0-ofw`).<br>
   <img src="img/relay-connection.gif" width="300" alt="Eligiendo el adaptador ZeroTier en la pestaña de ajustes del relay">
   </details></small>
3. `GRID0-ofw` iniciará automáticamente ZeroTier y se conectará a la red GRID0.
   <small><details><summary>Para estar seguro:</summary>**Windows:** Haz clic en la flecha hacia arriba en la parte inferior derecha de tu pantalla y haz clic derecho en el icono de ZeroTier en la bandeja, asegúrate de que el estado sea OK en `8bd5124fd68185ec` GRID0.<br>
   <img src="img/tray-video.gif" width="300" alt="Configuración de ZeroTier en la bandeja de Windows para la app de PC y el relay">
   </details></small>
4. `GRID0-ofw` seleccionará automáticamente el adaptador ZeroTier correcto.
   <small><details><summary>Para estar seguro:</summary>En `GRID0-ofw`, abre **Settings**, el relay se conectará automáticamente a la conexión ZeroTier detectada, pero asegúrate de que la conexión ZeroTier seleccionada parezca ser la correcta (debería tener un nombre parecido a ZeroTier).<br>
   <img src="img/relay-choose-adapter.gif" width="300" alt="La pestaña de ajustes del relay con el adaptador ZeroTier seleccionado">
   </details></small>

<small><details><summary>Modo automático (más fácil, solo Windows y con capacidad de hotspot):</summary>

5. Vuelve a la sección **Play** de `GRID0-ofw`, asegúrate de que **Automatic (DHCP)** esté seleccionado, haz clic en **Set up PC hotspot** si aún no está configurado y haz clic en **Start relay**.

[image: la pestaña Play del relay con Automatic (DHCP) seleccionado y Start relay visible]

6. Conecta tu Switch o Switch 2 al hotspot de la PC normalmente. Si tu Switch o Switch 2 ya estaba conectada, simplemente activa y desactiva el **Sleep Mode** o el **Airplane Mode** (ambos funcionan).
</details></small>

<small><details><summary>Modo manual:</summary>

5. Vuelve a la sección **Play** de `GRID0-ofw`, asegúrate de que **Manual IP settings** esté seleccionado, haz clic en **Start relay** y fíjate en los ajustes de IP para la Switch que te proporciona.
6. En tu Switch o Switch 2, entra a **Network Settings**, **Change Settings** en la misma red a la que está conectada tu PC, e ingresa los ajustes de IP proporcionados. (El DNS primario puede ser **8.8.8.8** y el DNS secundario puede dejarse en blanco).

[image: la pantalla de ajustes de internet de la Switch con los campos de IP manual, subred, puerta de enlace y DNS completados]
7. Asegúrate de guardar y conectarte a la red.
</details></small>

¡Luego solo inicia el relay y juega con tus amigos por LAN!

---

<sub>Hecho por 3 humanos con asistencia limitada de IA, probado en Switches reales durante muchos días y noches sin descanso.
