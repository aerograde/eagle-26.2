/*
 * Copyright (c) 2022-2023 lax1dude, ayunami2000. All Rights Reserved.
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
 * 26.2 port of the upstream custom skin holder: keeps the raw 64x64 skin as
 * the upstream 16384-byte array (4 bytes per pixel, same byte order as the
 * upstream NBT "data" field) and lazily registers a DynamicTexture with the
 * 26.2 TextureManager under eagler:skins/custom/tex_N on first use.
 */
public class CustomSkin {

	public final String name;
	public final byte[] texture;
	public SkinModel model;

	private Identifier resourceLocation;

	private static int texId = 0;

	public CustomSkin(String name, byte[] texture, SkinModel model) {
		this.name = name;
		this.texture = texture;
		this.model = model;
		this.resourceLocation = null;
	}

	public Identifier getResource() {
		if(resourceLocation == null) {
			Identifier loc = Identifier.parse("eagler:skins/custom/tex_" + texId++);
			NativeImage img = new NativeImage(model.width, model.height, false);
			for(int y = 0; y < model.height; ++y) {
				for(int x = 0; x < model.width; ++x) {
					int j = (y * model.width + x) << 2;
					img.setPixelABGR(x, y, (((int) texture[j] & 0xFF) << 24) | (((int) texture[j + 1] & 0xFF) << 16)
							| (((int) texture[j + 2] & 0xFF) << 8) | ((int) texture[j + 3] & 0xFF));
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
