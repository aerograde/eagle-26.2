/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package org.lwjgl.openal;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * WebAudio-backed OpenAL engine for the 26.2 web build (TODO 3.5 done): the audio
 * deviation, mirroring how the graphics deviation implements blaze3d's GpuDevice on
 * WebGL2. Minecraft's com.mojang.blaze3d.audio.{Library,Channel,Listener,SoundBuffer}
 * speak the ~30-call LWJGL OpenAL surface; the org.lwjgl.openal.* classes in this
 * package delegate here, where:
 *
 *  - AL buffers   -> WebAudio {@code AudioBuffer}s (PCM8/16 mono/stereo, from the
 *                    pure-java JOrbis decode path — LE per org.lwjgl.BufferUtils);
 *  - AL sources   -> a per-source GainNode (AL_GAIN), optionally behind a PannerNode
 *                    (linear distance model = AL_EXT_LINEAR_DISTANCE, the only model
 *                    MC uses; AL_NONE / AL_SOURCE_RELATIVE bypass the panner);
 *  - static play  -> one AudioBufferSourceNode (AL_LOOPING -> loop);
 *  - streaming    -> Channel's queue/unqueue protocol: queued buffers are scheduled
 *                    back-to-back on the context clock; AL_BUFFERS_PROCESSED counts
 *                    nodes whose onended fired, exactly what updateStream() expects;
 *  - pause/unpause-> playbackRate 0 <-> pitch (WebAudio nodes cannot pause);
 *  - listener     -> AudioContext.listener position/orientation.
 *
 * Runs on the render green thread (single JS thread), so no synchronization.
 */
public final class WebAudioAL {

	private WebAudioAL() {
	}

	// AL constants used by MC (see Channel/Library/Listener/OpenAlUtil)
	private static final int AL_SOURCE_RELATIVE = 514;
	private static final int AL_PITCH = 4099;
	private static final int AL_POSITION = 4100;
	private static final int AL_LOOPING = 4103;
	private static final int AL_BUFFER = 4105;
	private static final int AL_GAIN = 4106;
	private static final int AL_ORIENTATION = 4111;
	private static final int AL_SOURCE_STATE = 4112;
	private static final int AL_INITIAL = 4113;
	private static final int AL_PLAYING = 4114;
	private static final int AL_PAUSED = 4115;
	private static final int AL_STOPPED = 4116;
	private static final int AL_BUFFERS_PROCESSED = 4118;
	private static final int AL_REFERENCE_DISTANCE = 4128;
	private static final int AL_ROLLOFF_FACTOR = 4129;
	private static final int AL_MAX_DISTANCE = 4131;
	private static final int AL_SOURCE_DISTANCE_MODEL = 53248;
	private static final int AL_LINEAR_DISTANCE_CLAMPED = 53251;

	private static final int FORMAT_MONO8 = 4352;
	private static final int FORMAT_MONO16 = 4353;
	private static final int FORMAT_STEREO8 = 4354;
	private static final int FORMAT_STEREO16 = 4355;

	private static JSObject ctx;
	private static JSObject masterGain;
	private static boolean contextCreated;

	private static int nextBufferId = 1;
	private static int nextSourceId = 1;
	private static final Map<Integer, JSObject> buffers = new HashMap<>();
	private static final Map<Integer, Source> sources = new HashMap<>();

	private static final class Queued {
		final int bufferId;
		JSObject node;      // scheduled AudioBufferSourceNode (null until started)
		boolean ended;

		Queued(final int bufferId) {
			this.bufferId = bufferId;
		}
	}

	private static final class Source {
		JSObject gain;             // per-source gain, connected to masterGain
		JSObject panner;           // present only while positional attenuation is on
		JSObject staticNode;       // active static AudioBufferSourceNode
		final List<Queued> queue = new ArrayList<>();
		int state = AL_INITIAL;
		int staticBuffer;
		boolean streaming;
		boolean looping;
		boolean relative;
		boolean attenuate;         // linear distance model enabled
		float pitch = 1.0f;
		float x, y, z;
		float maxDistance = 100.0f, rolloff = 1.0f, refDistance;
		double nextStartTime;      // context-clock tail of the scheduled stream
		int playToken;             // invalidates stale onended callbacks after stop
	}

	// ==== context ====

	static boolean ensureContext() {
		if (!contextCreated) {
			ctx = jsCreateContext();
			if (ctx == null) {
				return false;
			}
			masterGain = jsCreateGain(ctx);
			jsConnectToDestination(ctx, masterGain);
			contextCreated = true;
		}
		return true;
	}

	static boolean hasContext() {
		return contextCreated;
	}

	static void destroyContext() {
		// keep the AudioContext (browsers limit how many may ever be created);
		// sources/buffers are already cleaned up by Library.cleanup()
	}

	// ==== buffers ====

	static void genBuffers(final int[] out) {
		for (int i = 0; i < out.length; ++i) {
			out[i] = nextBufferId++;
		}
	}

	static void bufferData(final int buffer, final int format, final ByteBuffer data, final int freq) {
		if (!ensureContext()) {
			return;
		}
		final int channels = (format == FORMAT_STEREO8 || format == FORMAT_STEREO16) ? 2 : 1;
		final boolean bits16 = format == FORMAT_MONO16 || format == FORMAT_STEREO16;
		final int bytesPerSample = bits16 ? 2 : 1;
		final int totalSamples = data.remaining() / bytesPerSample;
		final int frames = Math.max(1, totalSamples / channels);
		// convert interleaved PCM (s16le / u8) to per-channel float in Java, hand the
		// plain float[] to JS (TypedArray.set accepts array-likes)
		final JSObject audioBuffer = jsCreateBuffer(ctx, channels, frames, freq);
		final float[] chan = new float[frames];
		final int base = data.position();
		for (int c = 0; c < channels; ++c) {
			if (bits16) {
				for (int i = 0; i < frames; ++i) {
					final int off = base + ((i * channels + c) << 1);
					final short s = (short) ((data.get(off) & 0xFF) | (data.get(off + 1) << 8));
					chan[i] = s / 32768.0f;
				}
			} else {
				for (int i = 0; i < frames; ++i) {
					chan[i] = ((data.get(base + i * channels + c) & 0xFF) - 128) / 128.0f;
				}
			}
			jsFillChannel(audioBuffer, c, chan);
		}
		buffers.put(buffer, audioBuffer);
	}

	static void deleteBuffers(final int[] ids) {
		for (final int id : ids) {
			buffers.remove(id);
		}
	}

	// ==== browser-native decode (Opus music) ====
	// The Java JOrbis decoder is Vorbis-only; music ships as Opus for size. Hand the
	// encoded bytes to the browser's decodeAudioData (which handles Opus-in-Ogg), register
	// the resulting AudioBuffer as an AL buffer, and hand its id back. Async: onDone(id)
	// fires when decode resolves (single JS thread), onErr on failure.
	public static void decodeAndRegister(final byte[] encoded, final java.util.function.IntConsumer onDone, final Runnable onErr) {
		if (!ensureContext()) {
			onErr.run();
			return;
		}
		jsDecode(ctx, encoded, audioBuffer -> {
			final int id = nextBufferId++;
			buffers.put(id, audioBuffer);
			onDone.accept(id);
		}, onErr::run);
	}

	@FunctionalInterface
	@JSFunctor
	interface DecodeOk extends JSObject {
		void call(JSObject audioBuffer);
	}

	@JSBody(params = { "ctx", "bytes", "ok", "err" }, script = "try {"
			+ " var ab = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);"
			+ " var p = ctx.decodeAudioData(ab, function(b){ ok(b); }, function(e){ err(); });"
			+ " if(p && p.catch) { p.catch(function(e){}); }"
			+ " } catch(ex) { err(); }")
	private static native void jsDecode(JSObject ctx, byte[] bytes, DecodeOk ok, OnEnded err);

	// ==== sources ====

	static void genSources(final int[] out) {
		if (!ensureContext()) {
			// Library.init already succeeded if we got here; still guard
			for (int i = 0; i < out.length; ++i) {
				out[i] = 0;
			}
			return;
		}
		for (int i = 0; i < out.length; ++i) {
			final Source s = new Source();
			s.gain = jsCreateGain(ctx);
			jsConnect(s.gain, masterGain);
			final int id = nextSourceId++;
			sources.put(id, s);
			out[i] = id;
		}
	}

	static void deleteSources(final int[] ids) {
		for (final int id : ids) {
			final Source s = sources.remove(id);
			if (s != null) {
				stopAllNodes(s);
				jsDisconnect(s.gain);
				if (s.panner != null) {
					jsDisconnect(s.panner);
				}
			}
		}
	}

	private static JSObject inputNode(final Source s) {
		// where new AudioBufferSourceNodes connect: panner (positional) or gain
		if (s.attenuate && !s.relative) {
			if (s.panner == null) {
				s.panner = jsCreatePanner(ctx);
				jsConnect(s.panner, s.gain);
			}
			jsSetPanner(s.panner, s.x, s.y, s.z, s.refDistance, s.maxDistance, s.rolloff);
			return s.panner;
		}
		return s.gain;
	}

	private static void stopAllNodes(final Source s) {
		++s.playToken;
		if (s.staticNode != null) {
			jsStopNode(s.staticNode);
			s.staticNode = null;
		}
		for (final Queued q : s.queue) {
			if (q.node != null && !q.ended) {
				jsStopNode(q.node);
			}
			q.ended = true; // AL: stopping marks every queued buffer processed
		}
		s.nextStartTime = 0.0;
	}

	static void sourcePlay(final int source) {
		final Source s = sources.get(source);
		if (s == null || !ensureContext()) {
			return;
		}
		jsResume(ctx);
		if (s.state == AL_PAUSED) {
			// resume: restore playback rate on every live node
			if (s.staticNode != null) {
				jsSetPlaybackRate(s.staticNode, s.pitch);
			}
			for (final Queued q : s.queue) {
				if (q.node != null && !q.ended) {
					jsSetPlaybackRate(q.node, s.pitch);
				}
			}
			s.state = AL_PLAYING;
			return;
		}
		if (s.streaming || !s.queue.isEmpty()) {
			// streaming source: schedule everything not yet started
			s.streaming = true;
			s.state = AL_PLAYING;
			scheduleQueued(source, s);
			return;
		}
		// static source: restart from the attached buffer
		final JSObject buf = buffers.get(s.staticBuffer);
		if (buf == null) {
			s.state = AL_STOPPED;
			return;
		}
		if (s.staticNode != null) {
			jsStopNode(s.staticNode);
		}
		final int token = ++s.playToken;
		final JSObject node = jsCreateBufferSource(ctx, buf, s.looping, s.pitch);
		jsConnect(node, inputNode(s));
		jsOnEnded(node, () -> {
			if (s.playToken == token) {
				s.staticNode = null;
				s.state = AL_STOPPED;
			}
		});
		jsStartNode(node, 0.0);
		s.staticNode = node;
		s.state = AL_PLAYING;
	}

	private static void scheduleQueued(final int source, final Source s) {
		final double now = jsCurrentTime(ctx);
		if (s.nextStartTime < now) {
			s.nextStartTime = now;
		}
		for (final Queued q : s.queue) {
			if (q.node != null || q.ended) {
				continue;
			}
			final JSObject buf = buffers.get(q.bufferId);
			if (buf == null) {
				q.ended = true;
				continue;
			}
			final JSObject node = jsCreateBufferSource(ctx, buf, false, s.pitch);
			jsConnect(node, inputNode(s));
			final Queued qq = q;
			jsOnEnded(node, () -> qq.ended = true);
			jsStartNode(node, s.nextStartTime);
			s.nextStartTime += jsBufferDuration(buf) / Math.max(0.05, s.pitch);
			q.node = node;
		}
	}

	static void sourcePause(final int source) {
		final Source s = sources.get(source);
		if (s == null || s.state != AL_PLAYING) {
			return;
		}
		if (s.staticNode != null) {
			jsSetPlaybackRate(s.staticNode, 0.0f);
		}
		for (final Queued q : s.queue) {
			if (q.node != null && !q.ended) {
				jsSetPlaybackRate(q.node, 0.0f);
			}
		}
		s.state = AL_PAUSED;
	}

	static void sourceStop(final int source) {
		final Source s = sources.get(source);
		if (s == null) {
			return;
		}
		stopAllNodes(s);
		s.state = AL_STOPPED;
	}

	static int getSourcei(final int source, final int param) {
		final Source s = sources.get(source);
		if (s == null) {
			return 0;
		}
		switch (param) {
			case AL_SOURCE_STATE: {
				if (s.state == AL_PLAYING && s.streaming) {
					// underrun/EOF: every scheduled node finished -> STOPPED (real AL
					// does the same; SoundEngine uses it to end finite music streams)
					boolean anyLive = false;
					for (final Queued q : s.queue) {
						if (!q.ended) {
							anyLive = true;
							break;
						}
					}
					if (!anyLive && !s.queue.isEmpty()) {
						s.state = AL_STOPPED;
					}
				}
				return s.state;
			}
			case AL_BUFFERS_PROCESSED: {
				int n = 0;
				for (final Queued q : s.queue) {
					if (!q.ended) {
						break;
					}
					++n;
				}
				return n;
			}
			default:
				return 0;
		}
	}

	static void sourcei(final int source, final int param, final int value) {
		final Source s = sources.get(source);
		if (s == null) {
			return;
		}
		switch (param) {
			case AL_BUFFER:
				s.staticBuffer = value;
				break;
			case AL_LOOPING:
				s.looping = value != 0;
				if (s.staticNode != null) {
					jsSetLoop(s.staticNode, s.looping);
				}
				break;
			case AL_SOURCE_RELATIVE:
				s.relative = value != 0;
				break;
			case AL_SOURCE_DISTANCE_MODEL:
				s.attenuate = value == AL_LINEAR_DISTANCE_CLAMPED;
				break;
			default:
				break;
		}
	}

	static void sourcef(final int source, final int param, final float value) {
		final Source s = sources.get(source);
		if (s == null) {
			return;
		}
		switch (param) {
			case AL_GAIN:
				jsSetGain(s.gain, Math.max(0.0f, value));
				break;
			case AL_PITCH: {
				s.pitch = value;
				if (s.state == AL_PLAYING) {
					if (s.staticNode != null) {
						jsSetPlaybackRate(s.staticNode, value);
					}
					for (final Queued q : s.queue) {
						if (q.node != null && !q.ended) {
							jsSetPlaybackRate(q.node, value);
						}
					}
				}
				break;
			}
			case AL_MAX_DISTANCE:
				s.maxDistance = value;
				break;
			case AL_ROLLOFF_FACTOR:
				s.rolloff = value;
				break;
			case AL_REFERENCE_DISTANCE:
				s.refDistance = value;
				break;
			default:
				break;
		}
		if (s.panner != null) {
			jsSetPanner(s.panner, s.x, s.y, s.z, s.refDistance, s.maxDistance, s.rolloff);
		}
	}

	static void sourcefv(final int source, final int param, final float[] values) {
		if (param == AL_POSITION && values.length >= 3) {
			final Source s = sources.get(source);
			if (s == null) {
				return;
			}
			s.x = values[0];
			s.y = values[1];
			s.z = values[2];
			if (s.panner != null) {
				jsSetPannerPosition(s.panner, s.x, s.y, s.z);
			}
		}
	}

	static void source3f(final int source, final int param, final float v1, final float v2, final float v3) {
		if (param == AL_POSITION) {
			sourcefv(source, param, new float[]{v1, v2, v3});
		}
	}

	static void sourceQueueBuffers(final int source, final int[] bufferIds) {
		final Source s = sources.get(source);
		if (s == null) {
			return;
		}
		s.streaming = true;
		for (final int id : bufferIds) {
			s.queue.add(new Queued(id));
		}
		if (s.state == AL_PLAYING) {
			scheduleQueued(source, s);
		}
	}

	static void sourceUnqueueBuffers(final int source, final int[] out) {
		final Source s = sources.get(source);
		int n = 0;
		if (s != null) {
			while (n < out.length && !s.queue.isEmpty() && s.queue.get(0).ended) {
				out[n++] = s.queue.remove(0).bufferId;
			}
		}
		while (n < out.length) {
			out[n++] = 0;
		}
	}

	// ==== listener ====

	static void listener3f(final int param, final float x, final float y, final float z) {
		if (param == AL_POSITION && ensureContext()) {
			jsListenerPosition(ctx, x, y, z);
		}
	}

	static void listenerfv(final int param, final float[] values) {
		if (param == AL_ORIENTATION && values.length >= 6 && ensureContext()) {
			jsListenerOrientation(ctx, values[0], values[1], values[2], values[3], values[4], values[5]);
		}
	}

	// ==== JS interop ====

	@FunctionalInterface
	@JSFunctor
	interface OnEnded extends JSObject {
		void call();
	}

	@JSBody(params = {}, script = "try { var C = window.AudioContext || window.webkitAudioContext;"
			+ " return C ? new C() : null; } catch(e) { return null; }")
	private static native JSObject jsCreateContext();

	@JSBody(params = { "ctx" }, script = "return ctx.createGain();")
	private static native JSObject jsCreateGain(JSObject ctx);

	@JSBody(params = { "ctx", "node" }, script = "node.connect(ctx.destination);")
	private static native void jsConnectToDestination(JSObject ctx, JSObject node);

	@JSBody(params = { "from", "to" }, script = "from.connect(to);")
	private static native void jsConnect(JSObject from, JSObject to);

	@JSBody(params = { "node" }, script = "try { node.disconnect(); } catch(e) {}")
	private static native void jsDisconnect(JSObject node);

	@JSBody(params = { "ctx" }, script = "try { if(ctx.state === 'suspended' && ctx.resume) ctx.resume(); } catch(e) {}")
	private static native void jsResume(JSObject ctx);

	@JSBody(params = { "ctx" }, script = "return ctx.currentTime;")
	private static native double jsCurrentTime(JSObject ctx);

	@JSBody(params = { "ctx", "channels", "frames", "freq" }, script = "return ctx.createBuffer(channels, frames, freq);")
	private static native JSObject jsCreateBuffer(JSObject ctx, int channels, int frames, int freq);

	@JSBody(params = { "buf", "channel", "data" }, script = "buf.getChannelData(channel).set(data);")
	private static native void jsFillChannel(JSObject buf, int channel, float[] data);

	@JSBody(params = { "buf" }, script = "return buf.duration;")
	private static native double jsBufferDuration(JSObject buf);

	@JSBody(params = { "ctx", "buf", "loop", "rate" }, script = "var n = ctx.createBufferSource();"
			+ " n.buffer = buf; n.loop = !!loop; n.playbackRate.value = rate; return n;")
	private static native JSObject jsCreateBufferSource(JSObject ctx, JSObject buf, boolean loop, float rate);

	@JSBody(params = { "node", "cb" }, script = "node.onended = function() { cb(); };")
	private static native void jsOnEnded(JSObject node, OnEnded cb);

	@JSBody(params = { "node", "when" }, script = "try { node.start(when); } catch(e) {}")
	private static native void jsStartNode(JSObject node, double when);

	@JSBody(params = { "node" }, script = "try { node.onended = null; node.stop(); } catch(e) {}"
			+ " try { node.disconnect(); } catch(e2) {}")
	private static native void jsStopNode(JSObject node);

	@JSBody(params = { "node", "rate" }, script = "try { node.playbackRate.value = rate; } catch(e) {}")
	private static native void jsSetPlaybackRate(JSObject node, float rate);

	@JSBody(params = { "node", "loop" }, script = "node.loop = !!loop;")
	private static native void jsSetLoop(JSObject node, boolean loop);

	@JSBody(params = { "gain", "value" }, script = "gain.gain.value = value;")
	private static native void jsSetGain(JSObject gain, float value);

	@JSBody(params = { "ctx" }, script = "var p = ctx.createPanner(); p.panningModel = 'equalpower';"
			+ " p.distanceModel = 'linear'; return p;")
	private static native JSObject jsCreatePanner(JSObject ctx);

	@JSBody(params = { "p", "x", "y", "z", "ref", "max", "rolloff" }, script = "p.distanceModel = 'linear';"
			+ " p.refDistance = Math.max(0.01, ref); p.maxDistance = Math.max(0.02, max);"
			+ " p.rolloffFactor = rolloff;"
			+ " if(p.positionX) { p.positionX.value = x; p.positionY.value = y; p.positionZ.value = z; }"
			+ " else { p.setPosition(x, y, z); }")
	private static native void jsSetPanner(JSObject p, float x, float y, float z, float ref, float max, float rolloff);

	@JSBody(params = { "p", "x", "y", "z" }, script = "if(p.positionX) { p.positionX.value = x;"
			+ " p.positionY.value = y; p.positionZ.value = z; } else { p.setPosition(x, y, z); }")
	private static native void jsSetPannerPosition(JSObject p, float x, float y, float z);

	@JSBody(params = { "ctx", "x", "y", "z" }, script = "var l = ctx.listener;"
			+ " if(l.positionX) { l.positionX.value = x; l.positionY.value = y; l.positionZ.value = z; }"
			+ " else if(l.setPosition) { l.setPosition(x, y, z); }")
	private static native void jsListenerPosition(JSObject ctx, float x, float y, float z);

	@JSBody(params = { "ctx", "fx", "fy", "fz", "ux", "uy", "uz" }, script = "var l = ctx.listener;"
			+ " if(l.forwardX) { l.forwardX.value = fx; l.forwardY.value = fy; l.forwardZ.value = fz;"
			+ " l.upX.value = ux; l.upY.value = uy; l.upZ.value = uz; }"
			+ " else if(l.setOrientation) { l.setOrientation(fx, fy, fz, ux, uy, uz); }")
	private static native void jsListenerOrientation(JSObject ctx, float fx, float fy, float fz, float ux, float uy, float uz);
}
