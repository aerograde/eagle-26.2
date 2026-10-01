/*
 * Copyright (c) 2024 lax1dude. All Rights Reserved.
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

// 26.2 adaptations (Phase 3.1, see report):
//  - upstream calls EaglerIntegratedServerWorker.singleThreadMain()/singleThreadUpdate()
//    directly; that class lives in the :game module, which is not on this backend's
//    classpath. Mirroring the sibling :platform-lwjgl DesktopIntegratedServer seam,
//    the worker main/update are decoupled behind injected Runnables that the target
//    module wires up before starting the integrated server. TODO(3.2): wire the game
//    module's EaglerIntegratedServerWorker into these seams at boot.

package net.lax1dude.eaglercraft.v1_8.sp.server.internal.teavm;

import java.util.function.Consumer;

import net.lax1dude.eaglercraft.v1_8.internal.IPCPacketData;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.sp.server.internal.ServerPlatformSingleplayer;

public class SingleThreadWorker {

	private static final Logger logger = LogManager.getLogger("SingleThreadWorker");

	// 26.2: EaglerIntegratedServerWorker.singleThreadMain() equivalent, injected by
	// the game/target module (see class header)
	private static Runnable singleThreadMainEntry = null;

	// 26.2: EaglerIntegratedServerWorker.singleThreadUpdate() equivalent, injected by
	// the game/target module (see class header)
	private static Runnable singleThreadUpdateEntry = null;

	public static void setSingleThreadServerMain(Runnable entry) {
		singleThreadMainEntry = entry;
	}

	public static void setSingleThreadServerUpdate(Runnable entry) {
		singleThreadUpdateEntry = entry;
	}

	public static void singleThreadStartup(Consumer<IPCPacketData> packetSendCallback) {
		logger.info("Starting single-thread mode worker...");
		ServerPlatformSingleplayer.initializeContextSingleThread(packetSendCallback);
		if(singleThreadMainEntry != null) {
			singleThreadMainEntry.run();
		}else {
			// TODO(3.2): EaglerIntegratedServerWorker.singleThreadMain();
			logger.error("Single-thread integrated server main is not wired up yet (needs game module EaglerIntegratedServerWorker, later increment)");
		}
	}

	public static void sendPacketToWorker(IPCPacketData pkt) {
		ServerPlatformSingleplayer.recievePacketSingleThreadTeaVM(pkt);
	}

	public static void singleThreadUpdate() {
		if(singleThreadUpdateEntry != null) {
			singleThreadUpdateEntry.run();
		}else {
			// TODO(3.2): EaglerIntegratedServerWorker.singleThreadUpdate();
		}
	}

}
