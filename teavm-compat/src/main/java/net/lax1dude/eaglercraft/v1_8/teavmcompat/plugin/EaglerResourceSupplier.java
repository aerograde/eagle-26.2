package net.lax1dude.eaglercraft.v1_8.teavmcompat.plugin;

import org.teavm.classlib.ResourceSupplier;
import org.teavm.classlib.ResourceSupplierContext;

/**
 * Embeds the classpath resources vanilla reads during Bootstrap into the
 * generated JS (TeaVM bundles no resources by default). en_us.json is what
 * the vanilla client jar itself ships; Language.&lt;clinit&gt; hard-requires
 * it (gson-parses the stream with no null check). Runs inside the TeaVM
 * build daemon, never ships.
 */
public class EaglerResourceSupplier implements ResourceSupplier {

	@Override
	public String[] supplyResources(ResourceSupplierContext context) {
		return new String[] {
			"version.json",
			"assets/minecraft/lang/en_us.json",
		};
	}

}
