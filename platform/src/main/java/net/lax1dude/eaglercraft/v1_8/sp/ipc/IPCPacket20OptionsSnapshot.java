/*
 * 26.2 addition to the upstream IPC set (EaglercraftX carries these values by
 * direct shared-memory reads in IntegratedServer.tickServer; the worker split
 * pushes them client -> server as a snapshot whenever they change).
 *
 * Style and wire conventions follow lax1dude's IPCPacket* classes.
 */

package net.lax1dude.eaglercraft.v1_8.sp.ipc;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class IPCPacket20OptionsSnapshot implements IPCPacketBase {

	public static final int ID = 0x20;

	public int renderDistance;
	public int simulationDistance;
	public float entityDistanceScaling;
	public boolean pauseRequested;

	public IPCPacket20OptionsSnapshot() {
	}

	public IPCPacket20OptionsSnapshot(int renderDistance, int simulationDistance, float entityDistanceScaling,
			boolean pauseRequested) {
		this.renderDistance = renderDistance;
		this.simulationDistance = simulationDistance;
		this.entityDistanceScaling = entityDistanceScaling;
		this.pauseRequested = pauseRequested;
	}

	@Override
	public void deserialize(DataInput bin) throws IOException {
		renderDistance = bin.readInt();
		simulationDistance = bin.readInt();
		entityDistanceScaling = bin.readFloat();
		pauseRequested = bin.readBoolean();
	}

	@Override
	public void serialize(DataOutput bin) throws IOException {
		bin.writeInt(renderDistance);
		bin.writeInt(simulationDistance);
		bin.writeFloat(entityDistanceScaling);
		bin.writeBoolean(pauseRequested);
	}

	@Override
	public int id() {
		return ID;
	}

	@Override
	public int size() {
		return 13;
	}

}
