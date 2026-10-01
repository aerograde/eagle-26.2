plugins {
	id("java")
}

dependencies {
	// order matters: platform-lwjgl's Platform* bodies shadow :platform's stubs
	implementation(project(":platform-lwjgl"))
	implementation(project(":platform"))
	implementation(project(":game"))
}

tasks.register<JavaExec>("eaglercraftDebugRuntime") {
	group = "eaglercraft"
	description = "Run the 26.2 desktop client (debug)"
	// platform-lwjgl first: its Platform* bodies must shadow the :platform stubs
	classpath = project(":platform-lwjgl").extensions.getByType<SourceSetContainer>()["main"].output +
		sourceSets["main"].runtimeClasspath
	mainClass = "net.lax1dude.eaglercraft.v1_8.internal.lwjgl.MainClass"
	workingDir = rootProject.layout.projectDirectory.dir("run").asFile
	javaLauncher = javaToolchains.launcherFor {
		languageVersion = JavaLanguageVersion.of(25)
	}
	jvmArgs(
		"-Xmx4G", "-Xms1G", "-Deagler.hosted=true",
		"-Djava.library.path=" + layout.projectDirectory.dir("natives").asFile.absolutePath,
		"--enable-native-access=ALL-UNNAMED"
	)
	args(
		"hide-renderdoc",
		"--version", "26.2-eagler",
		"--gameDir", ".",
		"--assetsDir", rootProject.layout.projectDirectory.dir("assets").asFile.absolutePath,
		"--assetIndex", "32",
		"--username", "Matej",
		"--accessToken", "0",
		"--userType", "legacy"
	)
	doFirst {
		workingDir.mkdirs()
	}
}

tasks.register<JavaExec>("buildAssetsEPK") {
	group = "eaglercraft"
	description = "Pack the 26.2 asset tree into assets.epk (the eaglercraft packager)"
	dependsOn(":game:processResources")
	// JavaExec already tracks its classpath and arguments. Declare the actual
	// resource tree and archive so unchanged packaging keeps the verified bytes.
	inputs.dir(project(":game").layout.projectDirectory.dir("src/main/resources"))
		.withPathSensitivity(PathSensitivity.RELATIVE)
	outputs.file(layout.buildDirectory.file("epk/assets.epk"))
	classpath = sourceSets["main"].runtimeClasspath
	mainClass = "net.lax1dude.eaglercraft.v1_8.sp.server.export.EPKPackagerMain"
	args(
		project(":game").layout.projectDirectory.dir("src/main/resources").asFile.absolutePath,
		layout.buildDirectory.file("epk/assets.epk").get().asFile.absolutePath,
		"assets.epk"
	)
}

// Audit-only baseline for measuring the exact archive cost of entries excluded
// by buildAssetsEPK. It is deliberately not a dependency of any regular build.
tasks.register<JavaExec>("buildAssetsEPKWithWebUnreachable") {
	group = "verification"
	description = "Pack an audit baseline including web-unreachable assets"
	dependsOn(":game:processResources")
	inputs.dir(project(":game").layout.projectDirectory.dir("src/main/resources"))
		.withPathSensitivity(PathSensitivity.RELATIVE)
	outputs.file(layout.buildDirectory.file("epk/assets-with-web-unreachable.epk"))
	classpath = sourceSets["main"].runtimeClasspath
	mainClass = "net.lax1dude.eaglercraft.v1_8.sp.server.export.EPKPackagerMain"
	args(
		project(":game").layout.projectDirectory.dir("src/main/resources").asFile.absolutePath,
		layout.buildDirectory.file("epk/assets-with-web-unreachable.epk").get().asFile.absolutePath,
		"assets.epk",
		"--include-web-unreachable"
	)
}
