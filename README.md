# GRID0 Relay
![License](https://img.shields.io/badge/License-GPLv2-blue.svg)
[![Chat on Discord](https://img.shields.io/badge/Discord-5865f2?logo=discord&logoColor=white)](https://discordapp.com/invite/splatfest)
=====

Fork of Switch-lan-play, meant to be used in conjuction with ZeroTier as a modern replacement to the old lan play relay servers
resulting in much more stability across the board, way faster speeds, and also a way for unmodded switches to play online with either 
[sys-zerotier](https://github.com/redluigi323/sys-zerotier) users (wonder who made that lol) or emulator users!

Currently has support for Windows pc's, Macs (Apple Silicon and Intel), and Linux as an AppImage.


Theres both a cli you can use, and a Native QT gui with either macOS theming or Windows 11 theming.


simple usage guide:

1. Download the latest release from [releases](https://github.com/redluigi323/GRID0-relay/releases).
   Want the newest code instead? The **Nightly build** prerelease on that same page is rebuilt
   automatically every time main changes, for Windows, both kinds of Mac, and Linux.
2. Run it by either running GRID0Relay.exe(Windows), GRID0 Relay.app(macOS), or the AppImage(Linux) (you will be prompted for admin perms when necessary).
3. If you dont have Zerotier and Npcap installed, the app tells you on startup and offers to install them. On Linux, install ZeroTier and your distro's libpcap package (libpcap0.8 on Debian/Ubuntu, libpcap on Fedora/Arch).
4. Join a network on ZeroTier's ui.
5. Setup the adapters (will be automatically chosen if both are detected)
6. Input the ip settings on your switch


Then just start the relay and play with your friends over lan!
=====

<sub>Built by 3 humans with limited AI assistance, tested on real Switches over many days and restless nights. A fork of switch-lan-play, networking via ZeroTier.<sub>
