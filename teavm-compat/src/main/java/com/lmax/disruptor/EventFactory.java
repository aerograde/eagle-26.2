package com.lmax.disruptor;

/**
 * Minimal LMAX disruptor EventFactory — log4j2's async logger classes link
 * against this optional dependency; the async path is never enabled in the
 * browser runtime. Plain class (not java.*), so it is supplied directly.
 */
public interface EventFactory<T> {

	T newInstance();

}
