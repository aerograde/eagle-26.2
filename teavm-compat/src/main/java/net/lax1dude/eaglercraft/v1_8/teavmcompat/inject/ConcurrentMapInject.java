package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.util.Objects;
import java.util.function.Function;

/**
 * REPLACE-mode donor for java.util.concurrent.ConcurrentMap (TeaVM's
 * TConcurrentMap). TeaVM 0.13's default computeIfAbsent ends with
 * {@code return putIfAbsent(key, newValue);} — null on every first insert
 * instead of the mapped value (JDK contract: the current/new value). That
 * nulled out ResourceKey's MapMaker intern cache and killed registry
 * bootstrap. Abstract members are javac placeholders resolved against the
 * real interface after reference renaming; they are never copied.
 */
public abstract class ConcurrentMapInject {

	public abstract Object get(Object key);

	public abstract Object putIfAbsent(Object key, Object value);

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public Object computeIfAbsent(Object key, Function mappingFunction) {
		Objects.requireNonNull(mappingFunction);
		Object v = get(key);
		if (v != null) {
			return v;
		}
		Object newValue = mappingFunction.apply(key);
		if (newValue == null) {
			return null;
		}
		Object prior = putIfAbsent(key, newValue);
		return prior == null ? newValue : prior;
	}

}
