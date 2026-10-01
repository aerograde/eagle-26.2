plugins {
	id("java")
}

// Desktop backend: EaglercraftX src/lwjgl sources copied per the reimplementation
// directive (same-FQN shadowing of :platform stubs, eagler method).
// Upgraded from upstream LWJGL 3.3.6 to 26.2's 3.4.1 line.
val lwjglVersion = "3.4.1"

dependencies {
	implementation(project(":platform"))

	implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
	implementation("org.lwjgl:lwjgl")
	implementation("org.lwjgl:lwjgl-egl")
	implementation("org.lwjgl:lwjgl-glfw")
	implementation("org.lwjgl:lwjgl-jemalloc")
	implementation("org.lwjgl:lwjgl-openal")
	implementation("org.lwjgl:lwjgl-opengl")
	implementation("org.lwjgl:lwjgl-opengles")
	implementation("org.java-websocket:Java-WebSocket:1.6.0")
	implementation("org.xerial:sqlite-jdbc:3.50.3.0")
	implementation(
		files(
			"lib/codecjorbis-20101023.jar",
			"lib/codecwav-20101023.jar",
			"lib/soundsystem-20120107.jar",
			"lib/UnsafeMemcpy.jar"
		)
	)

	runtimeOnly("org.lwjgl:lwjgl::natives-linux")
	runtimeOnly("org.lwjgl:lwjgl-glfw::natives-linux")
	runtimeOnly("org.lwjgl:lwjgl-jemalloc::natives-linux")
	runtimeOnly("org.lwjgl:lwjgl-openal::natives-linux")
	runtimeOnly("org.lwjgl:lwjgl-opengles::natives-linux")
}
