package net.lax1dude.eaglercraft.v1_8.socket;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import org.json.JSONObject;

/** Public login identity only. Wispcraft retains ownership of account credentials. */
public final class WispcraftAccount {
   private WispcraftAccount() {}

   public static GameProfile fromProfileJSON(String json) {
      JSONObject profile = new JSONObject(json);
      String status = profile.optString("status", "invalid");
      if ("signed_out".equals(status)) return null;
      if (!"ready".equals(status)) {
         throw new IllegalStateException("Wispcraft account is not ready. Select or sign in to your account in Wisp Settings, then reconnect.");
      }
      String id = profile.optString("id", "");
      String name = profile.optString("name", "");
      if (!id.matches("[0-9a-fA-F]{32}") || !name.matches("[A-Za-z0-9_]{1,16}")) {
         throw new IllegalStateException("Wispcraft returned an invalid Minecraft profile. Sign in again in Wisp Settings.");
      }
      UUID uuid = UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-"
         + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20));
      return new GameProfile(uuid, name);
   }
}
