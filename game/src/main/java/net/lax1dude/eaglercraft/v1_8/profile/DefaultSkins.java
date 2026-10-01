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

import net.minecraft.resources.Identifier;

/**
 * 26.2 port of the upstream default skin gallery: the 24 standard entries
 * (ids 0-23), served from assets/eagler/skins/ on the classpath through the
 * vanilla pack's "eagler" namespace. The FNAW high-poly entries (24-28) are
 * dropped in the 26.2 port.
 */
public enum DefaultSkins {

	DEFAULT_STEVE(0, "Default Steve", Identifier.parse("eagler:skins/01.default_steve.png"), SkinModel.STEVE),
	DEFAULT_ALEX(1, "Default Alex", Identifier.parse("eagler:skins/02.default_alex.png"), SkinModel.ALEX),
	TENNIS_STEVE(2, "Tennis Steve", Identifier.parse("eagler:skins/03.tennis_steve.png"), SkinModel.STEVE),
	TENNIS_ALEX(3, "Tennis Alex", Identifier.parse("eagler:skins/04.tennis_alex.png"), SkinModel.ALEX),
	TUXEDO_STEVE(4, "Tuxedo Steve", Identifier.parse("eagler:skins/05.tuxedo_steve.png"), SkinModel.STEVE),
	TUXEDO_ALEX(5, "Tuxedo Alex", Identifier.parse("eagler:skins/06.tuxedo_alex.png"), SkinModel.ALEX),
	ATHLETE_STEVE(6, "Athlete Steve", Identifier.parse("eagler:skins/07.athlete_steve.png"), SkinModel.STEVE),
	ATHLETE_ALEX(7, "Athlete Alex", Identifier.parse("eagler:skins/08.athlete_alex.png"), SkinModel.ALEX),
	CYCLIST_STEVE(8, "Cyclist Steve", Identifier.parse("eagler:skins/09.cyclist_steve.png"), SkinModel.STEVE),
	CYCLIST_ALEX(9, "Cyclist Alex", Identifier.parse("eagler:skins/10.cyclist_alex.png"), SkinModel.ALEX),
	BOXER_STEVE(10, "Boxer Steve", Identifier.parse("eagler:skins/11.boxer_steve.png"), SkinModel.STEVE),
	BOXER_ALEX(11, "Boxer Alex", Identifier.parse("eagler:skins/12.boxer_alex.png"), SkinModel.ALEX),
	PRISONER_STEVE(12, "Prisoner Steve", Identifier.parse("eagler:skins/13.prisoner_steve.png"), SkinModel.STEVE),
	PRISONER_ALEX(13, "Prisoner Alex", Identifier.parse("eagler:skins/14.prisoner_alex.png"), SkinModel.ALEX),
	SCOTTISH_STEVE(14, "Scottish Steve", Identifier.parse("eagler:skins/15.scottish_steve.png"), SkinModel.STEVE),
	SCOTTISH_ALEX(15, "Scottish Alex", Identifier.parse("eagler:skins/16.scottish_alex.png"), SkinModel.ALEX),
	DEVELOPER_STEVE(16, "Developer Steve", Identifier.parse("eagler:skins/17.developer_steve.png"), SkinModel.STEVE),
	DEVELOPER_ALEX(17, "Developer Alex", Identifier.parse("eagler:skins/18.developer_alex.png"), SkinModel.ALEX),
	HEROBRINE(18, "Herobrine", Identifier.parse("eagler:skins/19.herobrine.png"), SkinModel.ZOMBIE),
	NOTCH(19, "Notch", Identifier.parse("eagler:skins/20.notch.png"), SkinModel.STEVE),
	CREEPER(20, "Creeper", Identifier.parse("eagler:skins/21.creeper.png"), SkinModel.STEVE),
	ZOMBIE(21, "Zombie", Identifier.parse("eagler:skins/22.zombie.png"), SkinModel.ZOMBIE),
	PIG(22, "Pig", Identifier.parse("eagler:skins/23.pig.png"), SkinModel.STEVE),
	MOOSHROOM(23, "Mooshroom", Identifier.parse("eagler:skins/24.mooshroom.png"), SkinModel.STEVE);

	public static final DefaultSkins[] defaultSkinsMap = new DefaultSkins[24];

	public final int id;
	public final String name;
	public final Identifier location;
	public final SkinModel model;

	private DefaultSkins(int id, String name, Identifier location, SkinModel model) {
		this.id = id;
		this.name = name;
		this.location = location;
		this.model = model;
	}

	public static DefaultSkins getSkinFromId(int id) {
		DefaultSkins e = null;
		if(id >= 0 && id < defaultSkinsMap.length) {
			e = defaultSkinsMap[id];
		}
		if(e != null) {
			return e;
		}else {
			return DEFAULT_STEVE;
		}
	}

	static {
		DefaultSkins[] skins = values();
		for(int i = 0; i < skins.length; ++i) {
			defaultSkinsMap[skins[i].id] = skins[i];
		}
	}

}
