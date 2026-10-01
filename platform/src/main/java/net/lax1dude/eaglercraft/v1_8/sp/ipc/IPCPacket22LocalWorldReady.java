/*
 * 26.2 addition to the upstream IPC set: the worker announces the integrated
 * server is ready and (on desktop) carries the netty LocalAddress id so the
 * client can connect its LocalChannel data plane. On the web target the
 * address field is unused (the player channel rides the IPC pipe instead).
 */

package net.lax1dude.eaglercraft.v1_8.sp.ipc;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class IPCPacket22LocalWorldReady implements IPCPacketBase {

	public static final int ID = 0x22;

	public String localAddress;

	public IPCPacket22LocalWorldReady() {
	}

	public IPCPacket22LocalWorldReady(String localAddress) {
		this.localAddress = localAddress;
	}

	@Override
	public void deserialize(DataInput bin) throws IOException {
		localAddress = bin.readUTF();
	}

	@Override
	public void serialize(DataOutput bin) throws IOException {
		bin.writeUTF(localAddress);
	}

	@Override
	public int id() {
		return ID;
	}

	@Override
	public int size() {
		return 2 + localAddress.length();
	}

}
