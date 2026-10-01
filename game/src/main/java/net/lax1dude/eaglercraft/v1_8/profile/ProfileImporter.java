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
import net.lax1dude.eaglercraft.v1_8.sp.server.export.EPKDecompiler;
import net.minecraft.client.Minecraft;

/**
 * 26.2 port of the upstream profile importer: unpacks a profile backup EPK
 * produced by ProfileExporter, writes the raw blobs back into EagRuntime
 * storage per key, and reloads the live state — EaglerProfile.read() for the
 * "p" entry and Options.load() for the "g" entry. Rejects EPKs whose
 * file-type header is not "epk/profile262" (including upstream 1.8.8
 * "epk/profile188" backups, whose settings format is incompatible).
 */
public class ProfileImporter {

	public static void importProfileAndSettings(byte[] data) throws IOException {
		try(EPKDecompiler epk = new EPKDecompiler(data)) {
			EPKDecompiler.FileEntry etr = epk.readFile();
			if(etr == null || !"HEAD".equals(etr.type) || !"file-type".equals(etr.name)
					|| !ProfileExporter.PROFILE_EPK_TYPE.equals(EPKDecompiler.readASCII(etr.data))) {
				throw new IOException("EPK file is not an Eaglercraft 26.2 profile backup!");
			}
			while((etr = epk.readFile()) != null) {
				if("FILE".equals(etr.type)) {
					if("_eaglercraftX.p".equals(etr.name)) {
						EagRuntime.setStorage("p", etr.data);
						EaglerProfile.read(etr.data);
					}else if("_eaglercraftX.g".equals(etr.name)) {
						EagRuntime.setStorage("g", etr.data);
						Minecraft.getInstance().options.load();
						// Options.load() accepts the imported g blob for compatibility;
						// save it once so the durable game-directory VFS does not keep
						// stale options.txt over the next browser restart.
						Minecraft.getInstance().options.save();
					}
				}
			}
		}
	}

}
