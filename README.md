# PF Hopper Highlighter / PF Helper

Client-only Fabric helper for Peaceful Farms PF Hoppers and Infinite Storage Barrels.

## Target

- Minecraft **26.1.2**
- Fabric Loader **0.19.3+**
- Fabric API **0.155.2+26.1.2**
- Java **25**

## Features

- Learns PF Hoppers from the placement/removal messages and the `PF Hopper` GUI title.
- Learns Infinite Storage Barrels from the `[PFBarrel] Infinite Barrel placed successfully!` message and the `PFBarrel Storage` GUI title.
- Preserves existing PF Hopper tracking in `config/pf-hopper-highlighter.tsv`.
- Stores tracked Infinite Barrels in `config/pf-barrel-highlighter.tsv`.
- Orange PF Hopper wireframes are centered and **0.25 blocks** wide.
- Cyan Infinite Barrel wireframes are centered and **0.42 blocks** wide.
- Wireframes render through walls with a configurable distance.
- `/pfhelper count` reports how many tracked PF Hoppers are in your current chunk, plus the tracked Infinite Barrel count.
- Client settings persist in `config/pf-helper.properties`.
- Sends no custom packets and requires no server-side mod.

## Commands

```text
/pfhelper status
/pfhelper help
/pfhelper outlines toggle
/pfhelper outlines on
/pfhelper outlines off
/pfhelper hoppers toggle
/pfhelper hoppers on
/pfhelper hoppers off
/pfhelper barrels toggle
/pfhelper barrels on
/pfhelper barrels off
/pfhelper range <8-2048>
/pfhelper count
```

## Build on Fedora

Minecraft 26.1.2 requires Java 25 for development/runtime.

```bash
git pull
chmod +x build.sh
./build.sh
```

The output jar will be:

```text
build/libs/pf-hopper-highlighter-1.1.0.jar
```

## Install in Prism Launcher

1. Make sure the 26.1.2 instance has Fabric Loader and Fabric API installed.
2. Right-click the instance -> **Edit** -> **Mods**.
3. Replace the older PF Hopper Highlighter jar with `pf-hopper-highlighter-1.1.0.jar`.
4. Launch and join Peaceful Farms.

Existing tracked PF Hoppers are retained automatically. Existing Infinite Barrels can be learned by opening them once.
