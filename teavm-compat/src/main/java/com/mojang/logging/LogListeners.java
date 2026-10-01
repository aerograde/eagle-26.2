package com.mojang.logging;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.event.Level;

/**
 * Replaces com.mojang:logging's log4j-appender-backed listener registry;
 * EaglerSlf4jLogger fans formatted lines out to listeners directly.
 */
public class LogListeners {

	public interface Listener {
		void accept(String message, Level level);
	}

	private static final List<Listener> listeners = new ArrayList<>();

	public static void addListener(String name, Listener listener) {
		synchronized (listeners) {
			listeners.add(listener);
		}
	}

	public static void fireLogEvent(String message, Level level) {
		synchronized (listeners) {
			for (int i = 0; i < listeners.size(); ++i) {
				listeners.get(i).accept(message, level);
			}
		}
	}

}
