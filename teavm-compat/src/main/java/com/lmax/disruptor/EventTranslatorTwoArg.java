package com.lmax.disruptor;

/**
 * Minimal LMAX disruptor EventTranslatorTwoArg (see EventFactory).
 */
public interface EventTranslatorTwoArg<T, A, B> {

	void translateTo(T event, long sequence, A arg0, B arg1);

}
