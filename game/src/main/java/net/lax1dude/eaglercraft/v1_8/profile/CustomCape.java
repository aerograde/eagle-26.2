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

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * 26.2 port of the upstream custom cape holder: keeps the raw cape as the
 * upstream 1173-byte 23x17 RGB blob (same byte layout as the upstream NBT
 * "data" field), expands it with SkinConverter.convertCape23x17RGBto32x32RGBA
 * exactly like upstream, and lazily registers a 64x32 DynamicTexture (32x32
 * content at the left, matching the effective 26.2 PlayerCapeModel UV layout:
 * the cape cube is baked with texScale 1.0x0.5 against the declared 64x64
 * size, i.e. a vanilla 64x32 cape texture) with the TextureManager under
 * eagler:capes/custom/tex_N on first use.
 */
public class CustomCape {

	public final String name;
	public final byte[] texture;

	private Identifier resourceLocation;

	private static int texId = 0;

	public CustomCape(String name, byte[] texture) {
		this.name = name;
		this.texture = texture;
		this.resourceLocation = null;
	}

	public Identifier getResource() {
		if(resourceLocation == null) {
			byte[] texture2 = new byte[4096];
			SkinConverter.convertCape23x17RGBto32x32RGBA(texture, texture2);
			Identifier loc = Identifier.parse("eagler:capes/custom/tex_" + texId++);
			NativeImage img = new NativeImage(64, 32, true);
			for(int y = 0; y < 32; ++y) {
				for(int x = 0; x < 32; ++x) {
					int j = ((y << 5) | x) << 2;
					img.setPixelABGR(x, y, (((int) texture2[j] & 0xFF) << 24) | (((int) texture2[j + 1] & 0xFF) << 16)
							| (((int) texture2[j + 2] & 0xFF) << 8) | ((int) texture2[j + 3] & 0xFF));
				}
			}
			Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(loc::toString, img));
			resourceLocation = loc;
		}
		return resourceLocation;
	}

	public void delete() {
		if(resourceLocation != null) {
			Minecraft.getInstance().getTextureManager().release(resourceLocation);
			resourceLocation = null;
		}
	}

}
