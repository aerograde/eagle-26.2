plugins {
	id("java")
}

// Browser backend: EaglercraftX src/teavm sources copied per the reimplementation
// directive (same-FQN shadowing of :platform stubs, eagler method, mirrors
// :platform-lwjgl). Upgraded from upstream TeaVM 0.9.x JSO to 26.2's 0.13.0 line
// (teavm-jso/jso-apis/interop are needed when the TeaVM build daemon compiles
// this module into JS/wasm, so implementation scope).
val teavmVersion = "0.13.0"

dependencies {
	implementation(project(":platform"))

	// Phase 3.3a: the WebGL2 GpuDeviceBackend implementation lives here and
	// implements the com.mojang.blaze3d.* interfaces from :game. No dependency
	// cycle: :game depends only on :platform. :game is `implementation` scope, so
	// its transitive libs do NOT leak onto this module's COMPILE classpath — the
	// handful the blaze3d interface signatures reference (lwjgl PointerBuffer /
	// Vk* in RenderPass(Backend), joml Vector4fc in CommandEncoderBackend, jspecify
	// @Nullable) are added compileOnly below. They are still present on
	// :target_teavm's runtime classpath transitively via :game, so TeaVM
	// generateJavaScript resolves them for any reachable method.
	implementation(project(":game"))
	// Phase 3.3b: org.lwjgl.* is provided by THIS module's linkage stubs
	// (src/main/java/org/lwjgl/**) — real LWJGL jars must NOT be on this compile
	// classpath or javac could prefer the jar classes over the stub sources.
	// :target_teavm excludes group org.lwjgl entirely for the same reason.
	compileOnly("org.joml:joml:1.10.8")           // Vector4fc (CommandEncoderBackend clears)
	compileOnly("org.jspecify:jspecify:1.0.0")    // @Nullable on interface signatures
	// Mesh-worker plan Phase B: MeshWorkerCompileTest references LocalPlayer, whose
	// superclass LivingEntity carries a @Nullable type annotation on a fastutil-typed
	// field — javac must load Object2LongMap to attach it. compileOnly only (same
	// rationale as joml/jspecify); fastutil is on :target_teavm's runtime classpath
	// transitively via :game.
	compileOnly("it.unimi.dsi:fastutil:8.5.18")

	implementation("org.teavm:teavm-jso:$teavmVersion")
	implementation("org.teavm:teavm-jso-apis:$teavmVersion")
	implementation("org.teavm:teavm-interop:$teavmVersion")
	// org.teavm.runtime.fs.VirtualFileSystemProvider — EaglerVirtualFilesystem swaps
	// TeaVM's in-memory java.io backing for the IndexedDB one (persistent worlds/
	// options/servers). The runtime classes are linked into the JS output by
	// :target_teavm's TeaVM build regardless; compileOnly is enough here.
	compileOnly("org.teavm:teavm-core:$teavmVersion")

	// Phase 3.2a logging bridge: ClientMain installs the web log stream via
	// org.slf4j.LoggerFactory.setEaglerRedirector (the facade hook documented in
	// docs §3.0b). The facade lives in :teavm-compat; compileOnly because the
	// real facade classes are put on the TeaVM classpath by :target_teavm (which
	// depends on :teavm-compat) — nothing of it needs to ship in this module's jar.
	compileOnly(project(":teavm-compat"))
}
