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

import net.minecraft.resources.Identifier;

/**
 * 26.2 port of the upstream default cape gallery: the 25 preset capes plus
 * "No Cape" (null location), served from assets/eagler/capes/ on the
 * classpath. The shipped PNGs were expanded from the upstream 32x32 layout to
 * the vanilla 64x32 cape layout (content at the top-left) so they sample
 * correctly through the 26.2 PlayerCapeModel, whose cape cube is baked with
 * texScale 1.0x0.5 against the declared 64x64 size (effective 64x32).
 */
public enum DefaultCapes {

	NO_CAPE(0, "No Cape", null),
	MINECON_2011(1, "Minecon 2011", Identifier.parse("eagler:capes/01.minecon_2011.png")),
	MINECON_2012(2, "Minecon 2012", Identifier.parse("eagler:capes/02.minecon_2012.png")),
	MINECON_2013(3, "Minecon 2013", Identifier.parse("eagler:capes/03.minecon_2013.png")),
	MINECON_2015(4, "Minecon 2015", Identifier.parse("eagler:capes/04.minecon_2015.png")),
	MINECON_2016(5, "Minecon 2016", Identifier.parse("eagler:capes/05.minecon_2016.png")),
	MICROSOFT_ACCOUNT(6, "Microsoft Account", Identifier.parse("eagler:capes/06.microsoft_account.png")),
	MAPMAKER(7, "Realms Mapmaker", Identifier.parse("eagler:capes/07.mapmaker.png")),
	MOJANG_OLD(8, "Mojang Old", Identifier.parse("eagler:capes/08.mojang_old.png")),
	MOJANG_NEW(9, "Mojang New", Identifier.parse("eagler:capes/09.mojang_new.png")),
	JIRA_MOD(10, "Jira Moderator", Identifier.parse("eagler:capes/10.jira_mod.png")),
	MOJANG_VERY_OLD(11, "Mojang Very Old", Identifier.parse("eagler:capes/11.mojang_very_old.png")),
	SCROLLS(12, "Scrolls", Identifier.parse("eagler:capes/12.scrolls.png")),
	COBALT(13, "Cobalt", Identifier.parse("eagler:capes/13.cobalt.png")),
	TRANSLATOR(14, "Lang Translator", Identifier.parse("eagler:capes/14.translator.png")),
	MILLIONTH_ACCOUNT(15, "Millionth Player", Identifier.parse("eagler:capes/15.millionth_account.png")),
	PRISMARINE(16, "Prismarine", Identifier.parse("eagler:capes/16.prismarine.png")),
	SNOWMAN(17, "Snowman", Identifier.parse("eagler:capes/17.snowman.png")),
	SPADE(18, "Spade", Identifier.parse("eagler:capes/18.spade.png")),
	BIRTHDAY(19, "Birthday", Identifier.parse("eagler:capes/19.birthday.png")),
	DB(20, "dB", Identifier.parse("eagler:capes/20.db.png")),
	_15TH_ANNIVERSARY(21, "15th Anniversary", Identifier.parse("eagler:capes/21.15th_anniversary.png")),
	VANILLA(22, "Vanilla", Identifier.parse("eagler:capes/22.vanilla.png")),
	TIKTOK(23, "TikTok", Identifier.parse("eagler:capes/23.tiktok.png")),
	PURPLE_HEART(24, "Purple Heart", Identifier.parse("eagler:capes/24.purple_heart.png")),
	CHERRY_BLOSSOM(25, "Cherry Blossom", Identifier.parse("eagler:capes/25.cherry_blossom.png"));

	public static final DefaultCapes[] defaultCapesMap = new DefaultCapes[26];

	public final int id;
	public final String name;
	public final Identifier location;

	private DefaultCapes(int id, String name, Identifier location) {
		this.id = id;
		this.name = name;
		this.location = location;
	}

	public static DefaultCapes getCapeFromId(int id) {
		DefaultCapes e = null;
		if(id >= 0 && id < defaultCapesMap.length) {
			e = defaultCapesMap[id];
		}
		if(e != null) {
			return e;
		}else {
			return NO_CAPE;
		}
	}

	static {
		DefaultCapes[] capes = values();
		for(int i = 0; i < capes.length; ++i) {
			defaultCapesMap[capes[i].id] = capes[i];
		}
	}

}
