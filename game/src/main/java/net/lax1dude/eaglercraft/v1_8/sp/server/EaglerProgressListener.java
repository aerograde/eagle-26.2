package net.lax1dude.eaglercraft.v1_8.sp.server;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Forwards the integrated server's world-load progress onto the IPC 0x0D pipe
 * (upstream: the worker's IProgressUpdate wiring) — the client busy screen renders
 * it. Runs on the server thread; the IPC send is thread-safe.
 */
public class EaglerProgressListener implements LevelLoadListener {

	public static final EaglerProgressListener INSTANCE = new EaglerProgressListener();

	private static String stageKey(Stage stage) {
		switch(stage) {
		case START_SERVER: return "singleplayer.busy.startingIntegratedServer";
		case PREPARE_GLOBAL_SPAWN: return "singleplayer.busy.preparingSpawn";
		case LOAD_INITIAL_CHUNKS: return "singleplayer.busy.loadingChunks";
		case LOAD_PLAYER_CHUNKS: default: return "singleplayer.busy.loadingPlayerChunks";
		}
	}

	@Override
	public void start(Stage stage, int totalChunks) {
		EaglerIntegratedServerWorker26.reportProgress(stageKey(stage), totalChunks > 0 ? 0.0f : -1.0f);
	}

	@Override
	public void update(Stage stage, int currentChunks, int totalChunks) {
		EaglerIntegratedServerWorker26.reportProgress(stageKey(stage),
				totalChunks > 0 ? (float) currentChunks / (float) totalChunks : -1.0f);
	}

	@Override
	public void finish(Stage stage) {
		EaglerIntegratedServerWorker26.reportProgress(stageKey(stage), 1.0f);
	}

	@Override
	public void updateFocus(ResourceKey<Level> dimension, ChunkPos chunkPos) {
	}

}
