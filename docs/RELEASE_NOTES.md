# Chest Chimes 1.2.0 — Fabric and NeoForge

Three new builds bring custom chest-opening sounds to:

- Fabric / Minecraft 1.20.1 / Java 17
- Fabric / Minecraft 1.21.1 / Java 21
- NeoForge / Minecraft 1.21.1 / Java 21

Fabric requires Fabric API. The existing Forge 1.20.1 build remains at version 1.1.2 and is included for convenience.

All ports preserve shared and personal sounds, WAV/OGG/MP3 imports, trimming, preview, per-sound volume, chest identity and the original Minecraft closing sound. English and Russian interfaces are included.

Install the matching build on both the server and every client. Do not install multiple loader variants together. MP3 decoder included; MIT license for mod code.

Validation: 18 unit tests per new build, 9 dedicated-server integration tests per Fabric build and 13 for NeoForge. The Forge build retains its existing 18 unit and 13 integration tests. Automated tests do not replace manual checks of OS file dialogs and audible playback.
