# Item model cache budget

The item model cache retains the existing full `ItemStack.save(new CompoundTag())`
key and FIFO policy. Hits and replacement of an existing value do not promote a
key. Nested NBT changes still produce a different detached snapshot. The model
build body, model combination order, quads, UVs, lighting and random queries are
unchanged.

The existing `performance.caches.sizes.stack-models` count limit remains in effect
(default 100). `performance.caches.memory.stack-models-estimated-bytes` adds a
second, insertion-time budget with a default of 64 MiB. This permits the existing
100-entry working set when models average below roughly 655 KiB while limiting
retention of unusually fragmented models. The default is a bounded policy choice,
not a measured optimum or GPU/VRAM limit. Zero for either limit disables retention.
Like the prior dynamic count policy, changed limits take effect on the next cache
insertion/replacement; hits preserve their existing lifetime and insertion order.

The estimate includes Minecraft's `CompoundTag.sizeInBytes()` estimate, fixed cache
entry/model overhead, stored model arrays, conservative ArrayList backing capacity,
quad objects and vertex integer arrays. The weighting path reads stored data only:
it never calls `getQuads`, advances model random sources or queries textures. It
may overcount shared quads/models. Shared atlas textures, native buffers and JVM
implementation details are excluded. This is a model-owned-array/NBT weight budget,
not an exact heap bound; the separate block model manager and other caches can
retain the same models independently.

An entry exceeding the entire budget is returned normally but not retained, and
does not displace smaller entries. Unknown model types and overflowed estimates
also bypass retention. Their `Long.MAX_VALUE` sentinel is excluded even if a custom
configuration requests that budget. Building an uncached large model again can cost
CPU time; raise the budget if that working set is intentionally needed.

Insertion, replacement, FIFO eviction and clear update weight accounting atomically.
Model suppliers and weighing run outside the mutation lock. Resource reload and the
existing client cache-clear action now clear the static item model cache too. A
generation check prevents a build started before clear from repopulating it; the
original caller still receives its result. Failed/null suppliers or invalid weights
do not change resident entries.

Run `./gradlew :core:test :fabric:remapJar` using the existing Java 17 build and
Java 21 access-transformer toolchain. The tests execute the production weighted
cache, DataAware cache factory, ChiseledBlockBakedModel, CombinedModel, real NBT and
BakedQuad data. Registry/configuration boundaries are test fixtures; no Minecraft
client or OpenGL context starts. They cover FIFO/count/weight/oversized entries,
nested mutation, reload/clear during a build, simultaneous insertion/clear, exception
and null contracts, packed quad data and the frozen pre-change item model build body.
