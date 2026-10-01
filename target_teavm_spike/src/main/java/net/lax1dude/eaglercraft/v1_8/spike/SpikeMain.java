package net.lax1dude.eaglercraft.v1_8.spike;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Bootstrap-scale wasm-gc gate (see scratchpad/wasm-gc-clsnull.md): compiling
 * {@code SharedConstants.tryDetectVersion()} + {@code Bootstrap.bootStrap()} to
 * wasm-gc pulls in the whole registry/datafixer graph, which is what trips the
 * stock TeaVM 0.13 wasm-gc codegen bugs the patched teavm-core fixes. This is the
 * milestone entry: it must BUILD (28 MB classes.wasm) and RUN (prints "Bootstrap ok")
 * under node via scratchpad/run-wasmgc-spike.mjs.
 */
public class SpikeMain {

	public static void main(String[] args) throws Exception {
		System.out.println("SPIKE: bootstrap-scale wasm-gc start");
		SharedConstants.tryDetectVersion();
		System.out.println("SPIKE: version detected");
		Bootstrap.bootStrap();
		System.out.println("SPIKE_RESULT=Bootstrap ok");
	}

}
