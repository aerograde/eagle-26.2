package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

/**
 * java.util.concurrent.RunnableFuture — absent from teavm-classlib 0.13.
 * Referenced by AbstractExecutorService.newTaskFor and by guava's
 * AbstractListeningExecutorService.
 */
public interface TRunnableFuture<V> extends Runnable, TFuture<V> {

	@Override
	void run();

}
