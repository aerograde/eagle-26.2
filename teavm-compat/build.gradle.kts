plugins {
	id("java")
}

// Phase 3.0 TeaVM classlib-compat module (docs/phase3-web-toolchain-map.md).
//
// Supplies JDK classes/methods that TeaVM 0.13's curated classlib lacks, via
// three mechanisms (see src/main/resources/META-INF/teavm.properties and
// net.lax1dude.eaglercraft.v1_8.teavmcompat.plugin.JdkMethodInjector):
//  1. mapPackageHierarchy   — brand-new java.*/javax.*/jdk.*/sun.* classes
//  2. mapClass              — whole-class replacements (java.util.UUID)
//  3. TeaVMPlugin transformer — methods injected into existing classlib classes
//
// teavm-core is compileOnly: the plugin/transformer classes run inside the
// TeaVM build daemon (which provides org.teavm.* itself) and are never
// reachable from the generated JS/wasm, so nothing of TeaVM ships.
dependencies {
	compileOnly("org.teavm:teavm-core:0.13.0")
	// The io.netty.util.internal.logging.InternalLoggerFactory shadow (see
	// that file) compiles against netty's InternalLogger/Slf4JLoggerFactory;
	// version matches :game's netty. compileOnly so nothing of netty ships.
	compileOnly("io.netty:netty-common:4.2.15.Final")
}
