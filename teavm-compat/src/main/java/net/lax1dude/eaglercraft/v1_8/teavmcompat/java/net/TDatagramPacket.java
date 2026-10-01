package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

/**
 * java.net.DatagramPacket — value stub. No UDP in the browser runtime; the
 * class only exists so DatagramSocket signatures link. Fields are held inertly.
 */
public class TDatagramPacket {

	private byte[] buf;
	private int offset;
	private int length;
	private TInetAddress address;
	private int port = -1;

	public TDatagramPacket(byte[] buf, int length) {
		this.buf = buf;
		this.length = length;
	}

	public TDatagramPacket(byte[] buf, int offset, int length) {
		this.buf = buf;
		this.offset = offset;
		this.length = length;
	}

	public TDatagramPacket(byte[] buf, int length, TInetAddress address, int port) {
		this.buf = buf;
		this.length = length;
		this.address = address;
		this.port = port;
	}

	public TDatagramPacket(byte[] buf, int offset, int length, TInetAddress address, int port) {
		this.buf = buf;
		this.offset = offset;
		this.length = length;
		this.address = address;
		this.port = port;
	}

	public TDatagramPacket(byte[] buf, int length, TSocketAddress address) {
		this.buf = buf;
		this.length = length;
	}

	public synchronized byte[] getData() {
		return buf;
	}

	public synchronized int getOffset() {
		return offset;
	}

	public synchronized int getLength() {
		return length;
	}

	public synchronized void setData(byte[] buf) {
		this.buf = buf;
	}

	public synchronized void setData(byte[] buf, int offset, int length) {
		this.buf = buf;
		this.offset = offset;
		this.length = length;
	}

	public synchronized void setLength(int length) {
		this.length = length;
	}

	public synchronized TInetAddress getAddress() {
		return address;
	}

	public synchronized void setAddress(TInetAddress address) {
		this.address = address;
	}

	public synchronized int getPort() {
		return port;
	}

	public synchronized void setPort(int port) {
		this.port = port;
	}

}
