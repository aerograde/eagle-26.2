#!/usr/bin/env bash
# Rebuild teavm-core-0.13.1-eagler.jar (Wasm GC codegen and precise dependency patches).
#
# Patches (see scratchpad/wasm-gc-clsnull.md + memory/wasm-gc-port-toolchain.md):
#   1. WasmGCVirtualTableBuilder.setUpInterfaceInHierarchy  -- interface-merge Object
#      root fix (clean variant): exclude java.lang.Object from interfacesAtLevel so
#      Object's concrete vtable is never folded into a trivial marker interface.
#      Clears the whole array-codegen null-virtualTablePointer cascade.
#   2. WasmGCClassGenerator.fillArrayVirtualTableMethods    -- array null-guard
#      (defense-in-depth for the same cascade).
#   3. CoroutineTransformation.generateEpilogue             -- save Fiber locals only
#      while genuinely suspending; normal fallthrough must not append stale frames.
#   4. SuperClassFilter.tryExtract                         -- intersect fully known
#      cached types before domain scans; preserve unknown/no-reduction fallbacks.
#      SuperArrayFilter keeps its previous item-class fallback because its input
#      mask contains array IDs, not the item IDs that superclass extraction uses.
#
# GOTCHA: the published teavm-core SOURCES use com.carrotsearch.hppc.*, but the
# released JAR SHADES hppc to org.teavm.hppc.*. src/*.java already have the rewrite
# (sed com.carrotsearch.hppc -> org.teavm.hppc). Compile against the SHADED
# teavm-relocated-libs-hppc jar, not upstream hppc.
#
# Deploy: target_teavm_spike/build.gradle.kts buildscript{} classpath lists this
# jar FIRST + org.teavm:teavm-core:0.13.1 (patched classes win). JS client stays on
# STOCK teavm-core (its build does not load this patched jar).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
M2="$HOME/.gradle/caches/modules-2/files-2.1/org.teavm"

find_jar() { find "$M2/$1/$2" -name "$1-$2.jar" 2>/dev/null | head -1; }

CORE="$(find_jar teavm-core 0.13.1)"
INTEROP="$(find_jar teavm-interop 0.13.1)"
HPPC="$(find_jar teavm-relocated-libs-hppc 0.13.1)"
ASM="$(find_jar teavm-relocated-libs-asm 0.13.1)"
ASM_TREE="$(find_jar teavm-relocated-libs-asm-tree 0.13.1)"
ASM_COMMONS="$(find_jar teavm-relocated-libs-asm-commons 0.13.1)"
ASM_UTIL="$(find_jar teavm-relocated-libs-asm-util 0.13.1)"
ASM_ANALYSIS="$(find_jar teavm-relocated-libs-asm-analysis 0.13.1)"

CP="$CORE:$INTEROP:$HPPC:$ASM:$ASM_TREE:$ASM_COMMONS:$ASM_UTIL:$ASM_ANALYSIS"

echo "[patch] teavm-core = $CORE"
echo "[patch] hppc (shaded) = $HPPC"

rm -rf "$HERE/classes" "$HERE/build"
mkdir -p "$HERE/classes" "$HERE/build"

echo "[patch] compiling precise-link memory patcher (--release 11)..."
mkdir -p "$HERE/build/patcher"
javac --release 11 -nowarn -cp "$ASM" -d "$HERE/build/patcher" \
  "$HERE/PatchDependencyAnalyzer.java"
java -cp "$HERE/build/patcher:$ASM" PatchDependencyAnalyzer "$CORE" \
  "$HERE/classes/org/teavm/dependency/DependencyAnalyzer.class"
javac --release 11 -nowarn -cp "$HPPC" -d "$HERE/classes" \
  "$HERE/CompactIntHashSet.java"

echo "[patch] compiling patched sources (--release 11)..."
javac --release 11 -nowarn -cp "$CP" -d "$HERE/classes" \
  "$HERE/src/SuperClassFilter.java" \
  "$HERE/src/SuperArrayFilter.java" \
  "$HERE/src/WasmGCVirtualTableBuilder.java" \
  "$HERE/src/WasmGCClassGenerator.java" \
  "$HERE/src/BaseWasmGenerationVisitor.java" \
  "$HERE/src/WasmGCGenerationVisitor.java" \
  "$HERE/src/WasmGCBranchTypeRepair.java" \
  "$HERE/src/WasmGCInitFunctionSplitter.java" \
  "$HERE/src/ActiveDataOffsetEncodingRepair.java" \
  "$HERE/src/WasmGCTarget.java" \
  "$HERE/src/WasmGCVariableCategoryProvider.java" \
  "$HERE/src/WasmGCMethodGenerator.java" \
  "$HERE/src/WasmTypeInference.java" \
  "$HERE/src/CoroutineTransformation.java" \
  "$HERE/src/Fiber.java"

echo "[patch] compiled classes:"
find "$HERE/classes" -name '*.class' | sed "s#$HERE/classes/##" | sort

# Overlay the recompiled classes into a copy of the stock jar.
OUT="$HERE/teavm-core-0.13.1-eagler.jar"
cp "$CORE" "$OUT"
( cd "$HERE/classes" && jar uf "$OUT" $(find . -name '*.class' | sed 's#^\./##') )

echo "[patch] wrote $OUT"
ls -la "$OUT"

# BLOCKER #15: also emit a Fiber-only override jar for the wasm-gc PROGRAM classpath. TeaVM reads
# runtime classes (org.teavm.runtime.Fiber) from the program/teavm-runtime classpath, NOT the buildscript
# classloader, so the patched Fiber in $OUT above is ignored for the emitted wasm. This small jar goes on
# the client's program classpath (implementation(files(...)) in target_teavm_wasm_gc/build.gradle.kts),
# read before the stock teavm runtime jars (same override mechanism teavm-compat facades use).
FIBEROUT="$HERE/fiber-override.jar"
rm -f "$FIBEROUT"
( cd "$HERE/classes" && jar cf "$FIBEROUT" org/teavm/runtime/Fiber.class org/teavm/runtime/Fiber\$*.class )
echo "[patch] wrote $FIBEROUT (Fiber override for the program classpath):"
jar tf "$FIBEROUT" | grep Fiber
if javap -classpath "$FIBEROUT" -p -c org.teavm.runtime.Fiber \
    | grep -q 'java/util/Arrays.copyOf'; then
  echo "[patch] ERROR: Fiber stack growth calls Arrays.copyOf and can recursively re-enter reversePush" >&2
  exit 1
fi
echo "[patch] verified Fiber stack growth has no coroutine-recursive Arrays.copyOf call"

# Sanity: the two guards must be present in the overlaid jar.
echo "[patch] verify Table.merge is STOCK (no Object guard, interface-filter fix is elsewhere):"
( cd "$HERE/classes" && javap -p -c 'org.teavm.backend.wasm.gc.vtable.WasmGCVirtualTableBuilder$Table' \
    2>/dev/null | grep -A6 'void merge' | head -8 ) || true
echo "[patch] verify fillArrayVirtualTableMethods null-guard (ifnull/ifnonnull -> return):"
( cd "$HERE/classes" && javap -p -c 'org.teavm.backend.wasm.generate.gc.classes.WasmGCClassGenerator' \
    2>/dev/null | grep -A8 'ValueType, java.util.List.*WasmGlobal, org.teavm.backend.wasm.model.WasmGlobal' \
    | head -10 ) || true

# ======================================================================================
# Patched teavm-jso-impl (Increment A): make @JSByRef marshal as a COPY on wasm-gc instead
# of erroring ("... @JSByRef, which is not supported in Wasm GC"). Wasm-gc-only branch in
# JSClassProcessor -> the JS build (stock jso-impl) is byte-identical. NOTE: copy drops
# write-through; callers that read data BACK through a @JSByRef view need explicit copy-back.
JSOIMPL="$(find_jar teavm-jso-impl 0.13.1)"
JSO="$(find_jar teavm-jso 0.13.1)"
RHINO="$(find_jar teavm-relocated-libs-rhino 0.13.1)"
echo "[patch] teavm-jso-impl = $JSOIMPL"
echo "[patch] rhino (shaded org.teavm.rhino) = $RHINO"
# GOTCHA (same as hppc): the jso-impl SOURCE imports org.mozilla.javascript.* but the released
# rhino jar SHADES it to org.teavm.rhino.javascript.* -- src-jso/JSClassProcessor.java has the
# sed-rewrite baked in, and we compile against teavm-relocated-libs-rhino.
if [ -n "$JSOIMPL" ] && [ -f "$HERE/src-jso/JSClassProcessor.java" ]; then
  rm -rf "$HERE/classes-jso"; mkdir -p "$HERE/classes-jso"
  JSOCP="$CORE:$INTEROP:$JSO:$JSOIMPL:$RHINO:$HPPC:$ASM:$ASM_TREE:$ASM_COMMONS:$ASM_UTIL:$ASM_ANALYSIS"
  echo "[patch] compiling patched jso-impl sources (--release 11)..."
  javac --release 11 -nowarn -cp "$JSOCP" -d "$HERE/classes-jso" "$HERE/src-jso/JSClassProcessor.java"
  OUTJSO="$HERE/teavm-jso-impl-0.13.1-eagler.jar"
  cp "$JSOIMPL" "$OUTJSO"
  ( cd "$HERE/classes-jso" && jar uf "$OUTJSO" $(find . -name '*.class' | sed 's#^\./##') )
  echo "[patch] wrote $OUTJSO"; ls -la "$OUTJSO"
else
  echo "[patch] WARNING: jso-impl jar or src-jso not found; skipping jso-impl patch"
fi

# ======================================================================================
# Patched teavm-classlib runtime class TJSBufferHelper (HOLISTIC BUFFER FIX #18/#19/#20):
# make getArrayBufferView COPY a non-provider HEAP ByteBuffer (TByteBufferImpl, plain byte[])
# into a fresh Int8Array at the GL upload boundary instead of throwing. This lets GL uploads
# work with the HEAP buffers we reverted to (BufferUtils/EaglerFakeHeap), while the font
# channel.read path also uses those heap buffers. Emitted as a small OVERRIDE jar for the
# wasm-gc PROGRAM classpath -- TeaVM reads runtime/classlib classes from the program classpath
# (renaming org.teavm.classlib.java.nio.TJSBufferHelper -> java.nio.JSBufferHelper), same
# mechanism as fiber-override.jar and the teavm-compat facades. JS client unaffected (its build
# never includes this jar). No shaded imports in TJSBufferHelper (all real org.teavm.* pkgs) ->
# no sed-rewrite needed. Same-package source can reference the package-private TByteBufferImpl /
# TArrayBufferViewProvider / TBufferFinalizationRegistry from the classlib jar.
CLASSLIB="$(find_jar teavm-classlib 0.13.1)"
JSOAPIS="$(find_jar teavm-jso-apis 0.13.1)"
echo "[patch] teavm-classlib = $CLASSLIB"
echo "[patch] teavm-jso-apis = $JSOAPIS"
if [ -n "$CLASSLIB" ] && [ -f "$HERE/src-classlib/TJSBufferHelper.java" ]; then
  rm -rf "$HERE/classes-classlib"; mkdir -p "$HERE/classes-classlib"
  CLCP="$CLASSLIB:$JSO:$JSOAPIS:$INTEROP:$CORE"
  echo "[patch] compiling patched classlib sources (--release 11)..."
  # TByteBufferImpl (BLOCKER #20 root fix): stock TeaVM 0.13 getLong(int index) wrongly did position+=8, so
  # PngInfo.validateHeader's absolute getLong(0) skipped the PNG signature -> "not a PNG". wasm-gc-only impl
  # (JS uses TByteBufferJsImpl) -> JS byte-identical.
  # TSimpleStreamImpl (BLOCKER #23 fix): stock toArray() trusted estimateSize() as a hard array
  # bound, but a fastutil LongOpenHashSet spliterator under-reports it -> ArrayFillingConsumer
  # overflowed (AIOOBE) in PlayerChunkSender.collectChunksToSend on the first server tick after a
  # player joins. Patched copy grows the fill array. wasm-gc program-classpath only -> JS byte-identical.
  # TZipFile: derive payload offset from local lengths, validate local header/data bounds.
  javac --release 11 -nowarn -cp "$CLCP" -d "$HERE/classes-classlib" \
      "$HERE/src-classlib/TJSBufferHelper.java" \
      "$HERE/src-classlib/TByteBufferImpl.java" \
      "$HERE/src-classlib/TSimpleStreamImpl.java" \
      "$HERE/src-classlib/TURI.java" \
      "$HERE/src-classlib/TZipFile.java"
  OUTCL="$HERE/classlib-override.jar"
  rm -f "$OUTCL"
  ( cd "$HERE/classes-classlib" && jar cf "$OUTCL" \
      org/teavm/classlib/java/nio/TJSBufferHelper.class \
      $(find org/teavm/classlib/java/nio -name 'TJSBufferHelper$*.class') \
      org/teavm/classlib/java/nio/TByteBufferImpl.class \
      $(find org/teavm/classlib/java/nio -name 'TByteBufferImpl$*.class') \
      org/teavm/classlib/java/util/stream/impl/TSimpleStreamImpl.class \
      $(find org/teavm/classlib/java/util/stream/impl -name 'TSimpleStreamImpl$*.class') \
      org/teavm/classlib/java/util/zip/TZipFile.class \
      $(find org/teavm/classlib/java/util/zip -name 'TZipFile$*.class') )
  echo "[patch] wrote $OUTCL (classlib overrides for the program classpath):"
  jar tf "$OUTCL" | grep -E 'TJSBufferHelper|TByteBufferImpl|TSimpleStreamImpl|TZipFile'
  URIOUT="$HERE/uri-classlib-override.jar"
  rm -f "$URIOUT"
  ( cd "$HERE/classes-classlib" && jar cf "$URIOUT" \
      org/teavm/classlib/java/net/TURI.class \
      $(find org/teavm/classlib/java/net -name 'TURI$*.class') )
  echo "[patch] wrote $URIOUT (URI parser override for all browser targets):"
  jar tf "$URIOUT" | grep TURI
else
  echo "[patch] WARNING: classlib jar or src-classlib not found; skipping classlib patch"
fi
echo "[patch] DONE"
