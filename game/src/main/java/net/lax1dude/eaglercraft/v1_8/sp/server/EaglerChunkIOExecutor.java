package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

import net.lax1dude.eaglercraft.v1_8.sp.server.internal.ServerPlatformSingleplayer;

/**
 * Hosted-web chunk storage executor. Browser {@code IndexedDB} calls suspend the
 * Java coroutine that issued them; running an {@code IOWorker} store inline on
 * the server coroutine therefore pauses every server tick until the transaction
 * completes. This executor gives storage its own cooperative coroutine and
 * yields after each chunk so server timers, entities, and world generation can
 * continue while IndexedDB is pending.
 *
 * <p>Only the hosted-web branch in {@code IOWorker} uses this executor. Desktop
 * keeps vanilla's {@code Util.ioPool()} and its real worker threads.</p>
 */
public final class EaglerChunkIOExecutor implements Executor {

	public static final EaglerChunkIOExecutor INSTANCE = new EaglerChunkIOExecutor();

	private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

	private EaglerChunkIOExecutor() {
		Thread worker = new Thread(this::runLoop, "Eagler chunk I/O");
		worker.setDaemon(true);
		worker.start();
	}

	@Override
	public void execute(Runnable command) {
		if (command == null) {
			throw new NullPointerException("command");
		}
		synchronized (queue) {
			queue.addLast(command);
			queue.notify();
		}
	}

	private void runLoop() {
		while (true) {
			Runnable command;
			synchronized (queue) {
				while (queue.isEmpty()) {
					try {
						queue.wait();
					} catch (InterruptedException ignored) {
					}
				}
				command = queue.removeFirst();
			}
			command.run();
			ServerPlatformSingleplayer.immediateContinue();
		}
	}
}
