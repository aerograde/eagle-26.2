package net.minecraft.client.multiplayer;

/** Bounded, generation-aware ownership for server-list ping resources. */
public final class ServerPingAdmission {

	private final int maximumActive;
	private long generation;
	private int active;

	ServerPingAdmission(final int maximumActive) {
		if(maximumActive < 1) {
			throw new IllegalArgumentException("maximumActive must be positive");
		}
		this.maximumActive = maximumActive;
	}

	synchronized Ticket tryAcquire() {
		if(this.active >= this.maximumActive) {
			return null;
		}
		++this.active;
		return new Ticket(this, this.generation);
	}

	synchronized void reset() {
		++this.generation;
	}

	synchronized boolean isCurrent(final Ticket ticket) {
		return ticket.owner == this && ticket.generation == this.generation;
	}

	private synchronized void release(final Ticket ticket) {
		if(ticket.owner != this || ticket.released) {
			return;
		}
		ticket.released = true;
		if(this.active > 0) {
			--this.active;
		}
	}

	synchronized int activeCount() {
		return this.active;
	}

	public static final class Ticket implements AutoCloseable {
		private final ServerPingAdmission owner;
		private final long generation;
		private boolean released;

		private Ticket(final ServerPingAdmission owner, final long generation) {
			this.owner = owner;
			this.generation = generation;
		}

		@Override
		public void close() {
			this.owner.release(this);
		}
	}
}
