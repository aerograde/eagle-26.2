plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "eagler-26_2"

include("game")
include("platform")
include("platform-lwjgl")
include("target_lwjgl_desktop")
include("target_teavm_spike")
include("teavm-compat")
include("platform-teavm")
include("target_teavm")
include("target_teavm_wasm_gc")
include("target_teavm_wasm_gc_mesh")
include("target_teavm_wasm_gc_server")
