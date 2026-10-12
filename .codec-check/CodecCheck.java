import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import net.lax1dude.eaglercraft.v1_8.internal.teavm.DirectConnectCodec;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.DirectConnectCodec.Cand;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.DirectConnectCodec.Decoded;

public class CodecCheck {

	private static int failures = 0;

	private static void check(boolean ok, String what) {
		if(!ok) {
			++failures;
			System.out.println("FAIL: " + what);
		}else {
			System.out.println("ok: " + what);
		}
	}

	private static boolean sameAddress(Cand a, Cand b) {
		if(a.address.equals(b.address)) return true;
		if(!a.v6) return false;
		try {
			return java.net.InetAddress.getByName(a.address).equals(java.net.InetAddress.getByName(b.address));
		}catch(java.net.UnknownHostException e) {
			return false;
		}
	}

	private static Cand cand(String address, int port, int type, String raddr, int rport) {
		Cand c = new Cand();
		c.address = address;
		c.port = port;
		c.type = type;
		c.v6 = address.indexOf(':') >= 0;
		c.raddr = raddr;
		c.rport = rport;
		c.raddrHost = raddr != null && raddr.indexOf(':') < 0 && raddr.indexOf('.') < 0;
		return c;
	}

	public static void main(String[] args) throws Exception {
		String ufrag = "abcd";
		String pwd = "0123456789abcdef0123456789abcdef";
		byte[] fp = new byte[32];
		for(int i = 0; i < 32; ++i) fp[i] = (byte) (i * 7 + 1);
		List<Cand> cands = new ArrayList<>();
		cands.add(cand("192.168.1.5", 54321, 0, null, 0));
		cands.add(cand("136.61.15.136", 52355, 1, "0.0.0.0", 0));
		cands.add(cand("b415e6aa-a694-4e0c-9b76-9d4b02d39b0d.local", 50000, 0, null, 0));
		cands.add(cand("localhost", 50001, 0, null, 0));
		cands.add(cand("fe80::1234:5678:9abc:def0", 50002, 0, null, 0));

		String code = DirectConnectCodec.packCompact(true, ufrag, pwd, fp, "actpass", cands);
		Decoded d = DirectConnectCodec.unpack(code);
		check(d.offer, "offer flag");
		check(ufrag.equals(d.ufrag), "ufrag");
		check(pwd.equals(d.pwd), "pwd");
		check("actpass".equals(d.setup), "setup");
		check(java.util.Arrays.equals(fp, d.fingerprint32), "fingerprint");
		check(d.candidates.size() == cands.size(), "candidate count");
		for(int i = 0; i < Math.min(d.candidates.size(), cands.size()); ++i) {
			Cand a = cands.get(i);
			Cand b = d.candidates.get(i);
			check(sameAddress(a, b), "cand[" + i + "] address " + a.address + " -> " + b.address);
			check(a.port == b.port, "cand[" + i + "] port");
			check(a.type == b.type, "cand[" + i + "] type " + a.type + " -> " + b.type);
			check(a.v6 == b.v6, "cand[" + i + "] v6");
			check(a.raddr == null ? b.raddr == null : a.raddr.equals(b.raddr),
					"cand[" + i + "] raddr " + a.raddr + " -> " + b.raddr);
			check(a.raddr == null || a.rport == b.rport, "cand[" + i + "] rport");
		}

		String sdp = DirectConnectCodec.buildSDP(d);
		check(sdp.contains("136.61.15.136"), "SDP carries real srflx address (no byte shift)");
		check(sdp.contains("b415e6aa-a694-4e0c-9b76-9d4b02d39b0d.local"), "SDP carries mDNS name");
		check(sdp.contains("typ srflx raddr 0.0.0.0 rport 0"), "SDP srflx raddr intact");
		check(sdp.contains("fe80:0:0:0:1234:5678:9abc:def0"), "SDP IPv6 intact");
		check(!sdp.contains("4.136.61.15"), "no shifted address reproduced");

		// Old-encoder bytes: identical layout, but bit 2(0x4) only set when len > 16.
		// Verify the new decoder still reads a <=16-byte hostname (localhost, len 9).
		byte[] payload = oldPack(true, ufrag, pwd, fp, "actpass",
				new Cand[] { cand("localhost", 50001, 0, null, 0) });
		String oldCode = "EGC" + new String(Base64.getUrlEncoder().withoutPadding().encode(payload), StandardCharsets.UTF_8);
		Decoded oldDecoded = DirectConnectCodec.unpack(oldCode);
		check(oldDecoded.candidates.size() == 1 && "localhost".equals(oldDecoded.candidates.get(0).address),
				"old-format short hostname decodes: " + (oldDecoded.candidates.isEmpty() ? "<none>"
						: oldDecoded.candidates.get(0).address));

		// Old real-world case: srflx written by the pre-fix encoder (bit 2 clear, len byte 4)
		byte[] oldSrflx = oldPack(true, ufrag, pwd, fp, "actpass",
				new Cand[] { cand("136.61.15.136", 52355, 1, null, 0) });
		Decoded oldSrflxDecoded = DirectConnectCodec.unpack("EGC" + new String(
				Base64.getUrlEncoder().withoutPadding().encode(oldSrflx), StandardCharsets.UTF_8));
		check("136.61.15.136".equals(oldSrflxDecoded.candidates.get(0).address)
				&& oldSrflxDecoded.candidates.get(0).type == 1, "old-format srflx decodes intact");

		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
		if(failures != 0) System.exit(1);
	}

	/** Replica of the pre-fix encoder byte layout (always writes the length byte,
	 *  but sets the hostname flag only for addresses longer than 16 bytes). */
	private static byte[] oldPack(boolean offer, String ufrag, String pwd, byte[] fp32, String setup, Cand[] cands)
			throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write('C');
		out.write(offer ? 'o' : 'a');
		writeBytes(out, ufrag);
		writeBytes(out, pwd);
		out.write(fp32, 0, 32);
		out.write("active".equals(setup) ? 1 : "passive".equals(setup) ? 2 : 0);
		out.write(cands.length);
		for(Cand c : cands) {
			byte[] addr = bytesFor(c.address);
			int flags = (c.v6 ? 1 : 0) | (c.raddr != null ? 2 : 0) | (addr.length > 16 ? 4 : 0) | 0;
			out.write(flags);
			out.write((c.port >> 8) & 0xFF);
			out.write(c.port & 0xFF);
			out.write(addr.length & 0xFF);
			out.write(addr, 0, addr.length);
			out.write(c.type & 3);
		}
		return out.toByteArray();
	}

	private static void writeBytes(ByteArrayOutputStream out, String s) {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		out.write(b.length & 0xFF);
		out.write(b, 0, b.length);
	}

	private static byte[] bytesFor(String address) {
		if(address.indexOf(':') >= 0) return ipv6(address);
		if(address.indexOf('.') >= 0) {
			try {
				String[] p = address.split("\\.", -1);
				if(p.length == 4) {
					byte[] r = new byte[4];
					for(int i = 0; i < 4; ++i) r[i] = (byte) Integer.parseInt(p[i]);
					return r;
				}
			}catch(NumberFormatException ignored) {
			}
		}
		return address.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] ipv6(String addr) {
		String[] head;
		int dc = addr.indexOf("::");
		if(dc >= 0) {
			String h = addr.substring(0, dc);
			String t = addr.substring(dc + 2);
			String[] hh = h.isEmpty() ? new String[0] : h.split(":", -1);
			String[] tt = t.isEmpty() ? new String[0] : t.split(":", -1);
			head = new String[8];
			for(int i = 0; i < hh.length; ++i) head[i] = hh[i];
			for(int i = 0; i < 8 - hh.length - tt.length; ++i) head[hh.length + i] = "0";
			for(int i = 0; i < tt.length; ++i) head[8 - tt.length + i] = tt[i];
		}else {
			head = addr.split(":", -1);
		}
		byte[] out = new byte[16];
		for(int i = 0; i < 8; ++i) {
			int v = Integer.parseInt(head[i], 16);
			out[i * 2] = (byte) (v >> 8);
			out[i * 2 + 1] = (byte) (v & 0xFF);
		}
		return out;
	}
}
