package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.script;

/**
 * javax.script.ScriptEngineFactory — marker stub (see TScriptEngine).
 */
public interface TScriptEngineFactory {

	String getEngineName();

	String getEngineVersion();

	String getLanguageName();

	String getLanguageVersion();

	java.util.List<String> getNames();

	Object getParameter(String key);

	TScriptEngine getScriptEngine();

}
