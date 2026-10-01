package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * Donor for java.lang.Thread (registered with copyConstructors, see
 * plugin.JdkMethodInjector). getThreadGroup returning null is a legal JDK
 * value (terminated threads report null).
 */
public final class ThreadInject {

	/** placeholder: the target already has the (Runnable,String) constructor (never copied) */
	private ThreadInject(Runnable target, String name) {
	}

	public ThreadInject(ThreadGroup group, Runnable target, String name, long stackSize) {
		this(target, name);
	}

	public ThreadInject(ThreadGroup group, Runnable target, String name) {
		this(target, name);
	}

	/** single-threaded runtime: a live thread is always RUNNABLE. */
	public Thread.State getState() {
		return Thread.State.RUNNABLE;
	}

	public static void onSpinWait() {
		// no-op: nothing to yield to on a single JS thread
	}

	public static void sleep(java.time.Duration duration) throws InterruptedException {
		Thread.sleep(duration.toMillis());
	}

	public ThreadGroup getThreadGroup() {
		return null;
	}

	/**
	 * Java 19+ alias of getId(); TeaVM 0.13's TThread only has getId. Reached from
	 * client Main / ClientShutdownWatchdog (Phase 3.3b). Identity hash is a stable
	 * per-thread id — it is only ever used as a label in watchdog dumps.
	 */
	public long threadId() {
		return System.identityHashCode(this);
	}

	public void setContextClassLoader(ClassLoader cl) {
		// inert: there is one class loader universe under TeaVM
	}

	/** Phase 3.3b: reached from client Main; a diagnostic no-op under TeaVM. */
	public static void dumpStack() {
		new Throwable("Stack trace").printStackTrace();
	}

}
