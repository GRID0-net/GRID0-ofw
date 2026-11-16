# GRID0 for iOS

This folder is the iOS version of the GRID0 app: the same app as the Android
one, same screens and same proxy behaviour, ported to Swift. It is Proxy mode
only, like the phone apps. The relay modes stay on the PC app.

Status: it builds clean in CI and packages into an IPA, but it has never run
on a real iPhone yet. The first device test decides whether it works.

## For Donut: testing this

1. Get the IPA. Go to the Actions tab, open the "Build iOS" workflow, and
   either open the latest run or press "Run workflow" yourself. Download the
   `grid0-ios-ipa` artifact from the run. Inside is `GRID0.ofw.v3.3.0.ipa`.
2. Install it with Sideloadly or AltStore using your Apple ID. There is no
   developer account on this project, so a free install expires after 7 days
   and needs a refresh.
3. Join the GRID0 network in the ZeroTier iOS app first, then open GRID0 and
   allow Local Network access when it asks.
4. The three steps should check off in order. Press Start Proxy.
5. On the Switch, open Splatoon 3 or Mario Kart 8 Deluxe and search for a
   LAN room. Watch the app: the "Switch detected" pill should appear, the
   console log on the MORE tab should show traffic, and Current Rooms should
   list any open room.

The make or break check is step 5. If the log shows no browse requests from
the Switch at all while the Switch is searching, iOS is blocking broadcast
traffic for this app. That is an Apple entitlement problem, not a code
problem: receiving broadcast needs the multicast entitlement, and getting it
needs a paid developer account. Everything else can be fixed in code.

## What is where

- `GRID0/App/` is the app itself: PLAY and MORE screens, the steps, Current
  Rooms, the ZeroTier button, the console log and share capture.
- `GRID0/Proxy/` is the proxy, a function by function port of
  `zttestproxy.py`. `Relay.swift`, `RelayHandlers.swift` and `RelayGame.swift`
  are the Relay class, `ProxyCore.swift` is the packet and crypto helpers,
  `Crypto.swift` is AES, GCM and HMAC, `Zstd.swift` wraps the zstd library in
  `GRID0/zstd/`.
- The events and capture files use the same formats as the other apps, so a
  capture from iOS can be read with the same tools.

## Known gaps in this first build

- The proxy only runs while the app is open with the screen awake. True
  background running needs a Network Extension and is not built.
- There is no in-app updater. iOS does not allow one. New builds come from
  CI and get sideloaded again.
- The ZeroTier button tries to open the ZeroTier app. If its URL schemes do
  not answer, the button falls back to the App Store page.
- Client Isolation on the MORE tab is a guess based on whether the proxy can
  see the Switch. It is not the measured check the Android app does.

## Releasing

The `build-ios` workflow is set up to attach the IPA to a release, but the
release trigger is commented out in the workflow file on purpose, so normal
releases do not ship the iOS build yet. When 3.3.0 is ready to go out,
uncomment that block.
