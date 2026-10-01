package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

/**
 * java.util.concurrent.RejectedExecutionHandler — absent from
 * teavm-classlib 0.13. Needed as the parameter type of a ThreadPoolExecutor
 * constructor. Inline execution never rejects, so this is never invoked, but
 * the interface must exist so callers that pass a handler link.
 */
public interface TRejectedExecutionHandler {

	void rejectedExecution(Runnable r, TThreadPoolExecutor executor);

}
