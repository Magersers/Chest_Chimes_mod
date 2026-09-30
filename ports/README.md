# Fabric and NeoForge ports

Independent Gradle projects keep loader-specific lifecycle, networking, persistent data and audio integration explicit. The original Forge 1.20.1 project stays at the repository root.

| Project | JDK | Verified loader/API |
| --- | --- | --- |
| fabric-1.20.1 | 17 | Fabric Loader 0.16.14, Fabric API 0.92.12+1.20.1 |
| fabric-1.21.1 | 21 | Fabric Loader 0.16.14, Fabric API 0.116.17+1.21.1 |
| neoforge-1.21.1 | 21 | NeoForge 21.1.252 |

From the repository root (use gradlew.bat on Windows):

```sh
./gradlew -p ports/fabric-1.20.1 build runGameTestServer
./gradlew -p ports/fabric-1.21.1 build runGameTestServer
./gradlew -p ports/neoforge-1.21.1 build runGameTestServer
```

Select the matching JDK before each command. Distributables are in each project's build/libs folder. For Fabric, use the normal JAR, not the development JAR. All include the JLayer MP3 decoder and license notices. Install Fabric API separately for Fabric.

Each project runs 18 audio/storage unit tests. Fabric runs 9 dedicated-server integration tests; NeoForge runs 13, including last-viewer closing for regular, trapped, double and ender chests. Fabric tests cover persistence, identity replacement, double chests, upload authorization, shared edits, private migration and server-side mixins. See ../docs/TESTING.md for manual GUI/audio scenarios.

Use matching loader/Minecraft/mod versions on clients and server. Cross-loader networking and moving worlds between Minecraft versions are not supported compatibility promises.
