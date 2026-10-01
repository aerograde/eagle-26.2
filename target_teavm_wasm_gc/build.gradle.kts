import java.io.File
import java.util.Properties

// === EaglercraftX 26.2 wasm-gc client target (foundation module) ===================
//
// The durable home for the native-i64 wasm-gc port (chunk-gen ~19-48x faster than JS).
// Mirrors :target_teavm (the JS client) but emits wasm-gc via a PATCHED teavm-core that
// fixes the stock TeaVM 0.13 wasm-gc backend bugs that a Bootstrap-scale graph triggers
// (see wasm-toolchain/ + scratchpad/wasm-gc-clsnull.md + memory wasm-gc-port-toolchain.md):
//   - WasmGCVirtualTableBuilder: interface-merge Object root fix (array-codegen cascade)
//   - WasmGCClassGenerator: array virtual-table null-guard
//   - BaseWasmGenerationVisitor.visit(ConditionalExpr) + WasmGCBranchTypeRepair: the
//     branch/PHI "too-general ref" fix (block-result value must be a subtype of the block
//     type; TeaVM missed the downcast on widened coalesced-register values)
// The JS client (:target_teavm) stays on STOCK teavm-core and byte-identical (patches are
// wasm-gc-codegen only). Rebuild the patched jar: wasm-toolchain/teavm-eagler-patch/build-patched-core.sh
buildscript {
	repositories {
		mavenCentral()
	}
	dependencies {
		// Patched teavm-core + teavm-jso-impl FIRST so their wasm-gc-only codegen/JSO-processing
		// classes win parent-first over the stock jars the org.teavm plugin pulls transitively.
		// (teavm-jso-impl patch: @JSByRef marshals as a COPY on wasm-gc instead of erroring.)
		classpath(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/teavm-core-0.13.1-eagler.jar")))
		classpath(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/teavm-jso-impl-0.13.1-eagler.jar")))
		classpath("org.teavm:teavm-core:0.13.1")
		// stock jso-impl coordinate pulls its transitives (teavm-jso=JSClass, relocated rhino) into
		// the SAME (buildscript) classloader as the patched jso-impl jar above, so the patched
		// JSClassProcessor resolves them (else NoClassDefFoundError: org/teavm/jso/JSClass). The
		// patched jar is listed FIRST, so its classes win parent-first.
		classpath("org.teavm:teavm-jso-impl:0.13.1")
	}
}

plugins {
	id("java")
	id("org.teavm") version "0.13.1"
}

// Local profiling variant only. The default remains the stripped release module;
// -PeaglerPerfDiagnostic=true retains Wasm function names for DevTools CPU profiles.
val eaglerPerfDiagnostic = providers.gradleProperty("eaglerPerfDiagnostic")
	.map(String::toBoolean)
	.orElse(false)

// Local edit-loop mode only. TeaVM's precise aggressive whole-program pass is
// intentionally retained for performance qualification and every release.
val eaglerFastLink = providers.gradleProperty("eaglerFastLink")
	.map(String::toBoolean)
	.orElse(false)

// Same web-target exclusions as :target_teavm (real logging/LWJGL/icu4j/text2speech never
// ship to web; same-FQN facades/stubs in :platform-teavm / :teavm-compat replace them).
configurations.configureEach {
	exclude(group = "org.slf4j")
	exclude(group = "org.apache.logging.log4j")
	exclude(group = "com.mojang", module = "logging")
	exclude(group = "org.lwjgl")
	exclude(group = "com.ibm.icu")
	exclude(group = "com.mojang", module = "text2speech")
	// Desktop-only discovery/auth/native transports. Keeping these jars on the
	// browser linker classpath makes precise type-flow analysis retain thousands
	// of impossible JNA/OS/epoll classes before platform markers can fold them.
	exclude(group = "com.github.oshi")
	exclude(group = "net.java.dev.jna")
	exclude(group = "com.microsoft.azure")
	exclude(group = "com.azure")
	// Keep the tiny Java-only jopt-simple and jtracy API jars. Strict TeaVM
	// validates the shared vanilla entry/render paths before DCE, and these class
	// shapes are required there. Tracy's native binding itself is still replaced
	// by the web compatibility transformer; no native classifier is emitted.
	exclude(group = "io.netty", module = "netty-transport-classes-epoll")
	exclude(group = "io.netty", module = "netty-transport-classes-kqueue")
	exclude(group = "io.netty", module = "netty-transport-native-epoll")
	exclude(group = "io.netty", module = "netty-transport-native-kqueue")
	exclude(group = "io.netty", module = "netty-transport-native-unix-common")
}

dependencies {
	implementation(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/uri-classlib-override.jar")))
	// BLOCKER #15 (wasm-gc-only): shadow stock teavm-core's org.teavm.runtime.Fiber with the patched
	// Fiber (Fiber.current() returns a placeholder fiber outside any real fiber, so async methods
	// invoked from the module initializer -- e.g. WasmGCJSRuntime.stringToJs made async by an exception-
	// path monitor -- run synchronously instead of dereferencing a null current fiber). This jar is on
	// the PROGRAM classpath (read before the teavm runtime jars, same override mechanism teavm-compat
	// facades use), so TeaVM emits THIS Fiber. Listed FIRST so it wins. JS client unaffected (its build
	// never includes this jar). Rebuilt by wasm-toolchain/teavm-eagler-patch/build-patched-core.sh.
	implementation(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/fiber-override.jar")))
	// HOLISTIC BUFFER FIX (#18/#19/#20, wasm-gc-only): shadow stock teavm-classlib's
	// org.teavm.classlib.java.nio.TJSBufferHelper (renamed by TeaVM to java.nio.JSBufferHelper) with a
	// patched version whose getArrayBufferView COPIES a non-provider HEAP ByteBuffer (TByteBufferImpl)
	// into a fresh Int8Array at the GL upload boundary instead of throwing. This makes GL uploads work
	// with the HEAP buffers BufferUtils/EaglerFakeHeap now allocate (which also fixed the font
	// channel.read "not a PNG" path). On the PROGRAM classpath, read before the stock teavm-classlib
	// (same override mechanism as fiber-override + the teavm-compat facades), so TeaVM emits THIS class.
	// Listed early so it wins. JS client unaffected (its build never includes this jar). Rebuilt by
	// wasm-toolchain/teavm-eagler-patch/build-patched-core.sh.
	implementation(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/classlib-override.jar")))
	// ORDER is load-bearing: :platform-teavm before :platform so its concrete
	// net.lax1dude...internal.PlatformRuntime shadows the :platform native stubs by FQN.
	implementation(project(":platform-teavm"))
	implementation(project(":platform"))
	implementation(project(":game"))
	implementation(project(":teavm-compat"))
	implementation(teavm.libs.jso)
	implementation(teavm.libs.jsoApis)
	compileOnly("com.mojang:datafixerupper:10.0.21")
	compileOnly("io.netty:netty-buffer:4.2.15.Final")
	compileOnly("io.netty:netty-common:4.2.15.Final")
	compileOnly("io.netty:netty-transport:4.2.15.Final")
}

teavm {
	all {
		mainClass = "net.lax1dude.eaglercraft.v1_8.internal.wasm_gc_teavm.MainClass"
	}
	wasmGC {
		targetFileName = "classes.wasm"
		// ===== SHIP vs DEBUG (one-line flip; measured on D6 2026-07-16) =====
		// obfuscated=true  -> SHIP: strips the 40 MB wasm `name` custom section (157 -> 115 MB
		//                     on disk; 15.3 MB on the wire with brotli -q11, 29.9 MB gzip -9 —
		//                     see wasm-toolchain/precompress-web.sh). Code bytes are IDENTICAL
		//                     (the name section is the whole delta), so runtime speed is the
		//                     same; V8 just parses/holds 40 MB less. Stack traces become
		//                     unnamed wasm-function[N] frames.
		// obfuscated=false -> DEBUG: emits the name section; V8 stacks show real Java method
		//                     names (required for takeStackTrace/__castStacks diagnosis).
		// SHIP BUILD S1 (2026-07-16): true. To diagnose a stack: flip to false + relink, OR
		// redeploy the saved debug artifact wasm-toolchain/classes-debug-D8w.wasm over
		// build/generated/teavm/wasm-gc/classes.wasm (two-file swap — see memory PHASE 2 note).
		obfuscated = !eaglerPerfDiagnostic.get()
		// Keep the validated production optimizer. BALANCED generated a much smaller module
		// but miscompiled the module initializer into a recursion loop on this graph.
		fastGlobalAnalysis = eaglerFastLink.get()
		strict = !eaglerFastLink.get()
		optimization = if (eaglerFastLink.get())
			org.teavm.gradle.api.OptimizationLevel.NONE
		else
			org.teavm.gradle.api.OptimizationLevel.AGGRESSIVE
		debugInformation = false
		// The patched WasmGCTarget keeps TeaVM's direct-buffer heap at 2 MB initially and 32 MB
		// maximum. Graphics buffers use heap ByteBuffers and copy at the WebGL boundary, so the old
		// 512 MB floor is neither needed nor desirable. See wasm-toolchain/teavm-eagler-patch/src/WasmGCTarget.java.
	}
}

val standardWasmGCTask = tasks.named<org.teavm.gradle.tasks.GenerateWasmGCTask>("generateWasmGC").get()
val incrementalWasmOutput = File(
	standardWasmGCTask.outputDir.get(), standardWasmGCTask.targetFileName.get())

tasks.register("printWasmGCClasspath") {
	group = "eaglercraft build diagnostics"
	doLast {
		standardWasmGCTask.classpath.files.forEach { println(it.absolutePath) }
	}
}

tasks.register("printTeaVMToolClasspath") {
	group = "eaglercraft build diagnostics"
	doLast {
		buildscript.configurations.getByName("classpath").files.forEach { println(it.absolutePath) }
	}
}

tasks.register("printStandaloneClasspaths") {
	group = "eaglercraft build diagnostics"
	doLast {
		println("EAGLER_STANDALONE_CLASSPATHS=" + groovy.json.JsonOutput.toJson(mapOf(
			"tool" to buildscript.configurations.getByName("classpath").files.map { it.absolutePath },
			"program" to standardWasmGCTask.classpath.files.map { it.absolutePath }
		)))
	}
}

tasks.register("generateWasmGCIncremental") {
	group = "eaglercraft build"
	description = "Links the WasmGC image with TeaVM's persistent precise incremental cache"
	dependsOn("teavmClasses", ":platform:jar", ":game:jar", ":teavm-compat:jar", ":platform-teavm:jar")
	inputs.files(standardWasmGCTask.classpath.files)
	inputs.property("eaglerIncrementalSimple", providers.gradleProperty("eaglerIncrementalSimple").orElse("false"))
	outputs.file(incrementalWasmOutput)

	doLast {
		val source = standardWasmGCTask
		val log = org.teavm.tooling.ConsoleTeaVMToolLog(false)
		val builder = org.teavm.tooling.builder.InProcessBuildStrategy()
		builder.init()
		builder.setLog(log)
		var phaseTotal = 0
		var lastProgressBucket = -1
		builder.setProgressListener(object : org.teavm.vm.TeaVMProgressListener {
			override fun phaseStarted(phase: org.teavm.vm.TeaVMPhase, count: Int): org.teavm.vm.TeaVMProgressFeedback {
				phaseTotal = count
				lastProgressBucket = -1
				println("[teavm-incremental] phase=$phase workItems=$count")
				return org.teavm.vm.TeaVMProgressFeedback.CONTINUE
			}
			override fun progressReached(progress: Int): org.teavm.vm.TeaVMProgressFeedback {
				val bucket = if (phaseTotal > 0) progress * 10 / phaseTotal else 0
				if (bucket > lastProgressBucket) {
					lastProgressBucket = bucket
					println("[teavm-incremental] progress=$progress/$phaseTotal")
				}
				return org.teavm.vm.TeaVMProgressFeedback.CONTINUE
			}
		})
		builder.setClassPathEntries(source.classpath.files.map { it.absolutePath })
		builder.setTargetType(org.teavm.tooling.TeaVMTargetType.WEBASSEMBLY_GC)
		builder.setMainClass(source.mainClass.get())
		builder.setTargetDirectory(source.outputDir.get().absolutePath)
		builder.setTargetFileName(source.targetFileName.get())
		builder.setDebugInformationGenerated(source.debugInformation.get())
		builder.setSourceMapsFileGenerated(source.sourceMap.get())
		builder.setFastDependencyAnalysis(false)
		builder.setOptimizationLevel(
			if (providers.gradleProperty("eaglerIncrementalSimple").orNull == "true") {
				org.teavm.vm.TeaVMOptimizationLevel.SIMPLE
			} else when (source.optimization.get()) {
				org.teavm.gradle.api.OptimizationLevel.AGGRESSIVE -> org.teavm.vm.TeaVMOptimizationLevel.FULL
				org.teavm.gradle.api.OptimizationLevel.BALANCED -> org.teavm.vm.TeaVMOptimizationLevel.ADVANCED
				else -> org.teavm.vm.TeaVMOptimizationLevel.SIMPLE
			})
		builder.setObfuscated(source.obfuscated.get())
		builder.setStrict(source.strict.get())
		builder.setClassesToPreserve(source.preservedClasses.get().toTypedArray())
		builder.setMinDirectBuffersSize(source.minDirectBuffersSize.get() * 1024 * 1024)
		builder.setMaxDirectBuffersSize(source.maxDirectBuffersSize.get() * 1024 * 1024)
		builder.setImportedWasmMemory(source.importedWasmMemory.get())
		builder.setWasmDebugInfoLevel(when (source.debugInfoLevel.get()) {
			org.teavm.gradle.api.WasmDebugInfoLevel.FULL -> org.teavm.backend.wasm.WasmDebugInfoLevel.FULL
			org.teavm.gradle.api.WasmDebugInfoLevel.DEOBFUSCATION -> org.teavm.backend.wasm.WasmDebugInfoLevel.DEOBFUSCATION
		})
		builder.setWasmDebugInfoLocation(when (source.debugInfoLocation.get()) {
			org.teavm.gradle.api.WasmDebugInfoLocation.EMBEDDED -> org.teavm.backend.wasm.WasmDebugInfoLocation.EMBEDDED
			else -> org.teavm.backend.wasm.WasmDebugInfoLocation.EXTERNAL
		})
		val properties = Properties()
		source.properties.get().forEach { (key, value) -> properties.setProperty(key, value) }
		builder.setProperties(properties)
		builder.setIncremental(true)
		builder.setCacheDirectory(
			layout.buildDirectory.dir("teavm-incremental/wasm-gc").get().asFile.absolutePath)

		val result = builder.build()
		org.teavm.tooling.TeaVMProblemRenderer.describeProblems(result.callGraph, result.problems, log)
		if (result.problems.severeProblems.isNotEmpty()) {
			throw GradleException("Errors occurred during incremental TeaVM build")
		}
	}
}
