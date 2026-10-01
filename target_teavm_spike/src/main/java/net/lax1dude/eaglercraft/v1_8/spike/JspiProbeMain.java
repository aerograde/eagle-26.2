package net.lax1dude.eaglercraft.v1_8.spike;

import org.teavm.interop.Import;
import org.teavm.runtime.Fiber;

public final class JspiProbeMain {

	private static final int ITERATIONS = 2400;

	private static int nestedFinallyCount;

	@Import(module = "jspiProbe", name = "suspend")
	private static native int suspend(int iteration, int mode);

	@Import(module = "jspiProbe", name = "event")
	private static native void event(int code, int value0, int value1);

	private static int expectedValue(int iteration) {
		return iteration * 1103515245 + 12345;
	}

	private static int waitOne(int iteration) {
		try {
			return suspend(iteration, 0);
		} finally {
			++nestedFinallyCount;
		}
	}

	private static void run() {
		int checksum = 0x13579BDF;
		int expectedChecksum = checksum;
		int parityErrors = 0;
		int outerFinallyCount = 0;
		int javaExceptionCount = 0;
		int fiberNullChecks = 0;
		int fiberNonNullChecks = 0;
		int maxObjectTop = 0;
		int maxObjectCapacity = 0;
		int maxIntTop = 0;
		int maxIntCapacity = 0;
		nestedFinallyCount = 0;

		event(1, ITERATIONS, 0);
		for(int i = 1; i <= ITERATIONS; ++i) {
			try {
				int value = waitOne(i);
				int expected = expectedValue(i);
				if(value != expected) {
					++parityErrors;
				}
				checksum = Integer.rotateLeft(checksum ^ value, 5) + i;
				expectedChecksum = Integer.rotateLeft(expectedChecksum ^ expected, 5) + i;
				if(i == 777) {
					throw new IllegalStateException("jspi-probe");
				}
			} catch(IllegalStateException ex) {
				++javaExceptionCount;
			} finally {
				++outerFinallyCount;
			}

			Fiber fiber = Fiber.current();
			if(fiber == null) {
				++fiberNullChecks;
			} else {
				++fiberNonNullChecks;
				maxObjectTop = Math.max(maxObjectTop, fiber.debugObjectTop());
				maxObjectCapacity = Math.max(maxObjectCapacity, fiber.debugObjectCapacity());
				maxIntTop = Math.max(maxIntTop, fiber.debugIntTop());
				maxIntCapacity = Math.max(maxIntCapacity, fiber.debugIntCapacity());
			}
		}

		event(2, checksum, expectedChecksum);
		event(3, parityErrors, javaExceptionCount);
		event(4, nestedFinallyCount, outerFinallyCount);
		event(5, fiberNullChecks, fiberNonNullChecks);
		event(6, maxObjectTop, maxObjectCapacity);
		event(7, maxIntTop, maxIntCapacity);
	}

	private static void reject() {
		event(8, 0, 0);
		try {
			suspend(ITERATIONS + 1, 1);
			event(9, 0, 0);
		} finally {
			event(10, 1, 0);
		}
	}

	public static void main(String[] args) {
		run();
		reject();
	}

	private JspiProbeMain() {
	}
}
