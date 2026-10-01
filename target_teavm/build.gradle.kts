plugins {
	id("java")
	id("org.teavm") version "0.13.0"
}

// Eagler method: the real logging stack never ships to the web target.
// slf4j-api / log4j2 / com.mojang:logging are replaced by the facades in
// :teavm-compat (org.slf4j.*, com.mojang.logging.*, netty factory shadow) —
// log4j-core's init dies on TeaVM's regex engine (\P{InBasic_Latin}).
// (Replicated verbatim from target_teavm_spike/build.gradle.kts.)
configurations.configureEach {
	exclude(group = "org.slf4j")
	exclude(group = "org.apache.logging.log4j")
	exclude(group = "com.mojang", module = "logging")
	// Phase 3.3b: real LWJGL never ships to the web target — same-FQN linkage
	// stubs in :platform-teavm (org.lwjgl.glfw/system/stb/openal/freetype/tinyfd/
	// vulkan) replace it; excluding the jars makes any missed stub a clear
	// "Class ... was not found" instead of a native-method failure.
	exclude(group = "org.lwjgl")
	// Phase 3.3b: real icu4j never ships to the web target. Its NumberFormat /
	// BreakIterator subclasses extend java.text.Format, so String.format's
	// polymorphic dispatch sweeps the whole ICU machinery into the reachable
	// graph; ICU's NumberFormat.getShim() then does an unconstrained
	// Class.newInstance() that drags in every no-arg-constructable class on the
	// path (netty NIO/epoll channels, blaze3d MonitorManager, ...), each reaching
	// unimplemented JDK APIs — and the resulting graph exhausts the TeaVM
	// optimizer's heap (OOM at generateJavaScript). :game only touches ~11 ICU
	// classes (Bidi/ArabicShaping/UCharacter for RTL font shaping, Collator for
	// biome-list sort, DateFormat/Calendar/TimeZone/ULocale for the clock item) —
	// all supplied as real-FQN facades in :teavm-compat (com.ibm.icu.*), none of
	// which extend java.text.Format, and none invoked before the title screen.
	exclude(group = "com.ibm.icu")
	// Phase 3.3e: com.mojang:text2speech probes native OS text-to-speech (SAPI/
	// NSSpeech/speech-dispatcher via JNI) that don't exist in the browser — its
	// getNarrator() logs "Error while loading the narrator" every boot. Excluded
	// here; a same-FQN no-op facade in :teavm-compat (com.mojang.text2speech.Narrator)
	// returns a silent EMPTY narrator instead.
	exclude(group = "com.mojang", module = "text2speech")
}

// Phase 3.2a web target (docs/phase3-web-toolchain-map.md increment 3.2). The
// entry point is net.lax1dude...internal.teavm.MainClass -> ClientMain._main:
// reads window.eaglercraftXOpts, boots EagRuntime (browser PlatformRuntime),
// downloads assets.epk, wires the log stream + crash overlay, then presents a
// black canvas ("eagler: web boot OK"). Minecraft client start is Phase 3.3.
//
// Dependency ORDER is load-bearing: :platform-teavm must precede :platform so
// its net.lax1dude...internal.PlatformRuntime (+ PlatformApplication/Assets/...)
// shadows the :platform native-stub set by FQN on the TeaVM classpath — exactly
// how :target_lwjgl_desktop lets :platform-lwjgl shadow the stubs on desktop.
dependencies {
	implementation(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/uri-classlib-override.jar")))
	implementation(project(":platform-teavm"))
	implementation(project(":platform"))
	implementation(project(":game"))
	// Phase 3.0 classlib-compat: missing-JDK shims + TeaVM plugin (see teavm-compat/)
	// Also provides the org.slf4j / com.mojang.logging facades the log exclusion
	// above removes, and the setEaglerRedirector hook platform-teavm calls.
	implementation(project(":teavm-compat"))
	implementation(teavm.libs.jso)
	implementation(teavm.libs.jsoApis)
	// :game uses implementation scope; these are needed to typecheck the entry graph
	compileOnly("com.mojang:datafixerupper:10.0.21")
}

teavm {
	all {
		mainClass = "net.lax1dude.eaglercraft.v1_8.internal.teavm.MainClass"
	}
	js {
		targetFileName = "classes.js"
		// Size pass (Matej directive 2026-07-05): obfuscated=true minifies identifiers
		// (~40-50% smaller classes.js + big parse-RAM cut). Costs roughly 2x link time.
		// FLIPPED ON 2026-07-11 (c82+): the RAM profile measured 392MB source string +
		// 137MB code = 42% of the JS heap, and each mesh worker parses its own copy —
		// minification is both the RAM fix and the worker-boot fix. Flip back to false
		// temporarily when a bug needs grep-able generated JS (deploy patcher is
		// obfuscation-safe — structural regex).
		obfuscated = true
		sourceMap = false
	}
}
