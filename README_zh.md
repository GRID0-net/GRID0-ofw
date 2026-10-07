![Banner](img/banner.png)

# GRID0 ofw
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE.txt)
[![Chat on Discord](https://img.shields.io/badge/Discord-5865f2?logo=discord&logoColor=white)](https://discordapp.com/invite/splatfest)

**通过虚拟局域网与朋友在线玩 Switch 游戏。<br>
适用于未改装的 Switch 1 和 Switch 2 的原厂固件 (ofw) 版本。**

<sub>以更好的稳定性、更快的速度和更简单的设置取代旧的 LAN-play 中继。</sub><br>
<sub>支持与被封禁的 Switch 用户和模拟器用户跨平台联机。switch-lan-play 的分支，基于 ZeroTier 运行。更多信息请访问 [GRID0](https://github.com/GRID0-net/GRID0)。</sub>


## 设置指南

未改装的主机无法在后台运行自定义模块。`GRID0-ofw` 运行在连接到同一家庭网络的 PC 上，自动捕获并转换 LAN-Play 数据包。

1. 从**[版本发布页面](https://github.com/GRID0-net/GRID0-ofw/releases)**下载最新版本并打开 `GRID0-ofw`。
2. `GRID0-ofw` 会自动安装 ZeroTier One 和 npcap。
   <small><details><summary>确认一下：</summary>确保它们已安装（`GRID0-ofw` 的设置中应显示 ZeroTier One 和 npcap 已安装）。<br>
   <img src="img/relay-connection.gif" width="300" alt="在中继的 Settings 选项卡中选择 ZeroTier 适配器">
   </details></small>
3. `GRID0-ofw` 会自动启动 ZeroTier 并连接到 GRID0 网络。
   <small><details><summary>确认一下：</summary>**Windows：** 点击屏幕右下角的向上箭头，右键点击 ZeroTier 托盘图标，确保在 `8bd5124fd68185ec` GRID0 中的状态为 OK。<br>
   <img src="img/tray-video.gif" width="300" alt="PC 应用和中继的 Windows 托盘 ZeroTier 配置">
   </details></small>
4. `GRID0-ofw` 会自动选择正确的 ZeroTier 适配器。
   <small><details><summary>确认一下：</summary>在 `GRID0-ofw` 中打开 **Settings**，中继会自动连接到检测到的 ZeroTier 连接，但请确保所选的 ZeroTier 连接看起来是正确的（名称应类似于 ZeroTier）。<br>
   <img src="img/relay-choose-adapter.gif" width="300" alt="已选择 ZeroTier 适配器的中继 Settings 选项卡">
   </details></small>

<small><details><summary>自动模式（更简单，仅限 Windows 且支持热点）：</summary>

5. 回到 `GRID0-ofw` 的 **Play** 部分，确保已选择 **Automatic (DHCP)**，如果尚未设置 PC 热点，请点击 **Set up PC hotspot**，然后点击 **Start relay**。

[image: 中继的 Play 选项卡，已选择 Automatic (DHCP)，可见 Start relay]

6. 将 Switch 或 Switch 2 正常连接到 PC 热点。如果 Switch 或 Switch 2 已经连接，只需开关一下 **Sleep Mode** 或 **Airplane Mode**（两种都可以）。
</details></small>

<small><details><summary>手动模式：</summary>

5. 回到 `GRID0-ofw` 的 **Play** 部分，确保已选择 **Manual IP settings**，点击 **Start relay**，并记下它为 Switch 提供的 IP 设置。
6. 在 Switch 或 Switch 2 上，进入 **Network Settings**，在与 PC 相同的网络上选择 **Change Settings**，然后输入所提供的 IP 设置。（首选 DNS 可设为 **8.8.8.8**，备用 DNS 可留空）。

[image: Switch 网络设置界面，已填写手动 IP、子网掩码、网关和 DNS]
7. 确保保存并连接到该网络。
</details></small>

然后只需启动中继，即可通过 LAN 与朋友一起玩！

---

<sub>由 3 位人类在有限的 AI 协助下构建，在真实的 Switch 上经过多个日夜的测试。
