package com.mojang.logging;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Replaces com.mojang:logging's queue registry. getNextLogEvent is a
 * blocking take() upstream (dedicated-server GUI thread); single-threaded
 * web runtime polls instead — the only caller (MinecraftServerGui) is
 * unreachable in the browser build anyway.
 */
public class LogQueues {

	private static final Map<String, BlockingQueue<String>> queues = new HashMap<>();

	public static synchronized BlockingQueue<String> getOrCreateQueue(String name) {
		BlockingQueue<String> ret = queues.get(name);
		if (ret == null) {
			ret = new LinkedBlockingQueue<>();
			queues.put(name, ret);
		}
		return ret;
	}

	public static String getNextLogEvent(String queueName) {
		return getOrCreateQueue(queueName).poll();
	}

}
