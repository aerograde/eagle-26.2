package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import io.netty.util.internal.TypeParameterMatcher;

/**
 * REPLACE-mode donor for io.netty.util.internal.TypeParameterMatcher (see
 * plugin.JdkMethodInjector). The real find() resolves a handler's generic type
 * parameter via getGenericSuperclass()/ParameterizedType — generic-signature
 * reflection metadata TeaVM does not emit — so it throws
 * "IllegalStateException: unknown type parameter 'I'" the moment the first
 * SimpleChannelInboundHandler subclass is constructed (world-load gap #8: MC's
 * Connection extends SimpleChannelInboundHandler&lt;Packet&gt;, built when the
 * integrated server's LocalChannel connects).
 *
 * Return the accept-everything matcher instead. Semantically safe for MC: its
 * pipelines are strictly typed (only Packets/ByteBufs reach each handler), so
 * the matcher's type-based skip never actually filters anything on web.
 */
public abstract class TypeParameterMatcherInject {

	public static TypeParameterMatcher find(Object object, Class<?> parametrizedSuperclass, String typeParamName) {
		return TypeParameterMatcher.get(Object.class);
	}

}
