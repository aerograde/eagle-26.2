/*
 *  Copyright 2018 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.runtime;

import org.teavm.interop.AsyncCallback;
import org.teavm.interop.StaticInit;
import org.teavm.interop.Unmanaged;

@StaticInit
public class Fiber {
    public static final int STATE_RUNNING = 0;
    public static final int STATE_SUSPENDING = 1;
    public static final int STATE_RESUMING = 2;
    public static int userThreadCount = 1;

    private int[] intValues;
    private int intTop;
    private long[] longValues;
    private int longTop;
    private float[] floatValues;
    private int floatTop;
    private double[] doubleValues;
    private int doubleTop;
    private Object[] objectValues;
    private int objectTop;
    private int state;
    private FiberRunner runner;
    private Object result;
    private Throwable exception;
    private boolean isPendingResume;
    private boolean daemon;
    private int pendingCallCount;
    private int staleStackResetCount;
    private long staleObjectValuesDiscarded;

    private static Fiber current;
    // EAGLER wasm-gc fix (BLOCKER #15): placeholder fiber returned by current() when no real fiber is
    // active (module initializer / eager <clinit>, before Fiber.startMain). See current().
    private static Fiber outsideFiber;
    private static PendingCall lastPendingCall;

    private Fiber(FiberRunner runner, boolean daemon) {
        this.runner = runner;
        this.daemon = daemon;
    }

    public void push(int value) {
        if (intValues == null) {
            intValues = new int[4];
        } else if (intTop + 1 == intValues.length) {
            // Do not call Arrays.copyOf here. In a large Wasm-GC graph TeaVM can
            // coroutine-transform that method; its prologue then saves state by
            // calling reversePush(), which re-enters this already-full stack and
            // recurses until the browser's Wasm call stack overflows.
            int[] grown = new int[intValues.length * 3 / 2];
            for (int i = 0; i < intValues.length; ++i) {
                grown[i] = intValues[i];
            }
            intValues = grown;
        }
        intValues[intTop++] = value;
    }

    public void push(long value) {
        if (longValues == null) {
            longValues = new long[4];
        } else if (longTop + 1 == longValues.length) {
            long[] grown = new long[longValues.length * 3 / 2];
            for (int i = 0; i < longValues.length; ++i) {
                grown[i] = longValues[i];
            }
            longValues = grown;
        }
        longValues[longTop++] = value;
    }

    public void push(float value) {
        if (floatValues == null) {
            floatValues = new float[4];
        } else if (floatTop + 1 == floatValues.length) {
            float[] grown = new float[floatValues.length * 3 / 2];
            for (int i = 0; i < floatValues.length; ++i) {
                grown[i] = floatValues[i];
            }
            floatValues = grown;
        }
        floatValues[floatTop++] = value;
    }

    public void push(double value) {
        if (doubleValues == null) {
            doubleValues = new double[4];
        } else if (doubleTop + 1 == doubleValues.length) {
            double[] grown = new double[doubleValues.length * 3 / 2];
            for (int i = 0; i < doubleValues.length; ++i) {
                grown[i] = doubleValues[i];
            }
            doubleValues = grown;
        }
        doubleValues[doubleTop++] = value;
    }

    public void push(Object value) {
        if (objectValues == null) {
            objectValues = new Object[4];
        } else if (objectTop + 1 == objectValues.length) {
            Object[] grown = new Object[objectValues.length * 3 / 2];
            for (int i = 0; i < objectValues.length; ++i) {
                grown[i] = objectValues[i];
            }
            objectValues = grown;
        }
        objectValues[objectTop++] = value;
    }

    public static void reversePush(int value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(long value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(float value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(double value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(Object value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(PlatformObject value, Fiber fiber) {
        fiber.push(new PlatformObjectWrapper(value));
    }

    public static void reversePush(PlatformFunction value, Fiber fiber) {
        fiber.push(new PlatformFunctionWrapper(value));
    }

    @Unmanaged
    public int popInt() {
        return intValues[--intTop];
    }

    @Unmanaged
    public long popLong() {
        return longValues[--longTop];
    }

    @Unmanaged
    public float popFloat() {
        return floatValues[--floatTop];
    }

    @Unmanaged
    public double popDouble() {
        return doubleValues[--doubleTop];
    }

    @Unmanaged
    public Object popObject() {
        Object result = objectValues[--objectTop];
        objectValues[objectTop] = null;
        return result;
    }

    public PlatformObject popPlatformObject() {
        var wrapper = (PlatformObjectWrapper) popObject();
        return wrapper.object;
    }

    public PlatformFunction popPlatformFunction() {
        var wrapper = (PlatformFunctionWrapper) popObject();
        return wrapper.object;
    }

    @Unmanaged
    public static Fiber current() {
        var c = current;
        if (c != null) {
            return c;
        }
        // EAGLER wasm-gc fix (BLOCKER #15): called OUTSIDE any active fiber. In wasm-gc this happens
        // when a coroutine-transformed (async) method is invoked from the MODULE INITIALIZER / an eager
        // <clinit> -- before Fiber.startMain() installs the main fiber, so `current` is still null.
        // In the full client, org.teavm.jso.impl.wasmgc.WasmGCJSRuntime.stringToJs is made async by a
        // synchronized method reachable on its exception paths (WasmGCSupport.npe/aiiobe ->
        // Throwable.decorateException -> ... a monitor in a client exception subclass), and the string-
        // constant pool init calls it during module start. Stock current() returned null, so the async
        // prologue's Fiber.current().isResuming() dereferenced null -> "dereferencing a null pointer".
        // Return a placeholder fiber instead: its state is STATE_RUNNING (0) so isResuming()==false and
        // the async method runs its body synchronously. Init-time async calls never reach a real
        // suspension point (their suspending edges are dead error paths), so this fiber is only read,
        // never suspended/resumed. `current` itself is left null (unchanged), and start() saves/restores
        // `current` around real fibers, so nothing else is perturbed.
        c = outsideFiber;
        if (c == null) {
            c = new Fiber(null, true);
            outsideFiber = c;
        }
        return c;
    }

    @Unmanaged
    public boolean isSuspending() {
        return state == STATE_SUSPENDING;
    }

    @Unmanaged
    public boolean isResuming() {
        return state == STATE_RESUMING;
    }

    public int debugObjectTop() {
        return objectTop;
    }

    public int debugObjectCapacity() {
        return objectValues != null ? objectValues.length : 0;
    }

    public int debugIntTop() {
        return intTop;
    }

    public int debugIntCapacity() {
        return intValues != null ? intValues.length : 0;
    }

    public int debugStaleStackResetCount() {
        return staleStackResetCount;
    }

    public long debugStaleObjectValuesDiscarded() {
        return staleObjectValuesDiscarded;
    }

    @Unmanaged
    public static boolean getBoolean(Object v) {
        return v != null ? (Boolean) v : false;
    }

    @Unmanaged
    public static byte getByte(Object v) {
        return v != null ? (Byte) v : 0;
    }

    @Unmanaged
    public static short getShort(Object v) {
        return v != null ? (Short) v : 0;
    }

    @Unmanaged
    public static int getInt(Object v) {
        return v != null ? (Integer) v : 0;
    }

    @Unmanaged
    public static char getChar(Object v) {
        return v != null ? (Character) v : 0;
    }

    @Unmanaged
    public static long getLong(Object v) {
        return v != null ? (Long) v : 0;
    }

    @Unmanaged
    public static float getFloat(Object v) {
        return v != null ? (Float) v : 0;
    }

    @Unmanaged
    public static double getDouble(Object v) {
        return v != null ? (Double) v : 0;
    }

    public static Object suspend(AsyncCall call) throws Throwable {
        Fiber fiber = current();
        Thread javaThread = Thread.currentThread();
        if (fiber.isResuming()) {
            fiber.state = STATE_RUNNING;
            if (fiber.exception != null) {
                throw fiber.exception;
            }
            return fiber.result;
        }
        if (fiber.pendingCallCount == 0) {
            fiber.repairStaleStack();
        }
        PendingCall pendingCall = new PendingCall(call, lastPendingCall, fiber);
        if (lastPendingCall != null) {
            lastPendingCall.next = pendingCall;
        }
        lastPendingCall = pendingCall;
        ++fiber.pendingCallCount;

        // EAGLER wasm-gc fix (BLOCKER #17): run the paired async body (call.run) with the fiber in
        // RUNNING state, NOT SUSPENDING. The paired body of an @Async method runs SYNCHRONOUSLY here.
        // At Bootstrap scale TeaVM's wasm-gc coroutine transform can mark that paired body async when
        // it contains a virtual call whose method-family has an async override somewhere in the graph
        // (e.g. a String concatenation compiles to a virtual toString(), and some reachable class's
        // toString() is async -> the whole toString() family is "async-split" -> every toString() call
        // site becomes a coroutine suspension point). A coroutine-transformed method performs an
        // isSuspending() check after each such call site. If the fiber were already SUSPENDING (as the
        // stock code sets it before call.run), that check reads the OUTER suspension's state and the
        // paired body SPURIOUSLY UNWINDS before it can schedule the real resume (e.g. Window.setTimeout)
        // -> no resume is ever scheduled -> permanent hang (first real @Async suspend never completes).
        // Keeping the fiber RUNNING lets those synchronously-completing call sites see isSuspending()==
        // false and fall through, so the paired body runs to completion and schedules its resume. We
        // mark the fiber SUSPENDING only AFTER the body returns, and only if it left the fiber RUNNING
        // (i.e. it ran synchronously). If the body genuinely suspended on a nested async it already left
        // the fiber SUSPENDING, and if it resumed synchronously it left RESUMING; in both cases we must
        // not clobber that state. (For a synchronously-completing async body, complete() has already set
        // isPendingResume, so the SUSPENDING we set here makes the caller unwind and start() bounce.)
        fiber.state = STATE_RUNNING;
        pendingCall.callback = new AsyncCallbackImpl(pendingCall, javaThread, fiber);
        call.run(pendingCall.callback);
        if (fiber.state == STATE_RUNNING) {
            fiber.state = STATE_SUSPENDING;
        }
        return null;
    }

    private void repairStaleStack() {
        if (intTop == 0 && longTop == 0 && floatTop == 0 && doubleTop == 0 && objectTop == 0) {
            return;
        }
        ++staleStackResetCount;
        staleObjectValuesDiscarded += objectTop;
        intValues = null;
        intTop = 0;
        longValues = null;
        longTop = 0;
        floatValues = null;
        floatTop = 0;
        doubleValues = null;
        doubleTop = 0;
        objectValues = null;
        objectTop = 0;
    }

    static class AsyncCallbackImpl implements AsyncCallback<Object> {
        PendingCall pendingCall;
        Thread javaThread;
        Fiber fiber;

        AsyncCallbackImpl(PendingCall pendingCall, Thread javaThread, Fiber fiber) {
            this.pendingCall = pendingCall;
            this.javaThread = javaThread;
            this.fiber = fiber;
        }

        @Override
        public void complete(Object result) {
            if (Fiber.current == this.fiber) {
                fiber.result = result;
                fiber.isPendingResume = true;
                removePendingCall();
            } else {
                setCurrentThread(javaThread);
                javaThread = null;
                Fiber fiber = this.fiber;
                this.fiber = null;
                fiber.result = result;
                removePendingCall();
                fiber.resume();
            }
        }

        @Override
        public void error(Throwable e) {
            if (Fiber.current == this.fiber) {
                fiber.exception = e;
                fiber.isPendingResume = true;
                removePendingCall();
            } else {
                setCurrentThread(javaThread);
                javaThread = null;
                Fiber fiber = this.fiber;
                this.fiber = null;
                fiber.exception = e;
                removePendingCall();
                fiber.resume();
            }
        }

        private void removePendingCall() {
            Fiber owner = pendingCall.owner;
            if (pendingCall.previous != null) {
                pendingCall.previous.next = pendingCall.next;
            }
            if (pendingCall.next != null) {
                pendingCall.next.previous = pendingCall.previous;
            }
            if (pendingCall == lastPendingCall) {
                lastPendingCall = pendingCall.previous;
            }
            pendingCall = null;
            --owner.pendingCallCount;
        }
    }

    static native void setCurrentThread(Thread thread);

    public static void start(FiberRunner runner, boolean daemon) {
        new Fiber(runner, daemon).start();
    }

    static void startMain(String[] args) {
        start(() -> runMain(args), false);
    }

    public static native void runMain(String[] args);

    private void start() {
        Fiber former = current;
        current = this;
        while (true) {
            runner.run();
            if (!isPendingResume) {
                break;
            }
            isPendingResume = false;
            state = STATE_RESUMING;
        }
        current = former;
        if (!isSuspending() && !daemon && --userThreadCount == 0) {
            EventQueue.stop();
        }
    }

    void resume() {
        state = STATE_RESUMING;
        start();
    }

    public interface FiberRunner {
        void run();
    }

    public interface AsyncCall {
        void run(AsyncCallback<?> callback);
    }

    static class PendingCall {
        AsyncCall value;
        PendingCall next;
        PendingCall previous;
        AsyncCallbackImpl callback;
        Fiber owner;

        PendingCall(AsyncCall value, PendingCall previous, Fiber owner) {
            this.value = value;
            this.next = null;
            this.previous = previous;
            this.owner = owner;
        }
    }

    public static class PlatformObjectWrapper {
        public final PlatformObject object;

        PlatformObjectWrapper(PlatformObject object) {
            this.object = object;
        }
    }

    public static class PlatformFunctionWrapper {
        public final PlatformFunction object;

        PlatformFunctionWrapper(PlatformFunction object) {
            this.object = object;
        }
    }

    public static class PlatformObject {
    }

    public static class PlatformFunction {
    }
}
