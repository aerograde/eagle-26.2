package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.script;

/**
 * javax.script.CompiledScript — link-only stub (see TScriptEngine; no engine
 * ever exists, so nothing ever compiles).
 */
public abstract class TCompiledScript {

	protected TCompiledScript() {
	}

	public Object eval() {
		throw new UnsupportedOperationException("no script engines in the browser runtime");
	}

	public abstract TScriptEngine getEngine();

}
