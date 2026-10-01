package net.lax1dude.eaglercraft.v1_8.spike;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.runtime.Fiber;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public class FiberLoopProbe {

	private static final int ITERATIONS = 2000;

	@JSFunctor
	interface TimeoutFn extends JSObject {
		void call();
	}

	@JSBody(params = { "fn" }, script = "setTimeout(function() { fn(); }, 0);")
	static native void schedule(TimeoutFn fn);

	@JSBody(params = { "iteration", "objectTop", "objectCapacity", "intTop", "intCapacity", "resetCount",
			"discardedObjects" }, script = "globalThis.__fiberProbe.push([iteration, objectTop, objectCapacity,"
					+ " intTop, intCapacity, resetCount, Number(discardedObjects)]);")
	static native void record(int iteration, int objectTop, int objectCapacity, int intTop, int intCapacity,
			int resetCount, long discardedObjects);

	@Async
	static native void nextFrame();

	private static void nextFrame(AsyncCallback<Void> callback) {
		schedule(() -> callback.complete(null));
	}

	@Async
	static native void synchronousJunction();

	private static void synchronousJunction(AsyncCallback<Void> callback) {
		callback.complete(null);
	}

	private static void record(int iteration) {
		Fiber fiber = Fiber.current();
		record(iteration, fiber.debugObjectTop(), fiber.debugObjectCapacity(), fiber.debugIntTop(),
				fiber.debugIntCapacity(), fiber.debugStaleStackResetCount(), fiber.debugStaleObjectValuesDiscarded());
	}

	public static void main(String[] args) {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		record(0);
		for(int i = 1; i <= ITERATIONS; ++i) {
			synchronousJunction();
			nextFrame();
			if((i % 100) == 0) {
				record(i);
			}
		}
		record(-1);
	}
}
