package net.lax1dude.eaglercraft.v1_8.sp.server;

import com.mojang.logging.LogUtils;
import java.util.Arrays;
import java.util.function.Supplier;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.metrics.MetricCategory;
import org.slf4j.Logger;

/** Explicit /debug capture only: fixed storage, elapsed time, never a CPU profile. */
public final class EaglerBoundedTickProfiler implements ProfilerFiller {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int STACK_LIMIT = 64;
    private static final int NODE_LIMIT = 2048;
    private static final int LABEL_LIMIT = 96;
    private static final int TICK_LIMIT = 200;
    private static final long WALL_LIMIT_NANOS = 10_000_000_000L;

    public enum Stop { COMMAND, TICK_CAP, WALL_CAP, DISABLED, RESTART, FAILURE, TEARDOWN }

    private final String[] labels = new String[NODE_LIMIT];
    private final int[] parents = new int[NODE_LIMIT];
    private final boolean[] counters = new boolean[NODE_LIMIT];
    private final long[] inclusive = new long[NODE_LIMIT];
    private final long[] exclusive = new long[NODE_LIMIT];
    private final long[] counts = new long[NODE_LIMIT];
    private final long[] maxima = new long[NODE_LIMIT];
    private final long[] values = new long[NODE_LIMIT];
    private final int[] lookup = new int[NODE_LIMIT * 2];
    private final int[] stackNodes = new int[STACK_LIMIT];
    private final long[] stackStarts = new long[STACK_LIMIT];
    private final long[] stackChildren = new long[STACK_LIMIT];
    private final long captureId;
    private final int startedTick;
    private final long startedNanos;
    private long lastNanos;
    private int nodeCount;
    private int depth;
    private long skippedDepth;
    private long skippedStarted;
    private int scopeTicks;
    private int completedTicks;
    private long droppedScopes;
    private long droppedCounters;
    private long supplierFailures;
    private boolean tickStarted;
    private boolean finished;
    private boolean depthOverflow;
    private boolean nodeOverflow;
    private boolean labelOverflow;
    private boolean numericOverflow;
    private boolean clockRegression;
    private boolean imbalance;
    private boolean incomplete;
    private Stop requestedStop;

    private EaglerBoundedTickProfiler(long captureId, int startedTick) {
        this.captureId = captureId;
        this.startedTick = startedTick;
        this.startedNanos = this.lastNanos = Util.getNanos();
    }

    public static EaglerBoundedTickProfiler startIfEnabled(long captureId, int startedTick) {
        // Only ServerWorkerHost enters serverMain. Desktop and single-thread mode
        // use supervisorTick/singleThreadMain and never set this liveness flag.
        if (!EaglerServerPerf.isEnabled() || !EaglerIntegratedServerWorker26.isServerMainLoopAlive()) return null;
        try {
            return new EaglerBoundedTickProfiler(captureId, startedTick);
        } catch (Throwable ignored) {
            return null; // A diagnostic allocation must not prevent a server tick.
        }
    }

    private long now() {
        long value = Util.getNanos();
        if (value < lastNanos) {
            clockRegression = true;
            incomplete = true;
            return lastNanos;
        }
        lastNanos = value;
        return value;
    }

    private long add(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0L) {
            numericOverflow = true;
            incomplete = true;
            return b < 0L ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return result;
    }

    private int node(int parent, String label, boolean counter) {
        if (label == null || label.length() > LABEL_LIMIT) {
            labelOverflow = true;
            incomplete = true;
            return -1;
        }
        int hash = (label.hashCode() * 31 + parent) * 31 + (counter ? 1 : 0);
        int slot = (hash ^ (hash >>> 16)) & (lookup.length - 1);
        while (lookup[slot] != 0) {
            int n = lookup[slot] - 1;
            if (parents[n] == parent && counters[n] == counter && labels[n].equals(label)) return n;
            slot = (slot + 1) & (lookup.length - 1);
        }
        if (nodeCount == NODE_LIMIT) {
            nodeOverflow = true;
            incomplete = true;
            return -1;
        }
        int n = nodeCount++;
        labels[n] = label;
        parents[n] = parent;
        counters[n] = counter;
        lookup[slot] = n + 1;
        return n;
    }

    @Override
    public void startTick() {
        if (finished) return;
        if (tickStarted) {
            imbalance = true;
            return; // Do not destroy an already active scope on reentry.
        }
        tickStarted = true;
        depth = 0;
        skippedDepth = 0L;
        push(ROOT);
    }

    @Override
    public void endTick() {
        if (finished) return;
        if (!tickStarted) { imbalance = true; return; }
        if (depth != 1 || skippedDepth != 0L) {
            imbalance = true;
            incomplete = true;
        }
        // A gameplay exception can leave pushes open. Close their elapsed spans
        // at the scope boundary and mark incomplete, without throwing or looping
        // over an unbounded overflow depth.
        long end = now();
        if (skippedDepth != 0L) {
            stackChildren[depth - 1] = add(stackChildren[depth - 1], end - skippedStarted);
            skippedDepth = 0L;
        }
        while (depth > 0) closeTop(end);
        tickStarted = false;
        ++scopeTicks;
    }

    @Override
    public void push(String name) {
        if (finished) return;
        if (!tickStarted) { imbalance = true; return; }
        long start = now();
        if (depth == STACK_LIMIT) {
            if (skippedDepth == 0L) skippedStarted = start;
            skippedDepth = add(skippedDepth, 1L);
            droppedScopes = add(droppedScopes, 1L);
            depthOverflow = true;
            incomplete = true;
            return;
        }
        int parent = depth == 0 ? -1 : stackNodes[depth - 1];
        int n = depth > 0 && parent < 0 ? -1 : node(parent, name, false);
        if (n < 0) droppedScopes = add(droppedScopes, 1L);
        stackNodes[depth] = n;
        stackStarts[depth] = start;
        stackChildren[depth] = 0L;
        ++depth;
    }

    private String supplied(Supplier<String> supplier) {
        try { return supplier.get(); }
        catch (Throwable ignored) {
            supplierFailures = add(supplierFailures, 1L);
            incomplete = true;
            return null;
        }
    }

    @Override
    public void push(Supplier<String> name) {
        if (!finished) push(supplied(name));
    }

    private void closeTop(long end) {
        --depth;
        long elapsed = end - stackStarts[depth];
        int n = stackNodes[depth];
        if (n >= 0) {
            inclusive[n] = add(inclusive[n], elapsed);
            exclusive[n] = add(exclusive[n], Math.max(0L, elapsed - stackChildren[depth]));
            counts[n] = add(counts[n], 1L);
            maxima[n] = Math.max(maxima[n], elapsed);
        }
        if (depth > 0) stackChildren[depth - 1] = add(stackChildren[depth - 1], elapsed);
    }

    @Override
    public void pop() {
        if (finished) return;
        if (!tickStarted || depth == 0) { imbalance = true; return; }
        long end = now();
        if (skippedDepth != 0L) {
            --skippedDepth;
            if (skippedDepth == 0L) stackChildren[depth - 1] = add(stackChildren[depth - 1], end - skippedStarted);
        } else closeTop(end);
    }

    @Override
    public void popPush(String name) { pop(); push(name); }

    @Override
    public void popPush(Supplier<String> name) { pop(); push(name); }

    @Override
    public void incrementCounter(String name, int amount) {
        if (finished) return;
        if (!tickStarted || depth == 0) { imbalance = true; return; }
        int parent = stackNodes[depth - 1];
        int n = skippedDepth != 0L || parent < 0 ? -1 : node(parent, name, true);
        if (n < 0) droppedCounters = add(droppedCounters, 1L);
        else {
            values[n] = add(values[n], amount);
            counts[n] = add(counts[n], 1L);
        }
    }

    @Override
    public void incrementCounter(Supplier<String> name, int amount) {
        if (!finished) incrementCounter(supplied(name), amount);
    }

    @Override
    public void markForCharting(MetricCategory category) { }

    public void requestStop(Stop reason) {
        if (requestedStop == null) requestedStop = reason;
    }

    /** Called only after try-with-resources has closed Profiler.Scope. */
    public boolean afterScope(int endedTick, boolean bodyCompleted) {
        if (finished) return true;
        if (!bodyCompleted || tickStarted) {
            incomplete = true;
            requestStop(Stop.FAILURE);
        } else ++completedTicks;
        if (!EaglerServerPerf.isEnabled() || !EaglerIntegratedServerWorker26.isServerMainLoopAlive()) requestStop(Stop.DISABLED);
        if (scopeTicks >= TICK_LIMIT) requestStop(Stop.TICK_CAP);
        if (now() - startedNanos >= WALL_LIMIT_NANOS) requestStop(Stop.WALL_CAP);
        if (requestedStop == null) return false;
        // A preceding combined filler may throw in endTick before our own call.
        if (tickStarted) endTick();
        return finishAtBoundary(endedTick, requestedStop);
    }

    /** Teardown/restart may request a stop, but cannot detach a live filler. */
    public boolean finishAtBoundary(int endedTick, Stop reason) {
        if (finished) return true;
        requestStop(reason);
        if (tickStarted) return false;
        finished = true;
        long endedNanos = now();
        try {
            LOGGER.info("[EagTickProfile] begin id={} startTick={} endTick={} scopeTicks={} completedTicks={} elapsedNanos={} reason={} diagnosticOnly=true clock=elapsed-inclusive-suspensions",
                captureId, startedTick, endedTick, scopeTicks, completedTicks, endedNanos - startedNanos, requestedStop);
            LOGGER.info("[EagTickProfile] status id={} nodes={} depthOverflow={} nodeOverflow={} labelOverflow={} numericOverflow={} clockRegression={} imbalance={} incomplete={} droppedScopes={} droppedCounters={} supplierFailures={} wallOvershootNanos={}",
                captureId, nodeCount, depthOverflow, nodeOverflow, labelOverflow, numericOverflow, clockRegression, imbalance, incomplete,
                droppedScopes, droppedCounters, supplierFailures, Math.max(0L, endedNanos - startedNanos - WALL_LIMIT_NANOS));
            for (int n = 0; n < nodeCount; ++n) {
                LOGGER.info("[EagTickProfile] row id={} node={} parent={} counter={} label=\"{}\" inclusiveNanos={} exclusiveNanos={} count={} maxNanos={} counterValue={}",
                    captureId, n, parents[n], counters[n], printable(labels[n]), inclusive[n], exclusive[n], counts[n], maxima[n], values[n]);
            }
            LOGGER.info("[EagTickProfile] end id={} rows={} scopeClosed=true", captureId, nodeCount);
        } catch (Throwable ignored) {
            // Logging or formatting cannot escape into server execution.
        } finally {
            Arrays.fill(labels, null);
        }
        return true;
    }

    private static String printable(String label) {
        StringBuilder out = new StringBuilder(label.length());
        for (int i = 0; i < label.length(); ++i) {
            char c = label.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32 || c == 127) {
                out.append("\\u00").append("0123456789abcdef".charAt(c >>> 4))
                    .append("0123456789abcdef".charAt(c & 15));
            } else out.append(c);
        }
        return out.toString();
    }
}
