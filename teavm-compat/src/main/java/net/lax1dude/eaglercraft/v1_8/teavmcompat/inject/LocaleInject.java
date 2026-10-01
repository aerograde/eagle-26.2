package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Donor for java.util.Locale (see plugin.JdkMethodInjector). TeaVM 0.13's
 * TLocale only implements the language/country surface; MC 26.2's text stack
 * (ICU4J, Language, jopt) reaches the BCP-47 extension accessors. These return
 * the "no extensions / root script" answers, which is correct for the locales
 * the client actually uses (en_us and friends carry no Unicode extensions).
 */
public final class LocaleInject {

	public static Locale forLanguageTag(String languageTag) {
		if (languageTag == null || languageTag.isEmpty()) {
			return Locale.ROOT;
		}
		String[] parts = languageTag.replace('_', '-').split("-");
		String lang = parts.length > 0 ? parts[0] : "";
		String country = parts.length > 1 && parts[1].length() == 2 ? parts[1] : "";
		return country.isEmpty() ? new Locale(lang) : new Locale(lang, country);
	}

	public String getScript() {
		return "";
	}

	public String getISO3Country() {
		return "";
	}

	public String getExtension(char key) {
		return null;
	}

	public Set<Character> getExtensionKeys() {
		return Collections.emptySet();
	}

	public Set<String> getUnicodeLocaleAttributes() {
		return Collections.emptySet();
	}

	public Set<String> getUnicodeLocaleKeys() {
		return Collections.emptySet();
	}

	public String getUnicodeLocaleType(String key) {
		return null;
	}

}
