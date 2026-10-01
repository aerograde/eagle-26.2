package org.slf4j;

import java.util.Iterator;

public interface Marker {

	String ANY_MARKER = "*";
	String ANY_NON_NULL_MARKER = "+";

	String getName();

	void add(Marker reference);

	boolean remove(Marker reference);

	boolean hasChildren();

	boolean hasReferences();

	Iterator<Marker> iterator();

	boolean contains(Marker other);

	boolean contains(String name);

}
