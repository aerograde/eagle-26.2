package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.Comparator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * java.util.concurrent.ConcurrentSkipListMap — plain TreeMap; there is no
 * concurrency to defend against in the browser runtime, and TreeMap supplies
 * the whole NavigableMap surface.
 */
public class TConcurrentSkipListMap<K, V> extends TreeMap<K, V> {

	public TConcurrentSkipListMap() {
	}

	public TConcurrentSkipListMap(Comparator<? super K> comparator) {
		super(comparator);
	}

	public TConcurrentSkipListMap(Map<? extends K, ? extends V> source) {
		super(source);
	}

	public TConcurrentSkipListMap(SortedMap<K, ? extends V> source) {
		super(source);
	}

}
