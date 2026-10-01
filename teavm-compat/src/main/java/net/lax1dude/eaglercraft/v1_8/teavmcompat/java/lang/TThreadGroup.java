package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang;

/**
 * java.lang.ThreadGroup — inert stub; Thread.getThreadGroup returns null (a
 * legal JDK value for terminated threads), so no instance normally exists.
 */
public class TThreadGroup {

	private final String name;

	public TThreadGroup(String name) {
		this.name = name;
	}

	public TThreadGroup(TThreadGroup parent, String name) {
		this.name = name;
	}

	public final String getName() {
		return name;
	}

	public final TThreadGroup getParent() {
		return null;
	}

	public int activeCount() {
		return 1;
	}

	public void uncaughtException(Thread t, Throwable e) {
		e.printStackTrace();
	}

}
