import org.teavm.gradle.api.OptimizationLevel
import org.teavm.gradle.api.WasmDebugInfoLevel
import org.teavm.gradle.api.WasmDebugInfoLocation

// === wasm-gc patched-teavm-core override (SPIKE ONLY) ===============================
// The Bootstrap-scale wasm-gc graph trips stock TeaVM 0.13 wasm-gc codegen bugs (see
// scratchpad/wasm-gc-clsnull.md). We deploy a patched teavm-core via a buildscript
// classpath override: the patched jar is listed FIRST so its wasm-gc codegen classes
// (WasmGCVirtualTableBuilder interface-merge Object fix + WasmGCClassGenerator array
// null-guard) win parent-first over the stock teavm-core the org.teavm plugin pulls in.
// The JS client link (:target_teavm) is UNAFFECTED — it uses stock teavm-core and stays
// byte-identical (these patches are wasm-gc-codegen only). Rebuild the jar with
// scratchpad/teavm-eagler-patch/build-patched-core.sh.
buildscript {
	repositories {
		mavenCentral()
	}
	dependencies {
		classpath(files(System.getenv("EAGLER_TEAVM_CORE_JAR")
			?: rootProject.file("wasm-toolchain/teavm-eagler-patch/teavm-core-0.13.1-eagler.jar")))
		classpath(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/teavm-jso-impl-0.13.1-eagler.jar")))
		classpath("org.teavm:teavm-core:0.13.1")
		classpath("org.teavm:teavm-jso-impl:0.13.1")
	}
}

plugins {
	id("java")
	id("org.teavm") version "0.13.1"
}

// Eagler method: the real logging stack never ships to the web target.
// slf4j-api / log4j2 / com.mojang:logging are replaced by the facades in
// :teavm-compat (org.slf4j.*, com.mojang.logging.*, netty factory shadow) —
// log4j-core's init dies on TeaVM's regex engine (\P{InBasic_Latin}).
configurations.configureEach {
	exclude(group = "org.slf4j")
	exclude(group = "org.apache.logging.log4j")
	exclude(group = "com.mojang", module = "logging")
	// mirror target_teavm: real LWJGL/icu4j/text2speech never ship to web; same-FQN
	// stubs/facades in :platform-teavm / :teavm-compat replace them.
	exclude(group = "org.lwjgl")
	exclude(group = "com.ibm.icu")
	exclude(group = "com.mojang", module = "text2speech")
}

dependencies {
	implementation(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/uri-classlib-override.jar")))
	implementation(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/classlib-override.jar")))
	// BLOCKER #17: the patched org.teavm.runtime.Fiber (suspend runs the paired async body with the
	// fiber RUNNING, not SUSPENDING) must be emitted INTO the wasm. TeaVM reads runtime classes from
	// the PROGRAM classpath, so the override jar must be listed FIRST (read before the stock teavm
	// runtime jars). Mirrors target_teavm_wasm_gc (the client). wasm-gc-only -> js byte-identical.
	implementation(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/fiber-override.jar")))
	// Dependency ORDER is load-bearing: :platform-teavm must precede :platform so its
	// net.lax1dude...internal.PlatformRuntime (concrete getPlatformType() etc.) shadows
	// the :platform native-stub set by FQN on the TeaVM classpath. Without it the
	// Bootstrap graph hits an unresolved-native link error (getPlatformType is native
	// in :platform) which wasm-gc rejects. (See scratchpad/wasm-gc-clsnull.md blocker #5.)
	implementation(project(":platform-teavm"))
	implementation(project(":platform"))
	implementation(project(":game"))
	// Phase 3.0 classlib-compat: missing-JDK shims + TeaVM plugin (see teavm-compat/)
	implementation(project(":teavm-compat"))
	implementation(teavm.libs.jso)
	implementation(teavm.libs.jsoApis)
	// :game uses implementation scope; these are needed to typecheck the spike entry
	compileOnly("com.mojang:datafixerupper:10.0.21")
	compileOnly("io.netty:netty-buffer:4.2.15.Final")
	compileOnly("io.netty:netty-common:4.2.15.Final")
	compileOnly("io.netty:netty-transport:4.2.15.Final")
}

teavm {
	all {
		mainClass = providers.gradleProperty("eaglerSpikeMain")
			.orElse("net.lax1dude.eaglercraft.v1_8.spike.SpikeMain").get()
	}
	js {
		targetFileName = "classes.js"
		// readable stack traces while chasing runtime failures; flip back for size
		obfuscated = false
		sourceMap = false
	}
	wasmGC {
		targetFileName = "classes.wasm"
		// Lean run build (blocker #6 branch-typing bug is fixed in the patched teavm-core).
		// obfuscated=false keeps names so fn-probe-named.mjs can attribute the big functions.
		obfuscated = false
		debugInformation = false
		// EXPERIMENT 1 (#7) RESULT: AGGRESSIVE optimization left local counts byte-identical
		// (Blocks::<clinit> still 924) — reverted. The blocker is wasm-gc TYPED locals: disjoint
		// ref temps ((ref $AirBlock) vs (ref $LiquidBlock)) can't coalesce into one typed local.
		// optimization = OptimizationLevel.AGGRESSIVE  // no effect on local count
	}
}
