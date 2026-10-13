![Logo](https://raw.githubusercontent.com/ChiselsAndBits/Assets/main/Logos/logo.png "Logo")

| [Discord](https://discord.chisels-and-bits.com) | [Wiki](https://wiki.chisels-and-bits.com)  | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/chisels-bits)  | [Releases](https://www.curseforge.com/minecraft/mc-mods/chisels-bits/files) | [Code](https://github.com/ChiselsAndBits/Chisels-and-Bits/) | [Issues](https://github.com/ChiselsAndBits/Chisels-and-Bits/issues) | [Pull Requests](https://github.com/ChiselsAndBits/Chisels-and-Bits/pulls) | [Patreon](https://www.patreon.com/chisels_and_bits) |

### Minecraft mod:
Chisels and Bits is a Minecraft Mod which allows you to cut up the large blocks that make up your world, and place the little cuts back down.
In any way you like.

You want smoother stairs? Go for it.
You want better looking roofs? Or Pillars? It is all possible now.

You can make entirely new looking and feeling blocks by mixing and matching the bits of different already existing blocks.

### Compatibility:
#### Vanilla:
By default, all solid, simple, full cube blocks, like logs, planks, ores, storage blocks (diamond, emerald, iron etc) are supported.
Additionally, some specific blocks are curated by hand, this is especially done for all glass blocks.

#### Mod:
The scan for compatible blocks automatically also detects all mod blocks which are compatible.
If a mod maker wants to control the selection procedure for his or her blocks, he can do so, by adding them to either one of two tags:
- `chiselsandbits:chiselable/forced` to force the block to be chiselable (handy for glass like blocks)
- `chiselsandbits:chiselable/blocked` to prevent a block that is normally chiselable to be chiseled.

### Cross Platform Compatibility:
This project now targets Fabric only.
Common code is still shared through the `core` subproject and loaded by the Fabric module.

### Minefed model update scheduling
The Minecraft 1.20.4 Fabric fork tracks `minefed-1.20.4`. Model update priorities
reuse a camera and age snapshot within each client tick; a new tick recalculates
the pending ranks. New requests, priority promotions and reruns still refresh
their own ranks immediately. Equal priorities preserve queue order. The dynamic
`pollNext(scorer)` API continues to evaluate every pending entry on every call.
This changes scheduling work, without changing model geometry or image quality.

The coalescer regression test drains 2,048 entries in one stable epoch with 2,048
score evaluations, compared with 2,098,176 for repeated full scans. This is a
kernel work count, not an FPS or loading-time estimate: real execution submits
at most eight jobs per pump and refreshes remaining priorities each client tick.
With one, four or eight polls per epoch, the same queue requires 2,098,176,
525,312 or 263,168 score evaluations respectively. One poll per epoch saves no
score evaluations and still incurs heap maintenance; multiple pumps may run in
one client tick. Heap construction is linear in the remaining queue size.
Queue limits and full-queue behavior are unchanged by this optimization.
