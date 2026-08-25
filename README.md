# PF Hopper Highlighter

Client-only Fabric mod for Peaceful Farms PF Hoppers.

## Target

- Minecraft **26.1.2**
- Fabric Loader **0.19.3+**
- Fabric API **0.155.2+26.1.2**
- Java **25**

## What it does

- Watches for `Successfully placed a PFHopper.` and records the hopper you just placed.
- Watches for `Successfully remove PFHopper.` and removes the recorded marker.
- Learns existing PF Hoppers when you open them and the container title is `PF Hopper`.
- Saves positions per **server + dimension** in `config/pf-hopper-highlighter.tsv`.
- Draws an **orange 3px outline through walls** within 128 blocks.
- Sends no custom packets and requires nothing on the Peaceful Farms server.

## Build on Fedora

Minecraft 26.1.2 requires Java 25 for mod development/runtime.

Once `java -version` and `javac -version` show Java 25:

```bash
./build.sh
```

`build.sh` downloads a private copy of Gradle 9.5.0 into this project and builds the mod. The output jar will be:

```text
build/libs/pf-hopper-highlighter-1.0.2.jar
```

## Install in Prism Launcher

1. Make sure the 26.1.2 instance has Fabric Loader and Fabric API installed.
2. Right-click the instance -> **Edit** -> **Mods**.
3. Add `pf-hopper-highlighter-1.0.2.jar`.
4. Launch and join Peaceful Farms.

Existing PF Hoppers are learned the first time you open each one. New PF Hoppers should be learned automatically from the placement success message.

## Notes

This mod intentionally uses three independent server-visible/client-visible signals instead of assuming PF Hoppers have unique block NBT. Peaceful Farms presents them to the client as ordinary `minecraft:hopper` blocks, so the server's success messages and the `PF Hopper` GUI title are the useful identifiers.
