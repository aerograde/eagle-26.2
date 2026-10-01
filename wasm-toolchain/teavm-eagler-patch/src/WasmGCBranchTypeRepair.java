/*
 *  EAGLER wasm-gc codegen fix (patched teavm-core-0.13.1-eagler).
 *
 *  Module-wide type-repair pass, run just before rendering. Inserts a runtime-safe downcast
 *  (ref.cast to the required type) wherever a reference value's STATIC wasm type is a proper
 *  supertype of the type its consumer requires -- which wasm-gc validation rejects (it requires a
 *  subtype). TeaVM's IR is type-correct, so the value genuinely IS an instance of the required
 *  type at that point; the cast only satisfies the verifier.
 *
 *  ORIGINAL motivation (blocker #6): a coalesced register/local whose declared type is a merged
 *  supertype (e.g. java.lang.Object) yielded as a `br` result into a block whose result type is a
 *  specific struct. TeaVM inserts ref.cast at ordinary consumption sites but missed the
 *  block-result-via-branch case (surfaced by the coroutine/async transform's spills).
 *
 *  EXTENDED (blocker #7): the reference-coarsening fix (WasmGCVariableCategoryProvider +
 *  WasmGCMethodGenerator) deliberately widens the many-ref-local mega-methods' object locals to
 *  java.lang.Object so the register allocator can coalesce them (collapsing e.g. Blocks::<clinit>
 *  from 924 typed locals to a handful). Every read of such a widened local that flows into a
 *  type-specific consumer then needs a downcast. WasmGCGenerationVisitor casts the ones it routes
 *  through typed accepts; this pass is the catch-all safety net for the rest: struct.get/set,
 *  array.get/set, call arguments, local/global stores, returns, and branch results.
 *
 *  Non-coarsened functions are unaffected: their operands are already correctly typed, so
 *  isAssignable holds and no cast is inserted (byte-identical output).
 */
package org.teavm.backend.wasm.transformation.gc;

import java.util.List;
import org.teavm.backend.wasm.model.WasmFunction;
import org.teavm.backend.wasm.model.WasmModule;
import org.teavm.backend.wasm.model.WasmStructure;
import org.teavm.backend.wasm.model.WasmType;
import org.teavm.backend.wasm.model.expression.WasmArrayGet;
import org.teavm.backend.wasm.model.expression.WasmArrayNewFixed;
import org.teavm.backend.wasm.model.expression.WasmArraySet;
import org.teavm.backend.wasm.model.expression.WasmBlock;
import org.teavm.backend.wasm.model.expression.WasmBranch;
import org.teavm.backend.wasm.model.expression.WasmBreak;
import org.teavm.backend.wasm.model.expression.WasmCall;
import org.teavm.backend.wasm.model.expression.WasmCast;
import org.teavm.backend.wasm.model.expression.WasmDefaultExpressionVisitor;
import org.teavm.backend.wasm.model.expression.WasmExpression;
import org.teavm.backend.wasm.model.expression.WasmPop;
import org.teavm.backend.wasm.model.expression.WasmReturn;
import org.teavm.backend.wasm.model.expression.WasmSetGlobal;
import org.teavm.backend.wasm.model.expression.WasmSetLocal;
import org.teavm.backend.wasm.model.expression.WasmStructGet;
import org.teavm.backend.wasm.model.expression.WasmStructNew;
import org.teavm.backend.wasm.model.expression.WasmStructSet;
import org.teavm.backend.wasm.model.expression.WasmThrow;
import org.teavm.backend.wasm.render.WasmTypeInference;

public final class WasmGCBranchTypeRepair extends WasmDefaultExpressionVisitor {
    // EAGLER wasm-gc fix (BLOCKER #12): the EXTENDED consumer repairs (struct.get/set, array.get/set,
    // call args, struct.new, array.new_fixed, throw, local/global set, return) are now MODULE-WIDE, not
    // coarsened-only. Originally they were gated to the ref-coarsening (#7) functions on the assumption
    // that NON-coarsened functions were type-correct in stock -- but that is FALSE: a PHI/merge widened
    // to java.lang.Object (blocker-#6 class -- PreciseTypeInference.merge LUBs two structs to Object) can
    // reach a type-specific consumer in a NON-coarsened method too (V8: `struct.set[1] expected
    // (ref null 19326) found (ref null 11=Object)` in fn #18825, only 4 Object locals -> not coarsened),
    // and the generation-time forceType narrowing is ALSO gated on coarsenedMethod, so neither path
    // narrows it. Running the repairs module-wide is safe + byte-identical for type-correct code: repair()
    // ONLY inserts a ref.cast when the operand's actual type is a proper struct SUPERtype of the expected
    // type (the genuine widened case, which stock wasm-gc validation rejects); for correct operands it is
    // a no-op. TeaVM's IR is type-correct so the value really IS a subtype -> the cast never traps.
    // markCoarsened/COARSENED are retained (harmless) but no longer gate the repairs.
    private static final java.util.Set<WasmFunction> COARSENED =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public static void markCoarsened(WasmFunction function) {
        COARSENED.add(function);
    }

    private final WasmTypeInference typeInference = new WasmTypeInference();
    private WasmType currentReturnType;
    private boolean extended;

    private WasmGCBranchTypeRepair() {
    }

    public static void apply(WasmModule module) {
        var repair = new WasmGCBranchTypeRepair();
        for (WasmFunction function : module.functions) {
            repair.currentReturnType = function.getType() != null ? function.getType().getSingleReturnType() : null;
            // BLOCKER #12: module-wide (was `COARSENED.contains(function)`) -- see class comment.
            repair.extended = true;
            for (WasmExpression part : function.getBody()) {
                part.acceptVisitor(repair);
            }
        }
        COARSENED.clear();
    }

    @Override
    public void visit(WasmBlock expression) {
        // Case (2): the coroutine transform emits values on the stack followed by a BARE
        // break (getResult()==null), e.g. [local.get 7, br $label_67]. The break value(s) are
        // then the preceding sibling expression(s), not the break's result field.
        //
        // BLOCKER #14 (wasm-gc-only): a bare break can target a MULTI-VALUE block. The coroutine/async
        // transform spills N live locals as N sibling value-pushes immediately before a bare break to an
        // N-result block (the block's result types are the live-local types in order). If one of those
        // live locals was widened to java.lang.Object (blocker-#6 merge/#7 coarsening), the corresponding
        // sibling pushes an Object where the block's result type is a specific struct (e.g. a 4-result
        // coroutine block whose result[3] is java.lang.Throwable, restored from an Object local -> V8
        // `type error in branch[3] (expected ref null Throwable, got ref null Object)`). Stock repair
        // only handled SINGLE-result blocks (repairToBlock bailed on size!=1), so multi-value spills were
        // missed. Here we align the N preceding siblings to the N result types and repair each. repair()
        // is guarded (only casts when the operand's static type is a proper struct SUPERtype of the
        // result type), so byte-identical when no widening occurred; and the N-siblings-align-to-N-results
        // invariant is exactly how the transform lays out a bare multi-value break.
        List<WasmExpression> body = expression.getBody();
        for (var i = 0; i < body.size(); i++) {
            if (!(body.get(i) instanceof WasmBreak)) {
                continue;
            }
            var br = (WasmBreak) body.get(i);
            if (br.getResult() != null || br.getTarget() == null || br.getTarget().isLoop()) {
                continue;
            }
            var outputs = br.getTarget().getResultTypes();
            var n = outputs.size();
            if (n == 0) {
                continue;
            }
            // Collect the N value-producing siblings that feed the break, walking backward. A body
            // entry is a complete expression tree that pushes its result arity and consumes its own
            // inputs internally (WasmSetLocal/WasmSetGlobal/void-call/drop push 0 and pop 0 from the
            // sibling stack -- their inputs are their own children), so the N carried values are the
            // last N siblings with EXACTLY 1 output; 0-output siblings are skipped, and a >1-output
            // sibling (rare) makes alignment ambiguous -> stop. This precise alignment is required:
            // a blind last-N-siblings scan would map a widened value to the wrong result slot and
            // the ref.cast would trap at runtime.
            //
            // BLOCKER #25 (the worldgen "illegal cast" trap, MonsterRoomFeature::place): the arity
            // accounting above counts sibling OUTPUTS but not stack CONSUMPTION. The coroutine
            // transform stack-THREADS values between siblings via WasmPop leaves (e.g. the original
            // `WasmCast(arrayGet(MOBS, nextInt()), EntityType)` becomes siblings [MOBS-push,
            // <suspend machinery>, WasmArrayGet(Pop,Pop), WasmCast(Pop, EntityType), ...]); a
            // pop-containing sibling consumes earlier siblings' pushes INVISIBLY, so every sibling
            // before it shifts one slot and the old full-walk mapped e.g. the array.get (Object
            // element) to the RECEIVER slot (SpawnerBlockEntity) -> inserted a ref.cast that ALWAYS
            // traps. FIX: the walk STOPS at the first sibling whose subtree contains a WasmPop and
            // repairs ONLY the suffix collected so far -- those are anchored at the break itself,
            // after the last consumer, so their last-k alignment is exact. The genuine blocker-#14
            // spill sites (plain local.get pushes before a bare br) contain no pops -> still get the
            // full repair. Values below the stop point were pushed with their own generation-time
            // casts and validate as-is.
            var slotIndex = new int[n];
            var need = n;
            var j = i - 1;
            while (need > 0 && j >= 0) {
                var e = body.get(j);
                if (containsPop(e)) {
                    break;
                }
                e.acceptVisitor(typeInference);
                var arity = typeInference.getResult().size();
                if (arity == 0) {
                    j--;
                    continue;
                }
                if (arity != 1) {
                    break;
                }
                slotIndex[need - 1] = j;
                need--;
                j--;
            }
            for (var k = need; k < n; k++) {
                var idx = slotIndex[k];
                var operand = body.get(idx);
                var fixed = repair(operand, outputs.get(k));
                if (fixed != operand) {
                    body.set(idx, fixed);
                }
            }
        }
        super.visit(expression);
    }

    @Override
    public void visit(WasmBreak expression) {
        var fixed = repairToBlock(expression.getTarget(), expression.getResult());
        if (fixed != null) {
            expression.setResult(fixed);
        }
        super.visit(expression);
    }

    @Override
    public void visit(WasmBranch expression) {
        var fixed = repairToBlock(expression.getTarget(), expression.getResult());
        if (fixed != null) {
            expression.setResult(fixed);
        }
        super.visit(expression);
    }

    @Override
    public void visit(WasmStructGet expression) {
        if (extended) {
            expression.setInstance(repair(expression.getInstance(), expression.getType().getReference()));
        }
        super.visit(expression);
    }

    @Override
    public void visit(WasmStructSet expression) {
        if (extended) {
            expression.setInstance(repair(expression.getInstance(), expression.getType().getReference()));
            var fieldIndex = expression.getFieldIndex();
            var fields = expression.getType().getFields();
            if (fieldIndex >= 0 && fieldIndex < fields.size()) {
                expression.setValue(repair(expression.getValue(), fields.get(fieldIndex).getUnpackedType()));
            }
        }
        super.visit(expression);
    }

    @Override
    public void visit(WasmArrayGet expression) {
        if (extended) {
            expression.setInstance(repair(expression.getInstance(), expression.getType().getReference()));
        }
        super.visit(expression);
    }

    @Override
    public void visit(WasmArraySet expression) {
        if (extended) {
            expression.setInstance(repair(expression.getInstance(), expression.getType().getReference()));
            expression.setValue(repair(expression.getValue(),
                    expression.getType().getElementType().asUnpackedType()));
        }
        super.visit(expression);
    }

    // EAGLER wasm-gc fix (BLOCKER #24): drop a provably-impossible INNER cast in a double ref.cast.
    // The #7 coarsening + coroutine spill/restore narrowing can emit two consecutive ref.casts on ONE
    // value to UNRELATED struct types, e.g. in MonsterRoomFeature.place:
    //     array.get(MOBS[i]) -> ref.cast SpawnerBlockEntity -> ref.cast EntityType
    // (the `Util.getRandom(MOBS,...)` element -- an EntityType -- gets conflated with the neighbouring
    // `instanceof SpawnerBlockEntity spawner` local). A value can be at most ONE of two unrelated
    // structs, so `ref.cast A; ref.cast B` with A,B incompatible ALWAYS traps (illegal cast) regardless
    // of the value -- it is dead/miscompiled by construction. Splice out the inner cast, keeping the
    // outer one (the real consumer narrowing). Module-wide + unconditional: an incompatible double cast
    // is never valid, so removing it is byte-identical for correct code (which never has one) and only
    // repairs the miscompiled case. Static type of the result is unchanged (still B), and the inner
    // operand shares B's Object-rooted hierarchy, so the spliced `ref.cast B` stays wasm-valid.
    @Override
    public void visit(WasmCast expression) {
        WasmExpression value = expression.getValue();
        while (value instanceof WasmCast) {
            WasmCast inner = (WasmCast) value;
            if (!incompatibleStructs(inner.getTargetType(), expression.getTargetType())) {
                break;
            }
            expression.setValue(inner.getValue());
            value = inner.getValue();
        }
        super.visit(expression);
    }

    // BLOCKER #25: WasmPop-detector -- a subtree containing a pop consumes values from the sibling
    // stack, which invalidates the output-only alignment in visit(WasmBlock). Conservative on
    // purpose (pops nested under an inner block don't reach the sibling stack, but stopping there
    // too only skips repairs, never mis-casts).
    private static boolean containsPop(WasmExpression expression) {
        var detector = new PopDetector();
        expression.acceptVisitor(detector);
        return detector.found;
    }

    private static final class PopDetector extends WasmDefaultExpressionVisitor {
        boolean found;

        @Override
        public void visit(WasmPop expression) {
            found = true;
        }
    }

    private static boolean incompatibleStructs(WasmType.Reference a, WasmType.Reference b) {
        if (!(a instanceof WasmType.CompositeReference) || !(b instanceof WasmType.CompositeReference)) {
            return false;
        }
        var ca = ((WasmType.CompositeReference) a).composite;
        var cb = ((WasmType.CompositeReference) b).composite;
        if (!(ca instanceof WasmStructure) || !(cb instanceof WasmStructure)) {
            return false;
        }
        var sa = (WasmStructure) ca;
        var sb = (WasmStructure) cb;
        if (sa == sb) {
            return false;
        }
        // incompatible == neither struct is a supertype of the other (a value cannot be both).
        return !sa.isSupertypeOf(sb) && !sb.isSupertypeOf(sa);
    }

    // EAGLER wasm-gc fix (BLOCKER #11): DIRECT-call argument downcast. A coarsened function reads a
    // widened Object local as a `call` argument where the callee expects a specific struct; the #7
    // generation-time forceType narrowing misses some direct-call shapes (surfaced as V8
    // `CompileError: call[0] expected (ref null 308) found (ref null 11=Object)`), so repair it here.
    // SAFE for DIRECT WasmCall only: unlike WasmCallReference (call_ref), a direct call has NO funcref
    // on the operand stack, so the coroutine transform's funcref+arg reordering (the reason call-arg
    // repair was originally removed) cannot mis-target the funcref. For a SUSPENDING direct call the
    // transform replaces each arg with a WasmPop (values pushed as siblings) -- wrapping the
    // getArguments()[i] node (normal expr OR WasmPop) in a ref.cast still narrows the value at its
    // consumption slot, so both suspending and non-suspending direct calls are handled. repair() only
    // acts when the arg's actual type is a proper struct SUPERtype of the expected param (i.e. the
    // widened case), so already-narrowed args are byte-identical. call_ref (virtual/interface) args +
    // receiver are narrowed at GENERATION (acceptWithType->forceType + WasmGCVirtualCallGenerator's
    // instance cast), which the audit confirmed covers them; they are intentionally left to that path.
    @Override
    public void visit(WasmCall expression) {
        if (extended && expression.getFunction() != null && expression.getFunction().getType() != null) {
            var paramTypes = expression.getFunction().getType().getParameterTypes();
            var args = expression.getArguments();
            for (var i = 0; i < args.size() && i < paramTypes.size(); i++) {
                args.set(i, repair(args.get(i), paramTypes.get(i)));
            }
        }
        super.visit(expression);
    }

    // EAGLER wasm-gc fix (BLOCKER #11 audit): struct.new initializers must match the field types; a
    // coarsened method can pass a widened Object where a specific-struct field is expected.
    @Override
    public void visit(WasmStructNew expression) {
        if (extended) {
            var fields = expression.getType().getFields();
            var inits = expression.getInitializers();
            for (var i = 0; i < inits.size() && i < fields.size(); i++) {
                inits.set(i, repair(inits.get(i), fields.get(i).getUnpackedType()));
            }
        }
        super.visit(expression);
    }

    // EAGLER wasm-gc fix (BLOCKER #11 audit): array.new_fixed elements must match the element type.
    @Override
    public void visit(WasmArrayNewFixed expression) {
        if (extended) {
            var elemType = expression.getType().getElementType().asUnpackedType();
            var elems = expression.getElements();
            for (var i = 0; i < elems.size(); i++) {
                elems.set(i, repair(elems.get(i), elemType));
            }
        }
        super.visit(expression);
    }

    // EAGLER wasm-gc fix (BLOCKER #11 audit): throw arguments must match the exception tag's types.
    // (Generation already casts the thrown value to Throwable; this is a post-transform safety net.)
    @Override
    public void visit(WasmThrow expression) {
        if (extended && expression.getTag() != null && expression.getTag().getType() != null) {
            var paramTypes = expression.getTag().getType().getParameterTypes();
            var args = expression.getArguments();
            for (var i = 0; i < args.size() && i < paramTypes.size(); i++) {
                args.set(i, repair(args.get(i), paramTypes.get(i)));
            }
        }
        super.visit(expression);
    }

    @Override
    public void visit(WasmSetLocal expression) {
        if (extended) {
            expression.setValue(repair(expression.getValue(), expression.getLocal().getType()));
        }
        super.visit(expression);
    }

    @Override
    public void visit(WasmSetGlobal expression) {
        if (extended) {
            expression.setValue(repair(expression.getValue(), expression.getGlobal().getType()));
        }
        super.visit(expression);
    }

    @Override
    public void visit(WasmReturn expression) {
        if (extended && currentReturnType != null) {
            expression.setValue(repair(expression.getValue(), currentReturnType));
        }
        super.visit(expression);
    }

    private WasmExpression repairToBlock(WasmBlock target, WasmExpression result) {
        if (target == null || result == null || target.isLoop()) {
            return null;
        }
        var outputs = target.getResultTypes();
        if (outputs.size() != 1) {
            return null;
        }
        var fixed = repair(result, outputs.get(0));
        return fixed == result ? null : fixed;
    }

    /**
     * Returns {@code operand} wrapped in a ref.cast to {@code expected} when both are STRUCT
     * references and {@code operand}'s static type is a proper SUPERTYPE of {@code expected} (the
     * widening the ref-coarsening fix introduces, which a struct.get/call/store consumer rejects);
     * otherwise returns {@code operand} unchanged.
     *
     * The repair is confined to the struct hierarchy on purpose: the coarsening only widens object
     * locals to the java.lang.Object STRUCT, so only struct->struct narrowing is ever needed. Never
     * touch func/array/i31/extern references or unrelated structs -- casting across reference
     * hierarchies is itself a validation error, and casting between unrelated structs would trap.
     */
    private WasmExpression repair(WasmExpression operand, WasmType expected) {
        if (operand == null || !(expected instanceof WasmType.CompositeReference)) {
            return operand;
        }
        var expectedComposite = ((WasmType.CompositeReference) expected).composite;
        if (!(expectedComposite instanceof WasmStructure)) {
            return operand;
        }
        operand.acceptVisitor(typeInference);
        if (typeInference.getResult().size() != 1) {
            return operand;
        }
        var actual = typeInference.getSingleResult();
        if (actual == null || actual == expected || !(actual instanceof WasmType.CompositeReference)) {
            return operand;
        }
        var actualComposite = ((WasmType.CompositeReference) actual).composite;
        if (!(actualComposite instanceof WasmStructure)) {
            return operand;
        }
        var expectedStruct = (WasmStructure) expectedComposite;
        var actualStruct = (WasmStructure) actualComposite;
        // already a subtype (or same) -> valid, no cast. Only narrow when actual is a proper
        // supertype of expected; leave unrelated structs alone (they would trap).
        if (expectedStruct.isSupertypeOf(actualStruct) || !actualStruct.isSupertypeOf(expectedStruct)) {
            return operand;
        }
        return new WasmCast(operand, (WasmType.Reference) expected);
    }
}
