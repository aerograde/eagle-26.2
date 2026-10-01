/*
 * 26.2 addition to the upstream IPC set: periodic server tick-time samples
 * (nanos per TpsDebugDimensions slot) for the client debug overlay, replacing
 * IntegratedServer's direct writes into the client-owned LocalSampleLogger.
 */

package net.lax1dude.eaglercraft.v1_8.sp.ipc;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

public class IPCPacket21TickSamples implements IPCPacketBase {

	public static final int ID = 0x21;

	/** Each entry: one tick's sample vector (dimension count is stable per session). */
	public final List<long[]> samples;
	public long completedTicks;
	public long elapsedMillis;
	public boolean paused;

	public IPCPacket21TickSamples() {
		samples = new LinkedList<>();
	}

	public IPCPacket21TickSamples(List<long[]> samples) {
		this.samples = samples;
	}

	public IPCPacket21TickSamples(List<long[]> samples, long completedTicks, long elapsedMillis, boolean paused) {
		this.samples = samples;
		this.completedTicks = completedTicks;
		this.elapsedMillis = elapsedMillis;
		this.paused = paused;
	}

	/** Count actual completions over wall time, including delayed tick wakeups. */
	public double ticksPerSecond() {
		return elapsedMillis > 0L ? Math.max(0.0, completedTicks * 1000.0 / elapsedMillis) : 0.0;
	}

	@Override
	public void deserialize(DataInput bin) throws IOException {
		samples.clear();
		int count = bin.readInt();
		int width = bin.readInt();
		if(count < 0 || count > 240 || width < 0 || width > 16 || (count > 0 && width == 0)) {
			throw new IOException("Invalid tick sample dimensions");
		}
		for(int i = 0; i < count; ++i) {
			long[] vec = new long[width];
			for(int j = 0; j < width; ++j) {
				vec[j] = bin.readLong();
			}
			samples.add(vec);
		}
		completedTicks = bin.readLong();
		elapsedMillis = bin.readLong();
		paused = bin.readBoolean();
	}

	@Override
	public void serialize(DataOutput bin) throws IOException {
		int count = samples.size();
		int width = count > 0 ? samples.get(0).length : 0;
		bin.writeInt(count);
		bin.writeInt(width);
		for(int i = 0; i < count; ++i) {
			long[] vec = samples.get(i);
			for(int j = 0; j < width; ++j) {
				bin.writeLong(vec[j]);
			}
		}
		bin.writeLong(completedTicks);
		bin.writeLong(elapsedMillis);
		bin.writeBoolean(paused);
	}

	@Override
	public int id() {
		return ID;
	}

	@Override
	public int size() {
		int width = samples.isEmpty() ? 0 : samples.get(0).length;
		return 25 + samples.size() * width * 8;
	}

}
