package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.beans;

import java.util.EventObject;

/**
 * java.beans.PropertyChangeEvent — value-object mirror (reached from library
 * bean plumbing; no java.beans machinery exists under TeaVM).
 */
public class TPropertyChangeEvent extends EventObject {

	private final String propertyName;
	private final Object oldValue;
	private final Object newValue;

	public TPropertyChangeEvent(Object source, String propertyName, Object oldValue, Object newValue) {
		super(source);
		this.propertyName = propertyName;
		this.oldValue = oldValue;
		this.newValue = newValue;
	}

	public String getPropertyName() {
		return propertyName;
	}

	public Object getOldValue() {
		return oldValue;
	}

	public Object getNewValue() {
		return newValue;
	}

}
