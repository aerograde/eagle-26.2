package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.locks;

import java.io.Serializable;

/** Minimal faithful java.util.concurrent.locks.AbstractOwnableSynchronizer. */
public abstract class TAbstractOwnableSynchronizer implements Serializable {

	private transient Thread exclusiveOwnerThread;

	protected TAbstractOwnableSynchronizer() {
	}

	protected final void setExclusiveOwnerThread(Thread thread) {
		exclusiveOwnerThread = thread;
	}

	protected final Thread getExclusiveOwnerThread() {
		return exclusiveOwnerThread;
	}
}
