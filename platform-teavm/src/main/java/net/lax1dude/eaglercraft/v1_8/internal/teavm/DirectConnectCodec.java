package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Connect-code codec for relay-free "Direct connect" worlds. Java port of the
 * browser-validated prototype codec (see workspace prototype/).
 *
 * <p>Wire format: {@code "EG"} + version char + payload, base64url without
 * padding. The decoder strips every whitespace character, so wrapped or
 * otherwise mangled pastes decode fine.</p>
 *
 * <ul>
 * <li>Version {@code 'C'} (compact struct) — the SDP session is reduced to the
 * identity-critical fields a peer needs to complete ICE + DTLS: ICE ufrag and
 * password, the 32-byte SHA-256 DTLS fingerprint, the SDP setup role, and the
 * gathered ICE candidates (IP/port/type, mDNS hostnames kept verbatim). The
 * receiving side rebuilds a valid data-channel SDP from the struct. Measured
 * sizes: ~99 bytes encoded (≈154 base64url chars with one mDNS candidate).
 * <li>Version {@code 'F'} (fallback) — minimal line-stripped SDP, deflate-raw
 * compressed. Used only when the SDP does not fit the compact struct.
 * </ul>
 */
public final class DirectConnectCodec {

	public static final String MAGIC = "EG";
	public static final char VERSION_COMPACT = 'C';
	public static final char VERSION_FALLBACK = 'F';

	public static final String URI_PREFIX = "eagler-direct:";

	/** One decoded ICE candidate. */
	public static final class Cand {
		public String address;      // IPv4/IPv6 literal, or hostname (e.g. *.local)
		public int port;
		public int type;            // 0=host 1=srflx 2=prflx 3=relay
		public boolean v6;
		public String raddr;        // null when absent
		public int rport;
		public boolean raddrHost;
	}

	/** Decoded connect code. */
	public static final class Decoded {
		public boolean offer;
		public String ufrag;
		public String pwd;
		public byte[] fingerprint32;
		public String setup;        // actpass | active | passive
		public List<Cand> candidates = new ArrayList<>();
	}

	private DirectConnectCodec() {
	}

	// ------------------------------------------------------------------ encode

	public static String packCompact(boolean offer, String ufrag, String pwd, byte[] fp32, String setup,
			List<Cand> candidates) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(96);
		out.write(VERSION_COMPACT);
		out.write(offer ? 'o' : 'a');
		byte[] uf = ufrag.getBytes(StandardCharsets.UTF_8);
		out.write(uf.length & 0xFF);
		out.write(uf, 0, uf.length);
		byte[] pw = pwd.getBytes(StandardCharsets.UTF_8);
		out.write(pw.length & 0xFF);
		out.write(pw, 0, pw.length);
		out.write(fp32, 0, 32);
		out.write(setupEnum(setup));
		int count = Math.min(candidates.size(), 255);
		out.write(count);
		for(int i = 0; i < count; ++i) {
			Cand c = candidates.get(i);
			byte[] addr = addressBytes(c);
			// Bit 2/3 mean "the bytes are a hostname, not an IP literal". The decoder
			// reads the u8 length for every address and only treats len 4/16 as an
			// IP when the flag is clear, so hostnames of any length round-trip.
			boolean addrHost = ipAddressBytes(c.address) == null;
			boolean raddrHost = c.raddr != null && ipAddressBytes(c.raddr) == null;
			int flags = (c.v6 ? 1 : 0) | (c.raddr != null ? 2 : 0) | (addrHost ? 4 : 0)
					| (raddrHost ? 8 : 0);
			out.write(flags);
			out.write((c.port >> 8) & 0xFF);
			out.write(c.port & 0xFF);
			out.write(addr.length & 0xFF);
			out.write(addr, 0, addr.length);
			if(c.raddr != null) {
				byte[] raddr = c.raddrHost ? c.raddr.getBytes(StandardCharsets.UTF_8) : ipAddressBytes(c.raddr);
				if(raddr == null) raddr = c.raddr.getBytes(StandardCharsets.UTF_8);
				out.write((c.rport >> 8) & 0xFF);
				out.write(c.rport & 0xFF);
				out.write(raddr.length & 0xFF);
				out.write(raddr, 0, raddr.length);
			}
			out.write(c.type & 0x3);
		}
		return MAGIC + VERSION_COMPACT + base64url(out.toByteArray());
	}

	private static byte[] addressBytes(Cand c) {
		if(c.address.indexOf('.') < 0 && c.address.indexOf(':') < 0) {
			return c.address.getBytes(StandardCharsets.UTF_8); // bare hostname
		}
		byte[] ip = ipAddressBytes(c.address);
		return ip != null ? ip : c.address.getBytes(StandardCharsets.UTF_8);
	}

	private static int setupEnum(String setup) {
		if("active".equals(setup)) return 1;
		if("passive".equals(setup)) return 2;
		return 0; // actpass
	}

	private static byte[] ipAddressBytes(String addr) {
		if(addr.indexOf(':') >= 0) return ipv6Bytes(addr);
		if(addr.indexOf('.') >= 0) return ipv4Bytes(addr);
		return null;
	}

	private static byte[] ipv4Bytes(String addr) {
		String[] p = addr.split("\\.", -1);
		if(p.length != 4) return null;
		byte[] out = new byte[4];
		for(int i = 0; i < 4; ++i) {
			int v;
			try { v = Integer.parseInt(p[i]); } catch(NumberFormatException e) { return null; }
			if(v < 0 || v > 255) return null;
			out[i] = (byte) v;
		}
		return out;
	}

	private static byte[] ipv6Bytes(String addr) {
		int dc = addr.indexOf("::");
		String[] head;
		String[] tail;
		if(dc >= 0) {
			String h = addr.substring(0, dc);
			String t = addr.substring(dc + 2);
			head = h.isEmpty() ? new String[0] : h.split(":", -1);
			tail = t.isEmpty() ? new String[0] : t.split(":", -1);
			int mid = 8 - head.length - tail.length;
			if(mid < 0) return null;
			String[] all = new String[8];
			for(int i = 0; i < head.length; ++i) all[i] = head[i];
			for(int i = 0; i < mid; ++i) all[head.length + i] = "0";
			for(int i = 0; i < tail.length; ++i) all[8 - tail.length + i] = tail[i];
			head = all;
		}else {
			head = addr.split(":", -1);
			if(head.length != 8) return null;
		}
		byte[] out = new byte[16];
		for(int i = 0; i < 8; ++i) {
			int v;
			try { v = Integer.parseInt(head[i], 16); } catch(NumberFormatException e) { return null; }
			if(v < 0 || v > 0xFFFF) return null;
			out[i * 2] = (byte) (v >> 8);
			out[i * 2 + 1] = (byte) (v & 0xFF);
		}
		return out;
	}

	/** Fallback pack: minimal line-stripped SDP, deflate-raw, base64url. */
	public static String packFallback(boolean offer, String sdp) {
		String stripped = stripSDP(sdp, offer);
		Deflater def = new Deflater(Deflater.BEST_COMPRESSION, true);
		byte[] src = stripped.getBytes(StandardCharsets.UTF_8);
		def.setInput(src);
		def.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream(src.length / 2 + 32);
		byte[] buf = new byte[4096];
		while(!def.finished()) {
			int n = def.deflate(buf);
			out.write(buf, 0, n);
		}
		def.end();
		return MAGIC + VERSION_FALLBACK + (offer ? "o" : "a") + base64url(out.toByteArray());
	}

	private static String stripSDP(String sdp, boolean offer) {
		String[] lines = sdp.replace("\r", "").split("\n");
		StringBuilder sb = new StringBuilder();
		boolean inMedia = false;
		for(String line : lines) {
			if(line.isEmpty()) continue;
			boolean media = line.startsWith("m=");
			if(media) {
				inMedia = offer ? line.startsWith("m=application ") : true;
			}
			if(media) { sb.append(line).append("\r\n"); continue; }
			if(!inMedia) {
				if(line.startsWith("v=") || line.startsWith("o=") || line.startsWith("s=") || line.startsWith("t=")
						|| line.startsWith("a=group:") || line.startsWith("a=msid-semantic")) {
					sb.append(line).append("\r\n");
				}
				continue;
			}
			if(line.startsWith("c=") || line.startsWith("a=mid:") || line.startsWith("a=sctp-port:")
					|| line.startsWith("a=max-message-size:") || line.startsWith("a=fingerprint:")
					|| line.startsWith("a=setup:") || line.startsWith("a=ice-ufrag:") || line.startsWith("a=ice-pwd:")) {
				sb.append(line).append("\r\n");
			}
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ decode

	public static Decoded unpack(String codeIn) {
		String code = codeIn == null ? "" : codeIn.trim();
		StringBuilder sb = new StringBuilder(code.length());
		for(int i = 0; i < code.length(); ++i) {
			char c = code.charAt(i);
			if(c != ' ' && c != '\n' && c != '\r' && c != '\t') sb.append(c);
		}
		code = sb.toString();
		if(!code.startsWith(MAGIC) || code.length() < 5) throw new IllegalArgumentException("Not a connect code");
		char ver = code.charAt(2);
		if(ver == VERSION_COMPACT) {
			return unpackCompact(b64urlDecode(code.substring(3)));
		}else if(ver == VERSION_FALLBACK) {
			boolean offer = code.charAt(3) == 'o';
			byte[] compressed = b64urlDecode(code.substring(4));
			try {
				Inflater inf = new Inflater(true);
				inf.setInput(compressed);
				ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4 + 64);
				byte[] buf = new byte[4096];
				while(!inf.finished()) {
					int n = inf.inflate(buf);
					if(n == 0 && inf.needsInput()) break;
					out.write(buf, 0, n);
				}
				inf.end();
				return parseStrippedSDP(new String(out.toByteArray(), StandardCharsets.UTF_8), offer);
			}catch(java.util.zip.DataFormatException e) {
				throw new IllegalArgumentException("Bad connect code", e);
			}
		}
		throw new IllegalArgumentException("Unknown connect code version " + ver);
	}

	private static Decoded unpackCompact(byte[] b) {
		if(b.length < 4 || (b[1] != 'o' && b[1] != 'a')) throw new IllegalArgumentException("Bad compact code");
		Decoded d = new Decoded();
		d.offer = b[1] == 'o';
		int[] off = new int[] { 2 };
		d.ufrag = takeBytes(b, off);
		d.pwd = takeBytes(b, off);
		if(off[0] + 32 > b.length) throw new IllegalArgumentException("Truncated fingerprint");
		d.fingerprint32 = new byte[32];
		System.arraycopy(b, off[0], d.fingerprint32, 0, 32);
		off[0] += 32;
		int setup = u8(b, off);
		d.setup = setup == 1 ? "active" : setup == 2 ? "passive" : "actpass";
		int n = u8(b, off);
		for(int i = 0; i < n; ++i) {
			Cand c = new Cand();
			int flags = u8(b, off);
			c.v6 = (flags & 1) != 0;
			boolean hasRaddr = (flags & 2) != 0;
			boolean addrHost = (flags & 4) != 0;
			c.raddrHost = (flags & 8) != 0;
			c.port = u16(b, off);
			c.address = takeAddress(b, off, addrHost);
			if(hasRaddr) {
				c.rport = u16(b, off);
				c.raddr = takeAddress(b, off, c.raddrHost);
			}
			c.type = u8(b, off) & 0x3;
			d.candidates.add(c);
		}
		return d;
	}

	private static int u8(byte[] b, int[] off) {
		if(off[0] >= b.length) throw new IllegalArgumentException("Truncated code");
		return b[off[0]++] & 0xFF;
	}

	private static int u16(byte[] b, int[] off) {
		int hi = u8(b, off);
		int lo = u8(b, off);
		return (hi << 8) | lo;
	}

	private static String takeBytes(byte[] b, int[] off) {
		int len = u8(b, off);
		if(off[0] + len > b.length) throw new IllegalArgumentException("Truncated code");
		String s = new String(b, off[0], len, StandardCharsets.UTF_8);
		off[0] += len;
		return s;
	}

	/** Every address on the wire is a u8 length followed by that many bytes; len 4
	 *  and len 16 are IP literals unless the hostname flag is set, anything else
	 *  is a UTF-8 hostname (old codes wrote the same length byte, so they decode
	 *  here too). */
	private static String takeAddress(byte[] b, int[] off, boolean hostname) {
		int len = u8(b, off);
		if(off[0] + len > b.length) throw new IllegalArgumentException("Truncated code");
		byte[] raw = new byte[len];
		System.arraycopy(b, off[0], raw, 0, len);
		off[0] += len;
		if(!hostname && len == 4) return ipv4ToString(raw);
		if(!hostname && len == 16) return ipv6ToString(raw);
		return new String(raw, StandardCharsets.UTF_8);
	}

	private static String ipv4ToString(byte[] raw) {
		return (raw[0] & 0xFF) + "." + (raw[1] & 0xFF) + "." + (raw[2] & 0xFF) + "." + (raw[3] & 0xFF);
	}

	private static String ipv6ToString(byte[] raw) {
		StringBuilder sb = new StringBuilder(39);
		for(int i = 0; i < 8; ++i) {
			if(i > 0) sb.append(':');
			sb.append(Integer.toHexString(((raw[i * 2] & 0xFF) << 8) | (raw[i * 2 + 1] & 0xFF)));
		}
		return sb.toString();
	}

	/** Parse one {@code a=candidate:} body (without the prefix) into a Cand. */
	public static Cand parseCandidate(String line) {
		return parseCandidateLine(line);
	}

	/** Rebuild a data-channel SDP from a decoded compact code. */
	public static String buildSDP(Decoded d) {
		StringBuilder sb = new StringBuilder(512);
		sb.append("v=0\r\n");
		sb.append("o=- 4611731400480061499 2 IN IP4 127.0.0.1\r\n");
		sb.append("s=-\r\nt=0 0\r\n");
		sb.append("a=group:BUNDLE 0\r\na=msid-semantic: WMS\r\n");
		sb.append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n");
		sb.append("c=IN IP4 0.0.0.0\r\n");
		sb.append("a=ice-ufrag:").append(d.ufrag).append("\r\n");
		sb.append("a=ice-pwd:").append(d.pwd).append("\r\n");
		sb.append("a=fingerprint:sha-256 ").append(fingerprintHex(d.fingerprint32)).append("\r\n");
		sb.append("a=setup:").append(d.setup).append("\r\n");
		sb.append("a=mid:0\r\na=sctp-port:5000\r\na=max-message-size:262144\r\n");
		for(int i = 0; i < d.candidates.size(); ++i) {
			Cand c = d.candidates.get(i);
			sb.append("a=candidate:").append(i + 1).append(" 1 udp 1677729535 ").append(c.address)
				.append(' ').append(c.port).append(" typ ").append(typeName(c.type));
			if(c.raddr != null) {
				sb.append(" raddr ").append(c.raddr).append(" rport ").append(c.rport);
			}
			sb.append(" generation 0 ufrag ").append(d.ufrag).append("\r\n");
		}
		return sb.toString();
	}

	private static String typeName(int type) {
		return type == 1 ? "srflx" : type == 2 ? "prflx" : type == 3 ? "relay" : "host";
	}

	public static String fingerprintHex(byte[] fp32) {
		StringBuilder sb = new StringBuilder(95);
		for(int i = 0; i < fp32.length; ++i) {
			if(i > 0) sb.append(':');
			sb.append(String.format("%02X", fp32[i] & 0xFF));
		}
		return sb.toString();
	}

	/** Parse a fallback ('F') code's stripped SDP back into a Decoded. */
	public static Decoded parseStrippedSDP(String sdp, boolean offer) {
		Decoded d = new Decoded();
		d.offer = offer;
		String fp = null;
		String[] lines = sdp.replace("\r", "").split("\n");
		for(String line : lines) {
			if(line.startsWith("a=ice-ufrag:")) d.ufrag = line.substring(12);
			else if(line.startsWith("a=ice-pwd:")) d.pwd = line.substring(10);
			else if(line.startsWith("a=fingerprint:")) fp = line.substring(14);
			else if(line.startsWith("a=setup:")) d.setup = line.substring(8);
			else if(line.startsWith("a=candidate:")) {
				d.candidates.add(parseCandidateLine(line.substring(12)));
			}
		}
		if(d.ufrag == null || d.pwd == null || fp == null) {
			throw new IllegalArgumentException("Stripped SDP missing identity fields");
		}
		d.fingerprint32 = fingerprintBytes(fp);
		return d;
	}

	private static byte[] fingerprintBytes(String fp) {
		String hex = fp.replace("sha-256", "").replace("SHA-256", "").trim().replace(":", "");
		if(hex.length() != 64) throw new IllegalArgumentException("Fingerprint must be sha-256");
		byte[] out = new byte[32];
		for(int i = 0; i < 32; ++i) {
			out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	private static Cand parseCandidateLine(String line) {
		String[] p = line.trim().split("\\s+");
		Cand c = new Cand();
		c.address = p[4];
		c.port = Integer.parseInt(p[5]);
		for(int i = 6; i < p.length - 1; ++i) {
			if("typ".equals(p[i])) {
				String t = p[i + 1];
				c.type = "srflx".equals(t) ? 1 : "prflx".equals(t) ? 2 : "relay".equals(t) ? 3 : 0;
			}else if("raddr".equals(p[i])) {
				c.raddr = p[i + 1];
			}else if("rport".equals(p[i])) {
				c.rport = Integer.parseInt(p[i + 1]);
			}
		}
		c.v6 = c.address.indexOf(':') >= 0;
		c.raddrHost = c.raddr != null && c.raddr.indexOf(':') < 0 && c.raddr.indexOf('.') < 0;
		boolean host = c.address.indexOf(':') < 0 && c.address.indexOf('.') < 0;
		if(host) c.v6 = false;
		return c;
	}

	// ------------------------------------------------------------------ base64url

	public static String base64url(byte[] data) {
		String b64 = java.util.Base64.getEncoder().encodeToString(data);
		int pad = b64.indexOf('=');
		return (pad >= 0 ? b64.substring(0, pad) : b64).replace('+', '-').replace('/', '_');
	}

	public static byte[] b64urlDecode(String s) {
		StringBuilder sb = new StringBuilder(s);
		while(sb.length() % 4 != 0) sb.append('=');
		String b64 = sb.toString().replace('-', '+').replace('_', '/');
		return java.util.Base64.getDecoder().decode(b64);
	}
}
