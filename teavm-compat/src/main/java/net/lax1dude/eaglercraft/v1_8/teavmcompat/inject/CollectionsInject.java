package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Donor for java.util.Collections (see plugin.JdkMethodInjector). TeaVM 0.13's
 * TCollections lacks the SortedMap/SortedSet unmodifiable wrappers reached from
 * client Main (registries / ICU). A defensive copy into a TreeMap/TreeSet is a
 * faithful read-only view for the single-threaded browser runtime.
 */
public final class CollectionsInject {

	public static <K, V> SortedMap<K, V> unmodifiableSortedMap(SortedMap<K, V> m) {
		TreeMap<K, V> copy = new TreeMap<>(m.comparator());
		copy.putAll(m);
		return copy;
	}

	public static <T> SortedSet<T> unmodifiableSortedSet(SortedSet<T> s) {
		TreeSet<T> copy = new TreeSet<>(s.comparator());
		copy.addAll(s);
		return copy;
	}

}
