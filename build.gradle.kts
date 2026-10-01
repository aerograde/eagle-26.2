subprojects {
	apply(plugin = "java")

	repositories {
		maven("https://libraries.minecraft.net")
		mavenCentral()
	}

	extensions.configure<JavaPluginExtension> {
		toolchain {
			// System apt openjdk-25 is picked up first; foojay resolver
			// (settings.gradle.kts) auto-downloads Temurin 25 elsewhere.
			languageVersion = JavaLanguageVersion.of(25)
		}
	}

	tasks.withType<JavaCompile>().configureEach {
		options.encoding = "UTF-8"
		// The Eagler TeaVM 0.12 WasmGC compiler supports classfiles through Java
		// 24, while this snapshot is built with JDK 25. Emit the oldest bytecode
		// level the source accepts so web linking does not depend on classfile 69.
		// Compiler plugins can be rebuilt for a Java 17 host without changing the
		// game bytecode by passing -PeaglerJavaRelease=17 to their isolated task.
		options.release.set(providers.gradleProperty("eaglerJavaRelease").map(String::toInt).orElse(24))
		options.compilerArgs.add("-nowarn")
		options.compilerArgs.addAll(listOf("-Xmaxerrs", "10000"))
		// Decompiled sources: no annotation processing wanted
		options.compilerArgs.add("-proc:none")
	}
}
