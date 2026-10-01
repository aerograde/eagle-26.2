package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.util.List;
import java.util.function.Consumer;

/**
 * Server-worker boundary protocol (eag26 teardown §2.3, online-perf-research §finding 1).
 *
 * <p>The integrated-server Web Worker and the client page exchange data over TWO
 * logically distinct channels, both carried on the existing {@code {ch:string, dat:ArrayBuffer}}
 * postMessage envelope so we reuse the whole {@code ServerPlatformSingleplayer} /
 * {@code ClientPlatformSingleplayer} router unchanged (mesh-worker-design H7 avoided):
 *
 * <ul>
 * <li><b>Control plane</b> — channel {@link SingleplayerServerController26#IPC_CHANNEL}
 *     ({@code "~!IPC"}). Carries the existing {@code IPCPacket*} control bytes:
 *     launch/stop (0x01), autosave (0x19), options snapshot (0x20), progress (0x0D),
 *     crash (0x15), keep-alive/ACK (0xFF), world init (0x00/0x02). Unchanged from
 *     single-thread mode; posted as a structured-clone (small, rare).</li>
 * <li><b>Data plane</b> — channel {@link #DATA_CHANNEL} ({@code "~!SVDATA"}). Carries
 *     REAL vanilla protocol bytes (post-{@code PacketEncoder}, pre-network), the same
 *     bytes the netty {@code LocalChannel} pipeline would have carried in-process. Each
 *     message is a <b>batch</b> of length-prefixed packet frames and is posted <b>with
 *     transfer</b> (zero-copy {@code postMessage(buf,[buf])}); H2 hazard: the sender's
 *     backing buffer is neutered after the post, so retained bytes must be cloned.</li>
 * </ul>
 *
 * <p><b>Batch framing (verbatim eag26 §2.3):</b> {@code [u32 LE len][len bytes] × K}
 * concatenated into one buffer. Batching amortizes the fixed per-{@code postMessage}
 * cost, which dominates worker IPC. There is NO per-packet JSON/envelope — only the
 * 4-byte little-endian length prefix framing each packet inside the batch.
 *
 * <p>Compression/encryption stages are <b>bypassed</b> on this boundary (the transport
 * is in-process, same-origin, transferable memory — a trusted integrated/LAN server),
 * exactly as eag26 §2.4 and the vanilla integrated-server path do.
 */
public class ServerWorkerProtocol {

	/** Vanilla-packet data-plane channel (transferable batches). */
	public static final String DATA_CHANNEL = "~!SVDATA";
	/** Raw TCP byte chunks for a relay-backed LAN peer; the peer id follows this prefix. */
	public static final String LAN_DATA_PREFIX = "~!LAN:";
	/** UTF-8 lifecycle commands for relay peers ({@code close:<id>} or {@code close-all}). */
	public static final String LAN_CONTROL_CHANNEL = "~!LANCTRL";

	private ServerWorkerProtocol() {
	}

	/**
	 * Encode a batch of packet payloads into one buffer: {@code [u32 LE len][bytes] × K}.
	 * The returned array is a fresh allocation (safe to wrap+transfer; nothing else
	 * retains it).
	 */
	public static byte[] encodeBatch(List<byte[]> packets) {
		int total = 0;
		for (int i = 0, l = packets.size(); i < l; ++i) {
			total += 4 + packets.get(i).length;
		}
		byte[] out = new byte[total];
		int f = 0;
		for (int i = 0, l = packets.size(); i < l; ++i) {
			byte[] p = packets.get(i);
			int j = p.length;
			out[f] = (byte) j;
			out[f + 1] = (byte) (j >> 8);
			out[f + 2] = (byte) (j >> 16);
			out[f + 3] = (byte) (j >>> 24);
			System.arraycopy(p, 0, out, f + 4, j);
			f += 4 + j;
		}
		return out;
	}

	/**
	 * Decode a batch buffer, handing each framed packet's bytes to {@code sink}. Mirrors
	 * eag26's {@code A.Qj} decode loop (§2.3). Each delivered {@code byte[]} is a fresh
	 * copy (not a view into {@code batch}) so the consumer may retain it (H8/H2 safe).
	 * A malformed trailing frame (length runs past the buffer end) is ignored, matching
	 * the eag26 loop's early return.
	 *
	 * @return the number of complete frames delivered
	 */
	public static int decodeBatch(byte[] batch, Consumer<byte[]> sink) {
		int e = batch.length;
		int f = 0;
		int count = 0;
		while (true) {
			int g = f + 4;
			if (g > e) {
				return count;
			}
			int len = (batch[f] & 0xFF) | ((batch[f + 1] & 0xFF) << 8) | ((batch[f + 2] & 0xFF) << 16)
					| ((batch[f + 3] & 0xFF) << 24);
			f = g + len;
			if (len < 0 || f > e) {
				// malformed / truncated final frame — stop (eag26 early return)
				return count;
			}
			byte[] frame = new byte[len];
			System.arraycopy(batch, g, frame, 0, len);
			sink.accept(frame);
			++count;
		}
	}

}
