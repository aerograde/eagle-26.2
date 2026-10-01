package org.slf4j.helpers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Marker;

public class BasicMarker implements Marker {

	private final String name;
	private final List<Marker> references = new ArrayList<>(0);

	public BasicMarker(String name) {
		if (name == null) {
			throw new IllegalArgumentException("A marker name cannot be null");
		}
		this.name = name;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void add(Marker reference) {
		if (reference != null && !this.contains(reference) && !reference.contains(this)) {
			references.add(reference);
		}
	}

	@Override
	public boolean remove(Marker reference) {
		return references.remove(reference);
	}

	@Override
	public boolean hasChildren() {
		return hasReferences();
	}

	@Override
	public boolean hasReferences() {
		return !references.isEmpty();
	}

	@Override
	public Iterator<Marker> iterator() {
		return references.iterator();
	}

	@Override
	public boolean contains(Marker other) {
		if (other == null) {
			return false;
		}
		if (this.equals(other)) {
			return true;
		}
		for (Marker ref : references) {
			if (ref.contains(other)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean contains(String otherName) {
		if (otherName == null) {
			return false;
		}
		if (name.equals(otherName)) {
			return true;
		}
		for (Marker ref : references) {
			if (ref.contains(otherName)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof Marker)) {
			return false;
		}
		return name.equals(((Marker) obj).getName());
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}

	@Override
	public String toString() {
		return name;
	}

}
