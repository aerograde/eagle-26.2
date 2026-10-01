/*
 * Copyright (c) 2025 lax1dude. All Rights Reserved.
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

package net.lax1dude.eaglercraft.v1_8.sp.server.internal;

import java.util.List;
import java.util.function.Consumer;

import net.lax1dude.eaglercraft.v1_8.internal.IClientConfigAdapter;
import net.lax1dude.eaglercraft.v1_8.internal.IEaglerFilesystem;
import net.lax1dude.eaglercraft.v1_8.internal.IPCPacketData;
import net.lax1dude.eaglercraft.v1_8.sp.server.IWASMCrashCallback;

public class ServerPlatformSingleplayer {

	public static native void initializeContext();

	public static native IEaglerFilesystem getWorldsDatabase();

	public static native void initializeContextSingleThread(Consumer<IPCPacketData> packetSendCallback);

	public static native void sendPacket(IPCPacketData packet);

	/** serverWorker data plane (Phase 3.4 seam c): post one vanilla-packet batch
	 *  (worker -> page) on the given channel, transferring the backing buffer
	 *  (zero-copy). No-op on desktop (in-JVM LocalChannel path). */
	public static native void sendDataBatch(String channel, byte[] batch);

	public static native List<IPCPacketData> recieveAllPacket();

	public static native void immediateContinue();

	public static native void tickContinue(long millis);

	public static native boolean isPerfDebugEnabled();

	/** Diagnostic A/B only: true caps hosted-web unload execution to four tasks/tick. */
	public static native boolean isChunkUnloadHardCapEnabled();

	public static native IClientConfigAdapter getClientConfigAdapter();

	public static native boolean isSingleThreadMode();

	public static native void setCrashCallbackWASM(IWASMCrashCallback callback);

	public static native boolean isTabAboutToCloseWASM();

}
