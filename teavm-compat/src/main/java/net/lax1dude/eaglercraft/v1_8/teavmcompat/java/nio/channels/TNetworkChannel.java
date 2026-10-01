package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.channels.Channel;
import java.util.Set;

/** java.nio.channels.NetworkChannel — link-only browser compatibility surface. */
public interface TNetworkChannel extends Channel {

	TNetworkChannel bind(SocketAddress local) throws IOException;

	SocketAddress getLocalAddress() throws IOException;

	<T> TNetworkChannel setOption(SocketOption<T> name, T value) throws IOException;

	<T> T getOption(SocketOption<T> name) throws IOException;

	Set<SocketOption<?>> supportedOptions();
}
