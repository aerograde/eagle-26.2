import java.io.File
import java.util.Properties

// Dedicated wasm-gc integrated-server image. A separate TeaVM root allows DCE
// to omit the client renderer, UI, audio and mesh compiler from the server heap.
buildscript {
	repositories {
		mavenCentral()
	}
	dependencies {
		classpath(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/teavm-core-0.13.1-eagler.jar")))
		classpath(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/teavm-jso-impl-0.13.1-eagler.jar")))
		classpath("org.teavm:teavm-core:0.13.1")
		classpath("org.teavm:teavm-jso-impl:0.13.1")
	}
}

plugins {
	id("java")
	id("org.teavm") version "0.13.1"
}

val eaglerFastLink = providers.gradleProperty("eaglerFastLink")
	.map(String::toBoolean)
	.orElse(false)

val eaglerSlimFastutilJar = providers.gradleProperty("eaglerSlimFastutilJar").orNull
val eaglerServerGameJar = providers.gradleProperty("eaglerServerGameJar").orNull

configurations.configureEach {
	exclude(group = "org.slf4j")
	exclude(group = "org.apache.logging.log4j")
	exclude(group = "com.mojang", module = "logging")
	exclude(group = "org.lwjgl")
	exclude(group = "com.ibm.icu")
	exclude(group = "com.mojang", module = "text2speech")
	exclude(group = "com.github.oshi")
	exclude(group = "net.java.dev.jna")
	exclude(group = "com.microsoft.azure")
	exclude(group = "com.azure")
	exclude(group = "com.mojang", module = "jtracy")
	exclude(group = "net.sf.jopt-simple")
	exclude(group = "io.netty", module = "netty-transport-classes-epoll")
	exclude(group = "io.netty", module = "netty-transport-classes-kqueue")
	exclude(group = "io.netty", module = "netty-transport-native-epoll")
	exclude(group = "io.netty", module = "netty-transport-native-kqueue")
	exclude(group = "io.netty", module = "netty-transport-native-unix-common")
	if (eaglerSlimFastutilJar != null) {
		exclude(group = "it.unimi.dsi", module = "fastutil")
	}
}

dependencies {
	implementation(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/uri-classlib-override.jar")))
	implementation(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/fiber-override.jar")))
	implementation(files(rootProject.file("wasm-toolchain/teavm-eagler-patch/classlib-override.jar")))
	implementation(project(":platform-teavm"))
	implementation(project(":platform"))
	implementation(project(":game"))
	implementation(project(":teavm-compat"))
	if (eaglerSlimFastutilJar != null) {
		implementation(files(eaglerSlimFastutilJar))
	}
	implementation(teavm.libs.jso)
	implementation(teavm.libs.jsoApis)
	compileOnly("com.mojang:datafixerupper:10.0.21")
	compileOnly("io.netty:netty-buffer:4.2.15.Final")
	compileOnly("io.netty:netty-common:4.2.15.Final")
	compileOnly("io.netty:netty-transport:4.2.15.Final")
}

teavm {
	all {
		mainClass = "net.lax1dude.eaglercraft.v1_8.internal.wasm_gc_teavm_server.ServerMainClass"
	}
	wasmGC {
		targetFileName = "server-worker.wasm"
		obfuscated = true
		// The server graph reaches Guardian.aiStep, where TeaVM 0.13.1's
		// AGGRESSIVE inliner can produce an invalid SSA use-before-definition.
		// BALANCED keeps whole-program DCE/obfuscation (the RAM/size win) while
		// avoiding that unsafe late optimizer pass. The dedicated-root startup
		// recursion was independently reproduced with NONE and is fixed at the
		// green-thread entry boundary, not by disabling optimization.
		fastGlobalAnalysis = eaglerFastLink.get()
		strict = !eaglerFastLink.get()
		optimization = if (eaglerFastLink.get())
			org.teavm.gradle.api.OptimizationLevel.NONE
		else
			org.teavm.gradle.api.OptimizationLevel.BALANCED
		debugInformation = false
	}
}

val standardWasmGCTask = tasks.named<org.teavm.gradle.tasks.GenerateWasmGCTask>("generateWasmGC").get()
if (eaglerServerGameJar != null) {
	val slicedClasspath = standardWasmGCTask.classpath.files
		.filter { it.name != "game.jar" }
		.plus(File(eaglerServerGameJar))
	standardWasmGCTask.classpath.setFrom(slicedClasspath)
}
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
	inputs.property("eaglerSlimFastutilJar", providers.gradleProperty("eaglerSlimFastutilJar").orElse(""))
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
		builder.setClassPathEntries(source.classpath.files.map {
			it.absolutePath
		})
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
