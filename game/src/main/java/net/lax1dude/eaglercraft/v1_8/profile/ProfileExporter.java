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

package net.lax1dude.eaglercraft.v1_8.profile;

import java.io.IOException;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.sp.server.export.EPKCompiler;

/**
 * 26.2 port of the upstream profile exporter: packs the raw EagRuntime
 * storage blobs for the profile ("p") and game settings ("g") keys into a
 * gzip EPK v2.0 backup and hands it to the platform download hook. Mirrors
 * the upstream file naming ("profile.epk" internal name, "_eaglercraftX.p"/
 * "_eaglercraftX.g" entries, "<username>-backup.epk" download) with the
 * file-type header versioned for this port ("epk/profile262", the upstream
 * 1.8.8 client uses "epk/profile188"). Relay/server-list/resource-pack
 * exports from upstream do not exist in the 26.2 port.
 */
public class ProfileExporter {

	/** upstream 1.8.8 uses "epk/profile188"; the 26.2 storage formats differ */
	public static final String PROFILE_EPK_TYPE = "epk/profile262";

	public static void exportProfileAndSettings() throws IOException {
		EaglerProfile.readIfNeeded();
		EaglerProfile.save();
		byte[] profileData = EagRuntime.getStorage("p");
		if(profileData == null) {
			throw new IOException("Could not write profile data!");
		}
		byte[] settingsData = EagRuntime.getStorage("g");

		String comment = "\n\n #  Eaglercraft 26.2 profile backup - \"" + EaglerProfile.getName() + "\""
				+ "\n #  Contains: profile" + (settingsData != null ? " settings" : "") + "\n\n";

		EPKCompiler epk = new EPKCompiler("profile", EaglerProfile.getName(), PROFILE_EPK_TYPE, true, false, comment);
		epk.append("_eaglercraftX.p", profileData);
		if(settingsData != null) {
			epk.append("_eaglercraftX.g", settingsData);
		}

		EagRuntime.downloadFileWithName(EaglerProfile.getName() + "-backup.epk", epk.complete());
	}

}
