package net.lax1dude.eaglercraft.v1_8.sp.server.export;

/**
 * Progress sink for the 26.2 world import/export converters. Implementations
 * must be safe to call from a background worker thread (the GUI side polls a
 * volatile snapshot every tick).
 */
public interface EaglerConvertProgress {

	/**
	 * @param detail   human-readable detail line ("N files, M KB"), may be empty
	 * @param fraction 0..1 completion estimate, or -1 when indeterminate
	 */
	void report(String detail, float fraction);

}
