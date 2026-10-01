package net.lax1dude.eaglercraft.v1_8.minecraft;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Low-overhead rolling profiler for browser frame and mesh preparation phases. */
public final class EaglerClientPerf {

	private static final Logger LOGGER = LogUtils.getLogger();
	private static final long WINDOW_NANOS = 5_000_000_000L;
	private static boolean enabled;
	private static long windowStarted;
	private static long frames;
	private static long extractNanos;
	private static long renderNanos;
	private static long presentNanos;
	private static long limiterNanos;
	private static long frameNanos;
	private static long frameMaxNanos;
	private static long snapshots;
	private static long snapshotCaptureNanos;
	private static long snapshotEncodeNanos;
	private static long inlineCompiles;
	private static long inlineCompileNanos;
	private static long inlineCompileMaxNanos;
	private static long clientWorkSamples;
	private static long packetNanos;
	private static long packetMaxNanos;
	private static long packetsProcessed;
	private static int packetQueueDepth;
	private static int packetQueueDepthMax;
	private static long packetQueueOldestNanos;
	private static long packetQueueOldestMaxNanos;
	private static long taskNanos;
	private static long tickNanos;
	public static final int TICK_TOTAL = 0;
	public static final int TICK_SETUP = 1;
	public static final int TICK_GAME_MODE_PICK = 2;
	public static final int TICK_GUI_KEYS = 3;
	public static final int TICK_GAME_RENDERER = 4;
	public static final int TICK_ENTITIES = 5;
	public static final int TICK_BLOCK_ENTITIES = 6;
	public static final int TICK_MUSIC_SOUND = 7;
	public static final int TICK_LEVEL = 8;
	public static final int TICK_ANIMATE = 9;
	public static final int TICK_PARTICLES = 10;
	public static final int TICK_CONNECTION = 11;
	public static final int TICK_KEYBOARD = 12;
	private static final String[] TICK_PHASE_NAMES = { "total", "setup", "gameMode_pick", "gui_keys",
			"gameRenderer", "entities", "blockEntities", "music_sound", "level", "animateTick",
			"particles", "connection", "keyboard" };
	private static final long[] tickPhaseSamples = new long[TICK_PHASE_NAMES.length];
	private static final long[] tickPhaseNanos = new long[TICK_PHASE_NAMES.length];
	private static final long[] tickPhaseMaxNanos = new long[TICK_PHASE_NAMES.length];
	private static long textureTickSamples;
	private static long textureTickNanos;
	private static long textureTickMaxNanos;
	private static long particleTickSamples;
	private static long particleTickNanos;
	private static long particleTickMaxNanos;
	private static long particleLive;
	private static long particleLiveMax;
	private static long earlyParticlePacketsAdmitted;
	private static long earlyParticlePacketsDropped;
	private static long earlyParticleBytesAdmitted;
	private static long earlyParticleBytesDropped;
	private static long playerInputSamples;
	private static long playerInputActive;
	private static long playerInputJump;
	private static long playerInputShift;
	private static long playerInputCameraBlocked;
	private static long playerLocalMoveSamples;
	private static long playerMovePackets;
	private static long playerPositionCorrections;
	private static double playerPositionCorrectionDistance;
	private static double playerPositionCorrectionMaxDistance;
	private static long loopNanos;
	private static long loopMaxNanos;
	private static long chunkDecodes;
	private static long chunkReadNanos;
	private static long chunkSkyNanos;
	private static long chunkOtherNanos;
	private static long entityTickSamples;
	private static long entityTickNanos;
	private static long entityTickMaxNanos;
	private static long entityTickCount;
	private static long entityTickPlayers;
	private static long entityTickDisplays;
	private static final int ENTITY_TYPE_SLOTS = 32;
	private static final String[] entityTypeNames = new String[ENTITY_TYPE_SLOTS];
	private static final long[] entityTypeNanos = new long[ENTITY_TYPE_SLOTS];
	private static final long[] entityTypeMaxNanos = new long[ENTITY_TYPE_SLOTS];
	private static final long[] entityTypeSamples = new long[ENTITY_TYPE_SLOTS];
	private static long levelRenderSamples;
	private static long levelSubmitNanos;
	private static long levelPrepareNanos;
	private static long levelChunkPrepareNanos;
	private static long levelExecuteNanos;
	private static long levelCompileNanos;
	private static long levelUploadNanos;
	private static long levelOcclusionNanos;
	private static long levelVisibleSections;
	private static long levelRenderedEntities;
	private static long levelRenderedBlockEntities;
	private static long featurePrepareSamples;
	private static long featureSortNanos;
	private static long featureBeginNanos;
	private static long featureBuildNanos;
	private static long featureFinishNanos;
	private static long featureUploadNanos;
	private static long featureSubmits;
	private static long featureGroups;
	private static final int FEATURE_TYPE_SLOTS = 32;
	private static final String[] featureTypeNames = new String[FEATURE_TYPE_SLOTS];
	private static final long[] featureTypeNanos = new long[FEATURE_TYPE_SLOTS];
	private static final long[] featureTypeMaxNanos = new long[FEATURE_TYPE_SLOTS];
	private static final long[] featureTypeSamples = new long[FEATURE_TYPE_SLOTS];
	private static final long[] featureTypeSubmits = new long[FEATURE_TYPE_SLOTS];
	private static final long[] featureTypeGroups = new long[FEATURE_TYPE_SLOTS];
	private static long modelPrepareSamples;
	private static long modelSetupNanos;
	private static long modelRenderNanos;
	private static long guiPrepareSamples;
	private static long guiPicturesNanos;
	private static long guiItemsNanos;
	private static long guiTextNanos;
	private static long guiSortNanos;
	private static long guiMeshNanos;
	private static long guiTextRuns;
	private static long guiGlyphs;
	private static long guiDraws;
	private static long singleplayerInboundPumps;
	private static long singleplayerInboundFrames;
	private static long singleplayerInboundBytes;
	private static long singleplayerInboundNanos;
	private static long singleplayerInboundMaxNanos;
	private static int singleplayerInboundQueueDepth;
	private static int singleplayerInboundQueueDepthMax;
	private static long singleplayerInboundOldestNanos;
	private static long singleplayerInboundOldestMaxNanos;
	private static final int PACKET_TYPE_SLOTS = 96;
	private static final String[] packetTypeNames = new String[PACKET_TYPE_SLOTS];
	private static final long[] packetTypeFrames = new long[PACKET_TYPE_SLOTS];
	private static final long[] packetTypeBytes = new long[PACKET_TYPE_SLOTS];
	public static final int ACTION_ATTACK = 0;
	public static final int ACTION_USE_BLOCK = 1;
	public static final int ACTION_CONTAINER_CLICK = 2;
	private static final int ACTION_SLOTS = 3;
	private static final String[] ACTION_NAMES = { "attack", "use_block", "container_click" };
	private static final long[] actionPendingNanos = new long[ACTION_SLOTS];
	private static final long[] actionSent = new long[ACTION_SLOTS];
	private static final long[] actionResponses = new long[ACTION_SLOTS];
	private static final long[] actionResponseNanos = new long[ACTION_SLOTS];
	private static final long[] actionResponseMaxNanos = new long[ACTION_SLOTS];
	private static int actionTraceUseSequence = -1;
	private static int actionTraceAttackEntity = -1;

	private EaglerClientPerf() {
	}

	public static void setEnabled(final boolean value) {
		boolean next = value && EaglerHosted.isActive();
		if (next != enabled) {
			// An observation from an earlier profiling session cannot be matched to
			// a response in this session (for example after changing worlds).
			for (int i = 0; i < ACTION_SLOTS; ++i) {
				actionPendingNanos[i] = 0L;
				actionSent[i] = actionResponses[i] = actionResponseNanos[i] = actionResponseMaxNanos[i] = 0L;
			}
			actionTraceUseSequence = -1;
			actionTraceAttackEntity = -1;
		}
		enabled = next;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void frame(final long extract, final long render, final long present, final long limiter,
			final long total) {
		if (!enabled) {
			return;
		}
		long now = System.nanoTime();
		if (windowStarted == 0L) {
			windowStarted = now;
		}
		frames++;
		extractNanos += extract;
		renderNanos += render;
		presentNanos += present;
		limiterNanos += limiter;
		frameNanos += total;
		frameMaxNanos = Math.max(frameMaxNanos, total);
		if (now - windowStarted >= WINDOW_NANOS) {
			long count = Math.max(1L, frames);
			long snapshotCount = Math.max(1L, snapshots);
			long inlineCount = Math.max(1L, inlineCompiles);
			LOGGER.info("[EagPerfClient] frames={} total avg/max={}/{}ms extract={}ms render={}ms present={}ms limiter={}ms | snapshots={} capture/encode={}/{}ms inline={} avg/max={}/{}ms",
					frames, millis(frameNanos / count), millis(frameMaxNanos), millis(extractNanos / count),
					millis(renderNanos / count), millis(presentNanos / count), millis(limiterNanos / count), snapshots,
					millis(snapshotCaptureNanos / snapshotCount), millis(snapshotEncodeNanos / snapshotCount), inlineCompiles,
					millis(inlineCompileNanos / inlineCount), millis(inlineCompileMaxNanos));
			if (clientWorkSamples != 0L) {
				LOGGER.info("[EagPerfClient] pre-frame samples={} packet avg/max={}/{}ms tasks={}ms ticks={}ms loop avg/max={}/{}ms",
						clientWorkSamples, millis(packetNanos / clientWorkSamples), millis(packetMaxNanos),
						millis(taskNanos / clientWorkSamples), millis(tickNanos / clientWorkSamples),
						millis(loopNanos / clientWorkSamples), millis(loopMaxNanos));
				LOGGER.info("[EagPerfClient] packet queue processed={} depth={}/{} oldest={}/{}ms",
						packetsProcessed, packetQueueDepth, packetQueueDepthMax,
						millis(packetQueueOldestNanos), millis(packetQueueOldestMaxNanos));
			}
			long actualClientTicks = tickPhaseSamples[TICK_TOTAL];
			if (actualClientTicks != 0L) {
				for (int i = 0; i < TICK_PHASE_NAMES.length; ++i) {
					LOGGER.info("[EagPerfClient] client tick phase={} ticks={} calls={} perTick avg/maxCall={}/{}ms",
							TICK_PHASE_NAMES[i], actualClientTicks, tickPhaseSamples[i],
							millis(tickPhaseNanos[i] / actualClientTicks), millis(tickPhaseMaxNanos[i]));
				}
			}
			if (textureTickSamples != 0L) {
				// Texture animation runs once before the catch-up loop, not once per client tick.
				LOGGER.info("[EagPerfClient] texture animation calls={} avg/max={}/{}ms (outside client tick loop)",
						textureTickSamples, millis(textureTickNanos / textureTickSamples), millis(textureTickMaxNanos));
			}
			if (singleplayerInboundPumps != 0L) {
				LOGGER.info("[EagPerfClient] singleplayer inbound pumps={} frames={} bytes={} decode avg/max={}/{}ms queue={}/{} oldest={}/{}ms",
						singleplayerInboundPumps, singleplayerInboundFrames, singleplayerInboundBytes,
						millis(singleplayerInboundNanos / singleplayerInboundPumps),
						millis(singleplayerInboundMaxNanos), singleplayerInboundQueueDepth,
						singleplayerInboundQueueDepthMax, millis(singleplayerInboundOldestNanos),
						millis(singleplayerInboundOldestMaxNanos));
			}
			for (int i = 0; i < PACKET_TYPE_SLOTS; ++i) {
				if (packetTypeFrames[i] != 0L) {
					LOGGER.info("[EagPerfClient] inbound packet type={} frames={} bytes={}",
							packetTypeNames[i], packetTypeFrames[i], packetTypeBytes[i]);
				}
			}
			for (int i = 0; i < ACTION_SLOTS; ++i) {
				if (actionSent[i] != 0L || actionResponses[i] != 0L || actionPendingNanos[i] != 0L) {
					long responses = Math.max(1L, actionResponses[i]);
					LOGGER.info("[EagPerfClient] action={} sent={} responses={} latency avg/max={}/{}ms pending={} pendingAge={}ms",
							ACTION_NAMES[i], actionSent[i], actionResponses[i],
							millis(actionResponseNanos[i] / responses), millis(actionResponseMaxNanos[i]),
							actionPendingNanos[i] != 0L,
							millis(actionPendingNanos[i] == 0L ? 0L : Math.max(0L, now - actionPendingNanos[i])));
				}
			}
			if (chunkDecodes != 0L) {
				LOGGER.info("[EagPerfClient] chunk decode count={} sectionRead={}ms skySources={}ms other={}ms",
						chunkDecodes, millis(chunkReadNanos / chunkDecodes), millis(chunkSkyNanos / chunkDecodes),
						millis(chunkOtherNanos / chunkDecodes));
			}
			if (particleTickSamples != 0L) {
				LOGGER.info("[EagPerfClient] particle ticks samples={} avg/max={}/{}ms live avg/max={}/{}",
						particleTickSamples, millis(particleTickNanos / particleTickSamples),
						millis(particleTickMaxNanos), particleLive / particleTickSamples, particleLiveMax);
			}
			if (earlyParticlePacketsAdmitted != 0L || earlyParticlePacketsDropped != 0L) {
				LOGGER.info("[EagPerfClient] early particle packets admitted={}/{}B shed={}/{}B",
						earlyParticlePacketsAdmitted, earlyParticleBytesAdmitted,
						earlyParticlePacketsDropped, earlyParticleBytesDropped);
			}
			if (playerInputSamples != 0L || playerPositionCorrections != 0L) {
				LOGGER.info("[EagPerfClient] player movement samples={} inputActive={} jump={} shift={} cameraBlocked={} localMoved={} sent={} corrections={} avg/max={}/{} blocks",
						playerInputSamples, playerInputActive, playerInputJump, playerInputShift,
						playerInputCameraBlocked, playerLocalMoveSamples, playerMovePackets,
						playerPositionCorrections,
						decimal(playerPositionCorrections == 0L ? 0.0 : playerPositionCorrectionDistance / playerPositionCorrections),
						decimal(playerPositionCorrectionMaxDistance));
			}
			if (entityTickSamples != 0L) {
				LOGGER.info("[EagPerfClient] entity ticks samples={} avg/max={}ms/{}ms entities={} players={} displays={}",
						entityTickSamples, millis(entityTickNanos / entityTickSamples), millis(entityTickMaxNanos),
						entityTickCount / entityTickSamples, entityTickPlayers / entityTickSamples,
						entityTickDisplays / entityTickSamples);
				for (int i = 0; i < ENTITY_TYPE_SLOTS; ++i) {
					long samples = entityTypeSamples[i];
					if (samples != 0L) {
						LOGGER.info("[EagPerfClient] entity type={} ticks={} avg/max={}/{}ms",
								entityTypeNames[i], samples, millis(entityTypeNanos[i] / samples),
								millis(entityTypeMaxNanos[i]));
					}
				}
			}
			if (levelRenderSamples != 0L) {
				LOGGER.info("[EagPerfClient] level render samples={} submit={}ms prepare={}ms chunks={}ms execute={}ms compile={}ms upload={}ms occlusion={}ms | visible={} entities={} blockEntities={}",
						levelRenderSamples, millis(levelSubmitNanos / levelRenderSamples),
						millis(levelPrepareNanos / levelRenderSamples), millis(levelChunkPrepareNanos / levelRenderSamples),
						millis(levelExecuteNanos / levelRenderSamples), millis(levelCompileNanos / levelRenderSamples),
						millis(levelUploadNanos / levelRenderSamples), millis(levelOcclusionNanos / levelRenderSamples),
						levelVisibleSections / levelRenderSamples, levelRenderedEntities / levelRenderSamples,
						levelRenderedBlockEntities / levelRenderSamples);
			}
			if (featurePrepareSamples != 0L) {
				LOGGER.info("[EagPerfClient] feature prepare samples={} sort={}ms begin={}ms build={}ms finish={}ms upload={}ms | submits={} groups={}",
						featurePrepareSamples, millis(featureSortNanos / featurePrepareSamples),
						millis(featureBeginNanos / featurePrepareSamples), millis(featureBuildNanos / featurePrepareSamples),
						millis(featureFinishNanos / featurePrepareSamples), millis(featureUploadNanos / featurePrepareSamples),
						featureSubmits / featurePrepareSamples, featureGroups / featurePrepareSamples);
				for (int i = 0; i < FEATURE_TYPE_SLOTS; ++i) {
					long samples = featureTypeSamples[i];
					if (samples != 0L) {
						LOGGER.info("[EagPerfClient] feature type={} samples={} avg/max={}/{}ms submits={} groups={}",
								featureTypeNames[i], samples, millis(featureTypeNanos[i] / samples),
								millis(featureTypeMaxNanos[i]), featureTypeSubmits[i] / samples,
								featureTypeGroups[i] / samples);
					}
				}
			}
			if (modelPrepareSamples != 0L) {
				LOGGER.info("[EagPerfClient] model prepare samples={} setupAnim={}ms renderToBuffer={}ms",
						modelPrepareSamples, millis(modelSetupNanos / modelPrepareSamples),
						millis(modelRenderNanos / modelPrepareSamples));
			}
			if (guiPrepareSamples != 0L) {
				LOGGER.info("[EagPerfClient] gui prepare samples={} pictures={}ms items={}ms text={}ms sort={}ms mesh={}ms | textRuns={} glyphs={} draws={}",
						guiPrepareSamples, millis(guiPicturesNanos / guiPrepareSamples),
						millis(guiItemsNanos / guiPrepareSamples), millis(guiTextNanos / guiPrepareSamples),
						millis(guiSortNanos / guiPrepareSamples), millis(guiMeshNanos / guiPrepareSamples),
						guiTextRuns / guiPrepareSamples, guiGlyphs / guiPrepareSamples, guiDraws / guiPrepareSamples);
			}
			windowStarted = now;
			frames = extractNanos = renderNanos = presentNanos = limiterNanos = frameNanos = frameMaxNanos = 0L;
			snapshots = snapshotCaptureNanos = snapshotEncodeNanos = 0L;
			inlineCompiles = inlineCompileNanos = inlineCompileMaxNanos = 0L;
			clientWorkSamples = packetNanos = packetMaxNanos = taskNanos = tickNanos = loopNanos = loopMaxNanos = 0L;
			for (int i = 0; i < TICK_PHASE_NAMES.length; ++i) {
				tickPhaseSamples[i] = tickPhaseNanos[i] = tickPhaseMaxNanos[i] = 0L;
			}
			textureTickSamples = textureTickNanos = textureTickMaxNanos = 0L;
			packetsProcessed = 0L;
			packetQueueDepthMax = packetQueueDepth;
			packetQueueOldestNanos = packetQueueOldestMaxNanos = 0L;
			chunkDecodes = chunkReadNanos = chunkSkyNanos = chunkOtherNanos = 0L;
			particleTickSamples = particleTickNanos = particleTickMaxNanos = 0L;
			particleLive = particleLiveMax = 0L;
			earlyParticlePacketsAdmitted = earlyParticlePacketsDropped = 0L;
			earlyParticleBytesAdmitted = earlyParticleBytesDropped = 0L;
			playerInputSamples = playerInputActive = playerInputJump = playerInputShift = 0L;
			playerInputCameraBlocked = playerLocalMoveSamples = playerMovePackets = 0L;
			playerPositionCorrections = 0L;
			playerPositionCorrectionDistance = playerPositionCorrectionMaxDistance = 0.0;
			entityTickSamples = entityTickNanos = entityTickMaxNanos = entityTickCount = 0L;
			entityTickPlayers = entityTickDisplays = 0L;
			for (int i = 0; i < ENTITY_TYPE_SLOTS; ++i) {
				entityTypeNanos[i] = entityTypeMaxNanos[i] = entityTypeSamples[i] = 0L;
			}
			levelRenderSamples = levelSubmitNanos = levelPrepareNanos = levelChunkPrepareNanos = 0L;
			levelExecuteNanos = levelCompileNanos = levelUploadNanos = levelOcclusionNanos = 0L;
			levelVisibleSections = levelRenderedEntities = levelRenderedBlockEntities = 0L;
			featurePrepareSamples = featureSortNanos = featureBeginNanos = featureBuildNanos = 0L;
			featureFinishNanos = featureUploadNanos = featureSubmits = featureGroups = 0L;
			for (int i = 0; i < FEATURE_TYPE_SLOTS; ++i) {
				featureTypeNanos[i] = featureTypeMaxNanos[i] = featureTypeSamples[i] = 0L;
				featureTypeSubmits[i] = featureTypeGroups[i] = 0L;
			}
			modelPrepareSamples = modelSetupNanos = modelRenderNanos = 0L;
			guiPrepareSamples = guiPicturesNanos = guiItemsNanos = guiTextNanos = guiSortNanos = 0L;
			guiMeshNanos = guiTextRuns = guiGlyphs = guiDraws = 0L;
			singleplayerInboundPumps = singleplayerInboundFrames = singleplayerInboundBytes = 0L;
			singleplayerInboundNanos = singleplayerInboundMaxNanos = 0L;
			singleplayerInboundQueueDepthMax = singleplayerInboundQueueDepth;
			singleplayerInboundOldestNanos = singleplayerInboundOldestMaxNanos = 0L;
			for (int i = 0; i < PACKET_TYPE_SLOTS; ++i) {
				packetTypeFrames[i] = packetTypeBytes[i] = 0L;
			}
			for (int i = 0; i < ACTION_SLOTS; ++i) {
				actionSent[i] = actionResponses[i] = actionResponseNanos[i] = actionResponseMaxNanos[i] = 0L;
			}
		}
	}

	public static void earlyParticlePacket(final boolean admitted, final int bytes) {
		if (!enabled) {
			return;
		}
		if (admitted) {
			earlyParticlePacketsAdmitted++;
			earlyParticleBytesAdmitted += Math.max(0, bytes);
		} else {
			earlyParticlePacketsDropped++;
			earlyParticleBytesDropped += Math.max(0, bytes);
		}
	}

	public static void playerMovement(final boolean inputActive, final boolean jump, final boolean shift,
			final boolean cameraBlocked, final boolean locallyMoved, final boolean sentMovePacket) {
		if (!enabled) {
			return;
		}
		playerInputSamples++;
		playerInputActive += inputActive ? 1L : 0L;
		playerInputJump += jump ? 1L : 0L;
		playerInputShift += shift ? 1L : 0L;
		playerInputCameraBlocked += cameraBlocked ? 1L : 0L;
		playerLocalMoveSamples += locallyMoved ? 1L : 0L;
		playerMovePackets += sentMovePacket ? 1L : 0L;
	}

	public static void playerPositionCorrection(final double distance) {
		if (!enabled) {
			return;
		}
		double safeDistance = Math.max(0.0, distance);
		playerPositionCorrections++;
		playerPositionCorrectionDistance += safeDistance;
		playerPositionCorrectionMaxDistance = Math.max(playerPositionCorrectionMaxDistance, safeDistance);
	}

	public static void clientWork(final long packet, final long tasks, final long ticks, final long loop) {
		if (enabled) {
			clientWorkSamples++;
			packetNanos += packet;
			packetMaxNanos = Math.max(packetMaxNanos, packet);
			taskNanos += tasks;
			tickNanos += ticks;
			loopNanos += loop;
			loopMaxNanos = Math.max(loopMaxNanos, loop);
		}
	}

	/** Fixed numeric phases: no maps, strings, or per-tick sample objects. */
	public static void clientTickPhase(final int phase, final long duration) {
		if (enabled && phase >= 0 && phase < TICK_PHASE_NAMES.length) {
			tickPhaseSamples[phase]++;
			tickPhaseNanos[phase] += duration;
			tickPhaseMaxNanos[phase] = Math.max(tickPhaseMaxNanos[phase], duration);
		}
	}

	public static void textureTick(final long duration) {
		if (enabled) {
			textureTickSamples++;
			textureTickNanos += duration;
			textureTickMaxNanos = Math.max(textureTickMaxNanos, duration);
		}
	}

	public static void packetQueue(final int processed, final int depth, final long oldestNanos) {
		if (enabled) {
			packetsProcessed += processed;
			packetQueueDepth = depth;
			packetQueueDepthMax = Math.max(packetQueueDepthMax, depth);
			packetQueueOldestNanos = oldestNanos;
			packetQueueOldestMaxNanos = Math.max(packetQueueOldestMaxNanos, oldestNanos);
		}
	}

	public static void chunkDecode(final long sectionRead, final long skySources, final long other) {
		if (enabled) {
			chunkDecodes++;
			chunkReadNanos += sectionRead;
			chunkSkyNanos += skySources;
			chunkOtherNanos += other;
		}
	}

	public static void particleTick(final long duration, final int live) {
		if (enabled) {
			particleTickSamples++;
			particleTickNanos += duration;
			particleTickMaxNanos = Math.max(particleTickMaxNanos, duration);
			particleLive += live;
			particleLiveMax = Math.max(particleLiveMax, live);
		}
	}

	public static void meshSnapshot(final long capture, final long encode) {
		if (enabled) {
			snapshots++;
			snapshotCaptureNanos += capture;
			snapshotEncodeNanos += encode;
		}
	}

	public static void meshInlineCompile(final long duration) {
		if (enabled) {
			inlineCompiles++;
			inlineCompileNanos += duration;
			inlineCompileMaxNanos = Math.max(inlineCompileMaxNanos, duration);
		}
	}

	public static void entityTicks(final long duration, final int count, final int players, final int displays) {
		if (enabled) {
			entityTickSamples++;
			entityTickNanos += duration;
			entityTickMaxNanos = Math.max(entityTickMaxNanos, duration);
			entityTickCount += count;
			entityTickPlayers += players;
			entityTickDisplays += displays;
		}
	}

	public static void entityType(final String name, final long duration) {
		if (!enabled) {
			return;
		}
		int empty = -1;
		for (int i = 0; i < ENTITY_TYPE_SLOTS; ++i) {
			String slotName = entityTypeNames[i];
			if (name.equals(slotName)) {
				entityTypeSamples[i]++;
				entityTypeNanos[i] += duration;
				entityTypeMaxNanos[i] = Math.max(entityTypeMaxNanos[i], duration);
				return;
			}
			if (empty < 0 && slotName == null) {
				empty = i;
			}
		}
		if (empty >= 0) {
			entityTypeNames[empty] = name;
			entityTypeSamples[empty] = 1L;
			entityTypeNanos[empty] = duration;
			entityTypeMaxNanos[empty] = duration;
		}
	}

	public static void levelRender(final long submit, final long prepare, final long chunkPrepare,
			final long execute, final long compile, final long upload, final long occlusion,
			final int visibleSections, final int entities, final int blockEntities) {
		if (enabled) {
			levelRenderSamples++;
			levelSubmitNanos += submit;
			levelPrepareNanos += prepare;
			levelChunkPrepareNanos += chunkPrepare;
			levelExecuteNanos += execute;
			levelCompileNanos += compile;
			levelUploadNanos += upload;
			levelOcclusionNanos += occlusion;
			levelVisibleSections += visibleSections;
			levelRenderedEntities += entities;
			levelRenderedBlockEntities += blockEntities;
		}
	}

	public static void featurePrepare(final long sort, final long begin, final long build, final long finish,
			final long upload, final int submits, final int groups) {
		if (enabled) {
			featurePrepareSamples++;
			featureSortNanos += sort;
			featureBeginNanos += begin;
			featureBuildNanos += build;
			featureFinishNanos += finish;
			featureUploadNanos += upload;
			featureSubmits += submits;
			featureGroups += groups;
		}
	}

	public static void featureType(final int id, final String name, final long duration, final int submits,
			final int groups) {
		if (enabled && id >= 0 && id < FEATURE_TYPE_SLOTS) {
			featureTypeNames[id] = name;
			featureTypeNanos[id] += duration;
			featureTypeMaxNanos[id] = Math.max(featureTypeMaxNanos[id], duration);
			featureTypeSamples[id]++;
			featureTypeSubmits[id] += submits;
			featureTypeGroups[id] += groups;
		}
	}

	public static void modelPrepare(final long setupAnim, final long renderToBuffer) {
		if (enabled) {
			modelPrepareSamples++;
			modelSetupNanos += setupAnim;
			modelRenderNanos += renderToBuffer;
		}
	}

	public static void guiPrepare(final long pictures, final long items, final long text, final long sort,
			final long mesh, final int textRuns, final int glyphs, final int draws) {
		if (enabled) {
			guiPrepareSamples++;
			guiPicturesNanos += pictures;
			guiItemsNanos += items;
			guiTextNanos += text;
			guiSortNanos += sort;
			guiMeshNanos += mesh;
			guiTextRuns += textRuns;
			guiGlyphs += glyphs;
			guiDraws += draws;
		}
	}

	public static void singleplayerInbound(final int frames, final int queueDepth, final int bytes,
			final long duration, final long oldestNanos) {
		if (enabled) {
			singleplayerInboundPumps++;
			singleplayerInboundFrames += frames;
			singleplayerInboundBytes += bytes;
			singleplayerInboundNanos += duration;
			singleplayerInboundMaxNanos = Math.max(singleplayerInboundMaxNanos, duration);
			singleplayerInboundQueueDepth = queueDepth;
			singleplayerInboundQueueDepthMax = Math.max(singleplayerInboundQueueDepthMax, queueDepth);
			singleplayerInboundOldestNanos = oldestNanos;
			singleplayerInboundOldestMaxNanos = Math.max(singleplayerInboundOldestMaxNanos, oldestNanos);
		}
	}

	/** Records decoded clientbound packet volume without allocating maps in the hot path. */
	public static void decodedPacket(final String name, final int bytes) {
		if (!enabled) {
			return;
		}
		int empty = -1;
		for (int i = 0; i < PACKET_TYPE_SLOTS; ++i) {
			String existing = packetTypeNames[i];
			if (existing == null) {
				empty = i;
				break;
			}
			if (existing.equals(name)) {
				packetTypeFrames[i]++;
				packetTypeBytes[i] += bytes;
				return;
			}
		}
		if (empty >= 0) {
			packetTypeNames[empty] = name;
			packetTypeFrames[empty] = 1L;
			packetTypeBytes[empty] = bytes;
		}
	}

	public static void actionSent(final int action) {
		if (enabled && action >= 0 && action < ACTION_SLOTS) {
			// These response classes carry no per-action correlation ID. Measure a
			// single observation window from its FIRST input until a matching response;
			// repeated clicks must not erase a minutes-old outstanding observation.
			// This is not a per-packet RTT when more than one action is outstanding.
			if (actionPendingNanos[action] == 0L) {
				actionPendingNanos[action] = System.nanoTime();
			}
			actionSent[action]++;
		}
	}

	public static void actionResponse(final int action) {
		actionResponse(action, null);
	}

	public static void actionResponse(final int action, final String source) {
		if (!enabled || action < 0 || action >= ACTION_SLOTS) {
			return;
		}
		long started = actionPendingNanos[action];
		if (started != 0L) {
			long elapsed = System.nanoTime() - started;
			actionPendingNanos[action] = 0L;
			actionResponses[action]++;
			actionResponseNanos[action] += elapsed;
			actionResponseMaxNanos[action] = Math.max(actionResponseMaxNanos[action], elapsed);
			if (source == null) {
				LOGGER.info("[EagPerfClient] action response type={} latency={}ms",
						ACTION_NAMES[action], millis(elapsed));
			} else {
				LOGGER.info("[EagPerfClient] action response type={} source={} latency={}ms",
						ACTION_NAMES[action], source, millis(elapsed));
			}
		}
	}

	public static void actionInput(final int action, final int key, final String detail) {
		traceAction(action, key, "input", detail);
	}

	public static void useBlockSent(final int sequence, final int x, final int y, final int z) {
		if (enabled) {
			actionTraceUseSequence = sequence;
			traceAction(ACTION_USE_BLOCK, sequence, "client_send", "pos=" + x + "," + y + "," + z);
		}
	}

	public static void attackSent(final int entityId) {
		if (enabled) {
			actionTraceAttackEntity = entityId;
			traceAction(ACTION_ATTACK, entityId, "client_send", "target=" + entityId);
		}
	}

	public static void openScreenPacketStage(final String stage, final int containerId, final long queueAgeNanos) {
		if (enabled && actionTraceUseSequence >= 0) {
			traceAction(ACTION_USE_BLOCK, actionTraceUseSequence, stage,
					"container=" + containerId + " queueAgeMs=" + millis(queueAgeNanos));
		}
	}

	public static void openScreenResponse(final int containerId) {
		if (enabled && actionTraceUseSequence >= 0) {
			traceAction(ACTION_USE_BLOCK, actionTraceUseSequence, "client_response", "container=" + containerId);
			actionTraceUseSequence = -1;
		}
	}

	public static void attackPacketStage(final String stage, final int entityId, final long queueAgeNanos,
			final String source) {
		if (enabled && actionTraceAttackEntity == entityId) {
			traceAction(ACTION_ATTACK, entityId, stage,
					"source=" + source + " queueAgeMs=" + millis(queueAgeNanos));
		}
	}

	public static void attackResponse(final int entityId, final String source) {
		if (enabled && actionTraceAttackEntity == entityId) {
			traceAction(ACTION_ATTACK, entityId, "client_response", "source=" + source);
			actionTraceAttackEntity = -1;
		}
	}

	private static void traceAction(final int action, final int key, final String stage, final String detail) {
		if (enabled && action >= 0 && action < ACTION_SLOTS) {
			LOGGER.info("[EagActionTrace] realm=client action={} key={} stage={} epochMs={} steadyNs={} tick=-1 {}",
					ACTION_NAMES[action], key, stage, System.currentTimeMillis(), System.nanoTime(), detail);
		}
	}

	private static String millis(final long nanos) {
		return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0);
	}

	private static String decimal(final double value) {
		return String.format(java.util.Locale.ROOT, "%.3f", value);
	}
}
