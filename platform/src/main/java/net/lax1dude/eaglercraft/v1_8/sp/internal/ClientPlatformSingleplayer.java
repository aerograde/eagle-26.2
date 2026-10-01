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

package net.lax1dude.eaglercraft.v1_8.sp.internal;

import java.util.List;

import net.lax1dude.eaglercraft.v1_8.internal.IPCPacketData;

public class ClientPlatformSingleplayer {

	public static native void startIntegratedServer(boolean singleThreadMode);

	public static native void sendPacket(IPCPacketData packet);

	public static native List<IPCPacketData> recieveAllPacket();

	public static native boolean canKillWorker();

	public static native void killWorker();

	public static native boolean isRunningSingleThreadMode();

	public static native boolean isSingleThreadModeSupported();

	/** True when the integrated server is running in a dedicated Web Worker
	 *  (serverWorker mode); false for single-thread mode and on desktop. */
	public static native boolean isServerWorkerMode();

	/** True when serverWorker mode is REQUESTED (?serverworker / opts.serverWorker AND Worker
	 *  is supported), resolvable BEFORE the worker is booted (unlike {@link #isServerWorkerMode()}
	 *  which only flips once the worker path is actually taken). Used by the client to persist
	 *  level.dat to the shared VFS before the worker builds the world. false on desktop. */
	public static native boolean isServerWorkerModeRequested();

	/** serverWorker data plane (Phase 3.4 seam c): post one vanilla-packet batch
	 *  (page -> worker) on the given channel, transferring the backing buffer
	 *  (zero-copy). No-op in single-thread mode and on desktop (LocalChannel path). */
	public static native void sendDataBatch(String channel, byte[] batch);

	/** Open an authenticated relay room for the current integrated-server world. */
	public static native boolean openLANRelay(String hostURI, int timeoutMillis);

	public static native void updateLANRelay();

	public static native boolean isLANRelayOpen();

	public static native String getLANRelayCode();

	public static native String getLANRelayError();

	public static native void closeLANRelay();

	/** Direct connect (relay-free): mint the offer code for the next guest. */
	public static native String directHostNewInviteCode();

	/** Direct connect: apply the guest's answer code to the room's pending invite. */
	public static native void directHostCompleteAnswer(String answerCode);

	/** Direct connect: true while this host's room is open. */
	public static native boolean isDirectRoomHosting();

	/** Direct connect: guest links in the room, connected or still handshaking. */
	public static native int getDirectGuestCount();

	/** Direct connect: guest links whose data channel is actually open. */
	public static native int getDirectConnectedGuestCount();

	/** Direct connect (join side): create an answer code from a pasted offer code. */
	public static native String directJoinCreateAnswerCode(String offerCode);

	/** Direct connect: true when the join-side data channel is actually open. */
	public static native boolean isDirectJoinConnected();

	/** Direct connect: release the current session (host or join side). */
	public static native void closeDirectConnect();
	public static native void updateSingleThreadMode();

	public static native void showCrashReportOverlay(String report, int x, int y, int w, int h);

	public static native void hideCrashReportOverlay();

}
