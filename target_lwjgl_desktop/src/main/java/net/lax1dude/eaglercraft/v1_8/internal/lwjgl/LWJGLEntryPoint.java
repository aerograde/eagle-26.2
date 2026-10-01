/*
 * Copyright (c) 2022-2023 lax1dude. All Rights Reserved.
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

package net.lax1dude.eaglercraft.v1_8.internal.lwjgl;

import java.util.ArrayList;
import java.util.List;

import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformInput;

/**
 * Upstream: EaglercraftX-1.8-workspace-master src/lwjgl LWJGLEntryPoint.
 * 26.2 adaptation: launches Minecraft 26.2 in HOSTED mode — the game's blaze3d
 * Window owns the GLFW window and GL 3.3 core context (GpuDeviceBackend seam),
 * the Eagler runtime provides everything else.
 * Deviations from upstream (see docs/gap-analysis.md):
 *  - GLES version / ANGLE / highp shader flags removed (PlatformOpenGL superseded)
 *  - RelayManager preload deferred with the rest of multiplayer (Phase 3+)
 */
public class LWJGLEntryPoint {

	public static Thread mainThread = null;

	public static void main_(String[] args) {
		mainThread = Thread.currentThread();

		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
				| UnsupportedLookAndFeelException e) {
			System.err.println("Could not set system look and feel: " + e.toString());
		}

		boolean hideRenderDocDialog = false;
		for(int i = 0; i < args.length; ++i) {
			if(args[i].equalsIgnoreCase("hide-renderdoc")) {
				hideRenderDocDialog = true;
			}
		}

		if(!hideRenderDocDialog) {
			LaunchRenderDocDialog lr = new LaunchRenderDocDialog();
			lr.setLocationRelativeTo(null);
			lr.setVisible(true);

			while(lr.isVisible()) {
				EagUtils.sleep(100);
			}

			lr.dispose();
		}

		List<String> vanillaArgs = getPlatformOptionsFromArgs(args);

		System.setProperty("eagler.hosted", "true");
		EagRuntime.create();

		// Eagler worker split (Phase 2d): the integrated-server worker main lives in
		// the game module; inject it into the desktop worker thread launcher
		net.lax1dude.eaglercraft.v1_8.sp.server.internal.lwjgl.DesktopIntegratedServer
				.setServerMain(net.lax1dude.eaglercraft.v1_8.sp.server.EaglerIntegratedServerWorker26::serverMain);

		try {
			net.minecraft.client.main.Main.main(vanillaArgs.toArray(new String[0]));
		}catch(Throwable t) {
			showCrashPopup(t);
			throw t;
		}
	}

	/**
	 * Eagler-style crash handling (upstream CrashScreenPopup, recopied): a crash
	 * before/outside the game's own CrashReport path still gets a visible screen.
	 */
	private static void showCrashPopup(Throwable t) {
		try {
			java.io.StringWriter sw = new java.io.StringWriter();
			t.printStackTrace(new java.io.PrintWriter(sw));
			net.lax1dude.eaglercraft.v1_8.sp.server.internal.lwjgl.CrashScreenPopup popup =
					new net.lax1dude.eaglercraft.v1_8.sp.server.internal.lwjgl.CrashScreenPopup();
			popup.setTitle("Eaglercraft 26.2 - Crash Report");
			popup.setDefaultCloseOperation(javax.swing.JFrame.DISPOSE_ON_CLOSE);
			popup.setCrashText("EAGLERCRAFT 26.2 CRASHED!\n\n"
					+ "Full crash reports are written to the \"run/crash-reports\" folder\n\n" + sw);
			popup.setLocationRelativeTo(null);
			popup.setVisible(true);
			while(popup.isVisible()) {
				EagUtils.sleep(100);
			}
			popup.dispose();
		}catch(Throwable t2) {
			System.err.println("Could not display crash popup: " + t2);
		}
	}

	/**
	 * Consumes eagler-runtime flags; everything else is passed through to the
	 * vanilla 26.2 Main (jopt-simple arguments like --gameDir, --assetsDir...).
	 */
	private static List<String> getPlatformOptionsFromArgs(String[] args) {
		List<String> vanillaArgs = new ArrayList<>(args.length);
		for(int i = 0; i < args.length; ++i) {
			if(args[i].equalsIgnoreCase("fullscreen")) {
				PlatformInput.setStartupFullscreen(true);
			}else if(args[i].equalsIgnoreCase("hide-renderdoc")) {
				// already handled
			}else {
				vanillaArgs.add(args[i]);
			}
		}
		return vanillaArgs;
	}

}
