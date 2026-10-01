/*
 *  EAGLER wasm-gc codegen fix (patched teavm-core-0.13.1-eagler) — BLOCKER #7.
 *
 *  TeaVM's wasm-gc backend emits two giant single functions on a Bootstrap-scale graph:
 *    teavm@initializer     (~1.16 MB) — the module start function, runs every class/string
 *                                       initializer contributor inline.
 *    @teavm.initReflection (~1.30 MB) — all reflection (@teavm.Field/@teavm.Method) metadata
 *                                       populated inline.
 *  V8 (both node AND Chrome's renderer — they share V8's Zone arena) blows its per-function
 *  compilation Zone lazily compiling one of these when Bootstrap.bootStrap() first calls it →
 *  "Fatal process out of memory: Zone" / renderer crash. This is a hard V8 cap unaffected by
 *  --max-old-space-size or --liftoff. The wasm itself is VALID; only the function size is fatal.
 *
 *  FIX: a module-wide pass (run just before rendering) that splits any oversized function whose
 *  top-level statements are self-contained into N smaller sub-functions called in sequence.
 *
 *  SAFETY / applicability: a WasmLocal is permanently owned by one WasmFunction (WasmFunction.add
 *  rejects re-parenting, getLocalVariables() is unmodifiable), so statements referencing function
 *  locals cannot be moved. We therefore only split VOID no-arg functions that have NO locals — the
 *  shape of the two init functions (their statements are struct.set on globals, array writes, and
 *  calls; no function locals, no cross-statement stack values). Ordinary methods (params/results or
 *  locals — e.g. Blocks::<clinit>) are left untouched, so ordering/semantics are preserved: the
 *  sub-functions are called in the exact original statement order.
 */
package org.teavm.backend.wasm.transformation.gc;

import java.util.ArrayList;
import org.teavm.backend.wasm.model.WasmFunction;
import org.teavm.backend.wasm.model.WasmModule;
import org.teavm.backend.wasm.model.expression.WasmCall;
import org.teavm.backend.wasm.model.expression.WasmDefaultExpressionVisitor;
import org.teavm.backend.wasm.model.expression.WasmExpression;

public final class WasmGCInitFunctionSplitter {
    // Only consider functions bigger than this (estimated AST node count).
    private static final int MIN_NODES = 20_000;
    // Target sub-function size (estimated AST node count). Conservative so each sub-function
    // stays well under V8's Zone cap; tune from the post-build function-size probe.
    private static final int NODE_BUDGET = 10_000;

    private WasmGCInitFunctionSplitter() {
    }

    public static void apply(WasmModule module) {
        var snapshot = new ArrayList<WasmFunction>();
        for (var f : module.functions) {
            snapshot.add(f);
        }
        for (var f : snapshot) {
            trySplit(module, f);
        }
    }

    private static void trySplit(WasmModule module, WasmFunction fn) {
        if (fn.getImportName() != null) {
            return;
        }
        if (!fn.getType().getParameterTypes().isEmpty() || !fn.getType().getReturnTypes().isEmpty()) {
            return;
        }
        var body = fn.getBody();
        var n = body.size();
        if (n < 4) {
            return;
        }

        // Estimate per-statement node counts + total.
        var sizes = new int[n];
        var total = 0L;
        for (var i = 0; i < n; i++) {
            var counter = new NodeCounter();
            counter.visitDefault(body.get(i));
            sizes[i] = counter.count;
            total += counter.count;
        }
        if (total < MIN_NODES) {
            return;
        }

        var hasLocals = !fn.getLocalVariables().isEmpty();
        if (hasLocals) {
            // Cannot move locals across functions; leave it (would need a source-level split).
            System.out.println("EAGLER_SPLIT: SKIP " + fn.getName() + " nodes=" + total
                    + " stmts=" + n + " (has " + fn.getLocalVariables().size() + " locals)");
            return;
        }

        // Chunk contiguous statements by the node budget.
        var chunks = new ArrayList<int[]>(); // [start, endExclusive]
        var start = 0;
        var acc = 0L;
        for (var i = 0; i < n; i++) {
            acc += sizes[i];
            if (acc >= NODE_BUDGET && i + 1 < n) {
                chunks.add(new int[] { start, i + 1 });
                start = i + 1;
                acc = 0;
            }
        }
        chunks.add(new int[] { start, n });
        if (chunks.size() < 2) {
            return;
        }

        var calls = new ArrayList<WasmExpression>(chunks.size());
        var k = 0;
        for (var range : chunks) {
            var sub = new WasmFunction(fn.getType());
            sub.setName(fn.getName() + "@part" + k);
            for (var i = range[0]; i < range[1]; i++) {
                sub.getBody().add(body.get(i));
            }
            module.functions.add(sub);
            calls.add(new WasmCall(sub));
            k++;
        }
        body.clear();
        body.addAll(calls);
        System.out.println("EAGLER_SPLIT: SPLIT " + fn.getName() + " nodes=" + total
                + " stmts=" + n + " -> " + chunks.size() + " sub-functions");
    }

    private static final class NodeCounter extends WasmDefaultExpressionVisitor {
        int count;

        @Override
        public void visitDefault(WasmExpression expression) {
            count++;
            super.visitDefault(expression);
        }
    }
}
