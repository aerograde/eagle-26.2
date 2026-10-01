package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * REPLACE-mode donor for java.lang.Throwable (see plugin.JdkMethodInjector).
 *
 * TeaVM 0.13's TThrowable initializes {@code suppressed = new TThrowable[0]} in the
 * FIELD initializer, but exceptions created by the runtime / native-JS-wrapped path
 * (netty's no-Unsafe/no-VarHandle fallbacks, and any wrapped JS TypeError) bypass the
 * ctor field-init, leaving {@code suppressed} null. getSuppressed() then does
 * {@code TArrays.copyOf(suppressed, suppressed.length)} -> reads null.length ->
 * "TypeError: Cannot read properties of null (reading 'data')". That fires inside netty's
 * DefaultPromise.rethrowIfFailed() at EVERY syncUninterruptibly() over a failed future,
 * MASKING the true cause (the integrated-server bind, and every protocol-change transition).
 *
 * Suppressed exceptions are a debug-only nicety we don't need on web; return an empty
 * array so getSuppressed() can never crash. Does NOT touch throw/catch/finally flow.
 */
public abstract class ThrowableInject {

	public Throwable[] getSuppressed() {
		return new Throwable[0];
	}

}
