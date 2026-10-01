plugins {
	id("java")
}

// The platform contract module (EaglercraftX pattern: native-stub signature set).
// Grows in Phase 2 as desktop-only calls are pulled behind seams.
dependencies {
	// Desktop implementations may delegate to native-memory APIs supplied by the
	// consuming game module. The TeaVM target shadows those classes and excludes
	// LWJGL, so keep this compile-only.
	compileOnly("org.lwjgl:lwjgl:3.4.1")
	// EagRuntime exposes one TeaVM platform marker used to cut the dedicated
	// integrated-server graph out of the Wasm-GC client before dependency
	// analysis. It is a normal false-returning method on the desktop JVM.
	compileOnly("org.teavm:teavm-interop:0.13.1")
}
