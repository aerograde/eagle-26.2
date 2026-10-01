package net.minecraft.client.server;

import java.net.SocketAddress;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.PlayerDataStorage;

public class IntegratedPlayerList extends PlayerList {
   public IntegratedPlayerList(
      final IntegratedServer server, final LayeredRegistryAccess<RegistryLayer> registryHolder, final PlayerDataStorage playerDataStorage
	   ) {
	      super(server, registryHolder, playerDataStorage, server.notificationManager());
	      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
	         // Bootstrap only the nearest 5x5 area. IntegratedServer expands to the
	         // requested distance after login, once the center has priority and is playable.
	         this.setViewDistance(2);
	         this.setSimulationDistance(2);
	      } else {
	         this.setViewDistance(10);
	      }
	   }

   @Override
   public Component canPlayerLogin(final SocketAddress address, final NameAndId nameAndId) {
      return this.getServer().isSingleplayerOwner(nameAndId) && this.getPlayerByName(nameAndId.name()) != null
         ? Component.translatable("multiplayer.disconnect.name_taken")
         : super.canPlayerLogin(address, nameAndId);
   }

   public IntegratedServer getServer() {
      return (IntegratedServer)super.getServer();
   }
}
