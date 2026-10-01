package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

/**
 * java.util.concurrent.ForkJoinWorkerThread — MC 26.2 subclasses this in
 * net.minecraft.util.Util's worker factory (overriding onStart/onTermination),
 * so the class and its protected hooks must exist. The inline TForkJoinPool
 * never actually starts one of these.
 */
public class TForkJoinWorkerThread extends Thread {

	private final TForkJoinPool pool;

	protected TForkJoinWorkerThread(TForkJoinPool pool) {
		this.pool = pool;
	}

	public TForkJoinPool getPool() {
		return pool;
	}

	public int getPoolIndex() {
		return 0;
	}

	protected void onStart() {
	}

	protected void onTermination(Throwable exception) {
	}

	@Override
	public void run() {
		Throwable exception = null;
		try {
			onStart();
		} catch (Throwable t) {
			exception = t;
		} finally {
			onTermination(exception);
		}
	}

}
