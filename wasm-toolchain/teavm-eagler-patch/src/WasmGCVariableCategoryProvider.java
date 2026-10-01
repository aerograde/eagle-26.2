/*
 *  Copyright 2024 Alexey Andreev.
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
package org.teavm.backend.wasm.gc;

import org.teavm.backend.wasm.generate.gc.classes.WasmGCTypeMapper;
import org.teavm.backend.wasm.model.WasmStructure;
import org.teavm.backend.wasm.model.WasmType;
import org.teavm.model.ClassHierarchy;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.util.VariableCategoryProvider;

public class WasmGCVariableCategoryProvider implements VariableCategoryProvider {
    // EAGLER wasm-gc fix (BLOCKER #7): threshold-gated reference-category coarsening.
    //
    // Stock TeaVM sets each variable's register-coalescing category to its PRECISE type. The
    // RegisterAllocator only coalesces variables that share a category (and have disjoint live
    // ranges). Mojang mega-initializers (Blocks::<clinit> registers 900+ DISTINCT block subtypes)
    // therefore get 900+ distinct categories -> zero coalescing -> 900+ typed wasm locals, which
    // V8's TurboFan optimizer OOMs its compilation Zone on.
    //
    // When the number of coarsenable reference variables in a program exceeds COARSEN_THRESHOLD,
    // assign ALL coarsenable references a SINGLE shared category so disjoint ref temps coalesce.
    // Below the threshold, categories are byte-identical to stock (every normal method is
    // unchanged; only the handful of mega-methods coarsen). The coalesced local's declared type is
    // then widened to the per-register LUB in WasmGCMethodGenerator, and precise reads are wrapped
    // in ref.cast by WasmGCGenerationVisitor (coarsened-method gate).
    private static final int COARSEN_THRESHOLD = 64;
    private static final Object REFERENCE_CATEGORY = new Object();

    private ClassHierarchy hierarchy;
    private WasmGCTypeMapper typeMapper;
    private boolean compactMode;
    private boolean coarsened;

    public WasmGCVariableCategoryProvider(ClassHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    public void setCompactMode(boolean compactMode) {
        this.compactMode = compactMode;
    }

    public void setTypeMapper(WasmGCTypeMapper typeMapper) {
        this.typeMapper = typeMapper;
    }

    // Whether the last getCategories() call coarsened reference categories (i.e. this program is a
    // mega-method). WasmGCMethodGenerator reads this after register allocation to decide whether to
    // widen coalesced ref locals to their per-register LUB and emit read casts.
    public boolean wasCoarsened() {
        return coarsened;
    }

    @Override
    public Object[] getCategories(Program program, MethodReference method) {
        var inference = new PreciseTypeInference(program, method, hierarchy);
        inference.setPhisSkipped(false);
        var n = program.variableCount();
        var types = new PreciseValueType[n];
        var referenceCount = 0;
        for (int i = 0; i < n; ++i) {
            var type = inference.typeOf(program.variableAt(i));
            types[i] = type;
            if (isCoarsenable(type)) {
                referenceCount++;
            }
        }
        coarsened = referenceCount > COARSEN_THRESHOLD;

        // Variables 0..parameterCount() are the receiver/parameter slots; RegisterAllocator
        // pre-colors them to their own register and their wasm local type is fixed by the function
        // signature (cannot be widened). Keep them PRECISE so coarsened non-parameter refs never
        // coalesce into a parameter register (which would store an Object value into a
        // specific-typed parameter local).
        var lastParam = method.parameterCount();
        var result = new Object[n];
        for (int i = 0; i < n; ++i) {
            var type = types[i];
            if (type == null) {
                result[i] = new Object();
            } else if (coarsened && i > lastParam && isCoarsenable(type)) {
                result[i] = REFERENCE_CATEGORY;
            } else {
                result[i] = type;
            }
        }
        if (compactMode) {
            result[0] = ValueType.object(method.getClassName());
        }
        return result;
    }

    // Only coalesce plain (non-array-unwrapped) object references WHOSE WASM MAPPING IS A STRUCT.
    // Arrays, array-unwrap natives and primitives keep precise categories. Crucially some Java
    // object/interface types map to a wasm ARRAY, not a struct (e.g. TeaVM's
    // org.teavm.platform.metadata.ResourceMap via a custom type mapper); those must NOT be coarsened,
    // because the coalesced local is widened to the java.lang.Object STRUCT and an array is not a
    // wasm-subtype of it (invalid local.set / store).
    private boolean isCoarsenable(PreciseValueType type) {
        if (type == null || type.isArrayUnwrap || !(type.valueType instanceof ValueType.Object)) {
            return false;
        }
        if (typeMapper == null) {
            return true;
        }
        var wasmType = typeMapper.mapType(type.valueType);
        return wasmType instanceof WasmType.CompositeReference
                && ((WasmType.CompositeReference) wasmType).composite instanceof WasmStructure;
    }
}
