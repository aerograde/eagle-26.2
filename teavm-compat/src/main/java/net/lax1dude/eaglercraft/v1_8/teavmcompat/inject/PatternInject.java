package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Donor for java.util.regex.Pattern (see plugin.JdkMethodInjector). TeaVM 0.13's
 * TPattern lacks asPredicate(), reached from client Main (validation / brigadier
 * argument parsing). Semantics match the JDK: find() on the input.
 */
public final class PatternInject {

	/** placeholder: the target already implements matcher (never copied) */
	public java.util.regex.Matcher matcher(CharSequence input) {
		return null;
	}

	public Predicate<String> asPredicate() {
		return input -> ((Pattern) (Object) this).matcher(input).find();
	}

	public Predicate<String> asMatchPredicate() {
		return input -> ((Pattern) (Object) this).matcher(input).matches();
	}

}
