/*
 * Copyright (c) 2022 lax1dude. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 */

package net.lax1dude.eaglercraft.v1_8.sp.ipc;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * 26.2 upgrade of upstream IPCPacket16NBTList: the wire format (opCode + list of
 * gzip-NBT byte blobs) is IDENTICAL to 1.8, but this class no longer decodes NBT —
 * the platform module must not depend on game NBT classes. The game module
 * (worker/controller glue) encodes level.dat compounds to bytes and back.
 */
public class IPCPacket16NBTList implements IPCPacketBase {

	public static final int ID = 0x16;

	public static final int WORLD_LIST = 0x0;

	public int opCode;
	public final List<byte[]> tagList;

	public IPCPacket16NBTList() {
		tagList = new LinkedList<>();
	}

	public IPCPacket16NBTList(int opcode, List<byte[]> list) {
		opCode = opcode;
		tagList = list;
	}

	@Override
	public void deserialize(DataInput bin) throws IOException {
		tagList.clear();
		opCode = bin.readInt();
		int count = bin.readInt();
		for(int i = 0; i < count; ++i) {
			byte[] toRead = new byte[bin.readInt()];
			bin.readFully(toRead);
			tagList.add(toRead);
		}
	}

	@Override
	public void serialize(DataOutput bin) throws IOException {
		bin.writeInt(opCode);
		int l = tagList.size();
		bin.writeInt(l);
		for(int i = 0; i < l; ++i) {
			byte[] b = tagList.get(i);
			bin.writeInt(b.length);
			bin.write(b);
		}
	}

	@Override
	public int id() {
		return ID;
	}

	@Override
	public int size() {
		int len = 8;
		for(int i = 0, l = tagList.size(); i < l; ++i) {
			len += 4 + tagList.get(i).length;
		}
		return len;
	}

}
