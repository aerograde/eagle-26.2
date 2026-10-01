/*
 * Copyright (c) 2022-2025 lax1dude. All Rights Reserved.
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

package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import org.teavm.jso.JSBody;

/**
 * Upstream: EaglercraftX-1.8-workspace-master src/teavm MainClass — the TeaVM
 * entry point ({@code teavm.all.mainClass} in :target_teavm). {@code main([])}
 * boots the client; {@code main(["_worker_process_"])} would boot the integrated
 * server worker.
 *
 * Phase 3.2a: the worker branch is deferred (the SP worker data-plane over the
 * postMessage pipe is Phase 3.4). Client boot goes through {@link ClientMain}.
 */
public class MainClass {

	public static void main(String[] args) {
		setStackTraceLimit();
		if(args.length == 1) {
			if("_worker_process_".equalsIgnoreCase(args[0])) {
				// Mesh-worker plan Phase A: a worker booted from the same classes.js
				// Blob installs the eag26 two-channel handlers and learns its role from
				// the first {meta:"role:mesh|mip:N"} message, then echoes mesh jobs
				// (decode+re-encode) to prove the codec+transfer round-trip.
				// TODO(3.4): the integrated-server worker (role:server) over the
				//            postMessage pipe is still deferred; MeshWorkerMain.onMeta
				//            already ignores non-mesh roles so the two can coexist.
				net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerMain.workerMain();
				return;
			}
		}else if(args.length == 0) {
			clientMain();
			return;
		}
		System.out.println("???");
	}

	private static void clientMain() {
		ClientMain._main();
	}

	@JSBody(script = "Error.stackTraceLimit = 1024;")
	private static native void setStackTraceLimit();

}
