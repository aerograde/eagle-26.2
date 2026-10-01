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
package org.teavm.backend.wasm.generate.gc.methods;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.teavm.ast.ArrayFromDataExpr;
import org.teavm.ast.ArrayType;
import org.teavm.ast.BinaryExpr;
import org.teavm.ast.CastExpr;
import org.teavm.ast.ConditionalExpr;
import org.teavm.ast.ConstantExpr;
import org.teavm.ast.Expr;
import org.teavm.ast.InstanceOfExpr;
import org.teavm.ast.InvocationExpr;
import org.teavm.ast.NewArrayExpr;
import org.teavm.ast.QualificationExpr;
import org.teavm.ast.SubscriptExpr;
import org.teavm.ast.TryCatchStatement;
import org.teavm.ast.VariableExpr;
import org.teavm.backend.wasm.BaseWasmFunctionRepository;
import org.teavm.backend.wasm.WasmFunctionTypes;
import org.teavm.backend.wasm.gc.PreciseTypeInference;
import org.teavm.backend.wasm.gc.vtable.WasmGCVirtualTableProvider;
import org.teavm.backend.wasm.generate.ExpressionCache;
import org.teavm.backend.wasm.generate.TemporaryVariablePool;
import org.teavm.backend.wasm.generate.common.methods.BaseWasmGenerationVisitor;
import org.teavm.backend.wasm.generate.gc.WasmGCNameProvider;
import org.teavm.backend.wasm.generate.gc.classes.WasmGCClassInfoProvider;
import org.teavm.backend.wasm.generate.gc.classes.WasmGCTypeMapper;
import org.teavm.backend.wasm.generate.gc.strings.WasmGCStringProvider;
import org.teavm.backend.wasm.intrinsics.gc.WasmGCIntrinsicContext;
import org.teavm.backend.wasm.model.WasmArray;
import org.teavm.backend.wasm.model.WasmFunction;
import org.teavm.backend.wasm.model.WasmLocal;
import org.teavm.backend.wasm.model.WasmModule;
import org.teavm.backend.wasm.model.WasmStructure;
import org.teavm.backend.wasm.model.WasmTag;
import org.teavm.backend.wasm.model.WasmType;
import org.teavm.backend.wasm.model.expression.WasmArrayGet;
import org.teavm.backend.wasm.model.expression.WasmArrayLength;
import org.teavm.backend.wasm.model.expression.WasmArraySet;
import org.teavm.backend.wasm.model.expression.WasmBlock;
import org.teavm.backend.wasm.model.expression.WasmBranch;
import org.teavm.backend.wasm.model.expression.WasmCall;
import org.teavm.backend.wasm.model.expression.WasmCallReference;
import org.teavm.backend.wasm.model.expression.WasmCast;
import org.teavm.backend.wasm.model.expression.WasmCastBranch;
import org.teavm.backend.wasm.model.expression.WasmCastCondition;
import org.teavm.backend.wasm.model.expression.WasmDrop;
import org.teavm.backend.wasm.model.expression.WasmExpression;
import org.teavm.backend.wasm.model.expression.WasmGetGlobal;
import org.teavm.backend.wasm.model.expression.WasmGetLocal;
import org.teavm.backend.wasm.model.expression.WasmInt32Constant;
import org.teavm.backend.wasm.model.expression.WasmInt32Subtype;
import org.teavm.backend.wasm.model.expression.WasmInt64Subtype;
import org.teavm.backend.wasm.model.expression.WasmIntBinary;
import org.teavm.backend.wasm.model.expression.WasmIntBinaryOperation;
import org.teavm.backend.wasm.model.expression.WasmIntType;
import org.teavm.backend.wasm.model.expression.WasmIntUnary;
import org.teavm.backend.wasm.model.expression.WasmIntUnaryOperation;
import org.teavm.backend.wasm.model.expression.WasmIsNull;
import org.teavm.backend.wasm.model.expression.WasmLoadFloat32;
import org.teavm.backend.wasm.model.expression.WasmLoadFloat64;
import org.teavm.backend.wasm.model.expression.WasmLoadInt32;
import org.teavm.backend.wasm.model.expression.WasmLoadInt64;
import org.teavm.backend.wasm.model.expression.WasmNullBranch;
import org.teavm.backend.wasm.model.expression.WasmNullCondition;
import org.teavm.backend.wasm.model.expression.WasmNullConstant;
import org.teavm.backend.wasm.model.expression.WasmPop;
import org.teavm.backend.wasm.model.expression.WasmReferencesEqual;
import org.teavm.backend.wasm.model.expression.WasmSetGlobal;
import org.teavm.backend.wasm.model.expression.WasmSetLocal;
import org.teavm.backend.wasm.model.expression.WasmSignedType;
import org.teavm.backend.wasm.model.expression.WasmStoreFloat32;
import org.teavm.backend.wasm.model.expression.WasmStoreFloat64;
import org.teavm.backend.wasm.model.expression.WasmStoreInt32;
import org.teavm.backend.wasm.model.expression.WasmStoreInt64;
import org.teavm.backend.wasm.model.expression.WasmStructGet;
import org.teavm.backend.wasm.model.expression.WasmStructNewDefault;
import org.teavm.backend.wasm.model.expression.WasmStructSet;
import org.teavm.backend.wasm.model.expression.WasmTest;
import org.teavm.backend.wasm.model.expression.WasmThrow;
import org.teavm.backend.wasm.model.expression.WasmUnreachable;
import org.teavm.backend.wasm.runtime.StringInternPool;
import org.teavm.dependency.DependencyInfo;
import org.teavm.diagnostics.Diagnostics;
import org.teavm.model.ClassHierarchy;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldReference;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReference;
import org.teavm.model.TextLocation;
import org.teavm.model.ValueType;
import org.teavm.model.analysis.ClassInitializerInfo;
import org.teavm.parsing.resource.ResourceProvider;

public class WasmGCGenerationVisitor extends BaseWasmGenerationVisitor {
    private static final MethodDescriptor CLINIT = new MethodDescriptor("<clinit>", ValueType.VOID);

    private WasmGCGenerationContext context;
    private WasmGCGenerationUtil generationUtil;
    private WasmType expectedType;
    private PreciseTypeInference types;
    private WasmGCVirtualCallGenerator virtualCallGenerator;
    private boolean compactMode;
    // EAGLER wasm-gc fix (BLOCKER #7): set when this method's reference locals were coalesced and
    // widened to per-register LUBs. In that case a value read where a more specific reference type
    // is required must be narrowed with ref.cast (forceType) at its consumer site.
    private boolean coarsenedMethod;
    // EAGLER wasm-gc fix (BLOCKER #7): one-shot flag that suppresses the coarsened-read narrowing
    // for the NEXT typed accept only (consumed before sub-expressions are visited, so nested
    // accepts still narrow normally). Used where the accepted value must stay wide because the
    // operation itself relates it to the type: instanceof (ref.test must see the un-narrowed value,
    // else the pre-cast traps exactly when the test would be false) and checkcast (visit(CastExpr)
    // performs the cast / ClassCastException itself).
    private boolean suppressNarrowingOnce;
    private Set<MethodReference> asyncSplitMethods;

    public WasmGCGenerationVisitor(WasmGCGenerationContext context, MethodReference currentMethod,
            WasmFunction function, int firstVariable, boolean async, PreciseTypeInference types,
            Set<MethodReference> asyncSplitMethods) {
        super(context, currentMethod, function, firstVariable, async);
        this.context = context;
        generationUtil = new WasmGCGenerationUtil(context.classInfoProvider());
        virtualCallGenerator = new WasmGCVirtualCallGenerator(context.virtualTables(), context.classInfoProvider());
        this.types = types;
        this.asyncSplitMethods = asyncSplitMethods;
    }

    public void setCompactMode(boolean compactMode) {
        this.compactMode = compactMode;
    }

    public void setCoarsenedMethod(boolean coarsenedMethod) {
        this.coarsenedMethod = coarsenedMethod;
    }

    // EAGLER wasm-gc fix (BLOCKER #7): in a coarsened mega-method, type the one-shot inline
    // construction scratch temp as the java.lang.Object struct so the temporary-variable pool reuses
    // a single local across all the distinct-typed constructions (chiefly the per-item factory
    // lambdas in Blocks/Items/EntityTypes::<clinit>). allocateObject casts the self-reference back to
    // the concrete struct for its field writes, and the caller casts the receiver/result.
    @Override
    protected WasmType constructorTempType(String className, WasmType preciseType) {
        if (coarsenedMethod && preciseType instanceof WasmType.Reference) {
            return context.classInfoProvider().getClassInfo("java.lang.Object").getType();
        }
        return preciseType;
    }

    @Override
    protected void accept(Expr expr) {
        accept(expr, null);
    }

    @Override
    protected void accept(Expr expr, WasmType type) {
        var previousExpectedType = expectedType;
        // Consume the one-shot suppress flag BEFORE visiting sub-expressions, so only this
        // (outermost) accept skips narrowing; nested accepts inside expr narrow normally.
        var suppress = suppressNarrowingOnce;
        suppressNarrowingOnce = false;
        expectedType = type;
        super.accept(expr);
        expectedType = previousExpectedType;
        // EAGLER wasm-gc fix (BLOCKER #7): in a coarsened method, a reference value read from a
        // widened (LUB/Object) local may be a wasm-supertype of the type this consumer site
        // requires (method arg, field/array target, assignment target, return). forceType inserts a
        // ref.cast exactly when the produced value's struct is a supertype of the expected struct
        // (otherwise it is a no-op), so every typed consumer narrows the widened read correctly.
        if (coarsenedMethod && !suppress && type instanceof WasmType.Reference && result != null) {
            result = forceType(result, type);
        }
    }

    @Override
    protected void acceptWithType(Expr expr, ValueType type) {
        accept(expr, mapType(type));
    }

    @Override
    protected boolean isManaged() {
        return true;
    }

    @Override
    protected boolean needsCallSiteId() {
        return false;
    }

    @Override
    protected boolean isManagedCall(MethodReference method) {
        return false;
    }

    @Override
    public void visit(VariableExpr expr) {
        super.visit(expr);
        if (compactMode && expr.getVariableIndex() == 0) {
            var receiverType = context.typeMapper().mapType(ValueType.object(currentMethod.getClassName()));
            var cast = new WasmCast(result, (WasmType.Reference) receiverType);
            cast.setLocation(expr.getLocation());
            result = cast;
        }
    }

    @Override
    protected void generateThrowNPE(TextLocation location, List<WasmExpression> target) {
        generateThrow(new WasmCall(context.npeMethod()), location, target);
    }

    @Override
    protected void generateThrowAIOOBE(TextLocation location, List<WasmExpression> target) {
        generateThrow(new WasmCall(context.aaiobeMethod()), location, target);
    }

    @Override
    protected void generateThrowCCE(TextLocation location, List<WasmExpression> target) {
        generateThrow(new WasmCall(context.cceMethod()), location, target);
    }

    @Override
    protected void generateThrow(WasmExpression expression, TextLocation location, List<WasmExpression> target) {
        // EAGLER wasm-gc fix (BLOCKER #7): the thrown value is read via an untyped accept (no
        // consumer coercion), so in a coarsened method it may be a widened (Object) local. The
        // exception tag requires java.lang.Throwable -- narrow it. forceType is a no-op when the
        // value is already a Throwable subtype (legacy + non-coarsened unaffected).
        if (coarsenedMethod) {
            expression = forceType(expression, mapType(ValueType.object("java.lang.Throwable")));
        }
        var result = new WasmThrow(context.getExceptionTag());
        result.getArguments().add(expression);
        result.setLocation(location);
        target.add(result);
    }

    @Override
    protected WasmExpression unwrapArray(WasmExpression array) {
        array.acceptVisitor(typeInference);
        var arrayType = (WasmType.CompositeReference) typeInference.getSingleResult();
        var arrayStruct = (WasmStructure) arrayType.composite;
        return new WasmStructGet(arrayStruct, array, WasmGCClassInfoProvider.ARRAY_DATA_FIELD_OFFSET);
    }

    @Override
    protected WasmExpression generateArrayLength(WasmExpression array) {
        return new WasmArrayLength(array);
    }

    @Override
    protected WasmExpression storeArrayItem(WasmExpression array, WasmExpression index, Expr value,
            ArrayType type) {
        array.acceptVisitor(typeInference);
        var arrayRefType = (WasmType.CompositeReference) typeInference.getSingleResult();
        var arrayType = (WasmArray) arrayRefType.composite;
        accept(value, arrayType.getElementType().asUnpackedType());
        var wasmValue = result;
        return new WasmArraySet(arrayType, array, index, wasmValue);
    }

    @Override
    protected void storeField(Expr qualified, FieldReference field, Expr value, TextLocation location) {
        WasmExpression expr;
        if (qualified == null) {
            var global = context.classInfoProvider().getStaticFieldLocation(field);
            accept(value, global.getType());
            var wasmValue = result;
            expr = new WasmSetGlobal(global, wasmValue);
        } else {
            acceptWithType(qualified, ValueType.object(field.getClassName()));
            var target = result;
            if (context.classInfoProvider().getClassInfo(field.getClassName()).isHeapStructure()) {
                expr = storeHeapField(target, field, value);
            } else {
                expr = storeNormalField(target, field, value);
            }
        }
        expr.setLocation(location);
        resultConsumer.add(expr);
    }

    private WasmExpression storeNormalField(WasmExpression target, FieldReference field, Expr value) {
        target.acceptVisitor(typeInference);
        var type = (WasmType.CompositeReference) typeInference.getSingleResult();
        var struct = (WasmStructure) type.composite;
        var fieldIndex = context.classInfoProvider().getFieldIndex(field);
        if (fieldIndex >= 0) {
            accept(value, struct.getFields().get(fieldIndex).getUnpackedType());
            return new WasmStructSet(struct, target, fieldIndex, result);
        } else {
            accept(value);
            return result;
        }
    }

    private WasmExpression storeHeapField(WasmExpression target, FieldReference field, Expr value) {
        var cls = context.classes().get(field.getClassName());
        var type = cls.getField(field.getFieldName()).getType();
        var offset = context.classInfoProvider().getHeapFieldOffset(field);
        accept(value, context.typeMapper().mapType(type));
        var wasmValue = result;
        if (type instanceof ValueType.Primitive) {
            switch (((ValueType.Primitive) type).getKind()) {
                case BOOLEAN:
                case BYTE:
                    return new WasmStoreInt32(1, target, wasmValue, WasmInt32Subtype.INT8, offset);
                case CHARACTER:
                    return new WasmStoreInt32(2, target, wasmValue, WasmInt32Subtype.UINT16, offset);
                case SHORT:
                    return new WasmStoreInt32(2, target, wasmValue, WasmInt32Subtype.INT16, offset);
                case INTEGER:
                    return new WasmStoreInt32(4, target, wasmValue, WasmInt32Subtype.INT32, offset);
                case LONG:
                    return new WasmStoreInt64(8, target, wasmValue, WasmInt64Subtype.INT32, offset);
                case FLOAT:
                    return new WasmStoreFloat32(4, target, wasmValue, offset);
                case DOUBLE:
                    return new WasmStoreFloat64(8, target, wasmValue, offset);
            }
        }
        return new WasmStoreInt32(4, target, wasmValue, WasmInt32Subtype.INT32, offset);
    }

    @Override
    protected WasmExpression stringLiteral(String s) {
        var stringConstant = context.strings().getStringConstant(s);
        return new WasmGetGlobal(stringConstant.global);
    }

    @Override
    protected WasmExpression classLiteral(ValueType type) {
        if (type instanceof ValueType.Array) {
            var itemType = ((ValueType.Array) type).getItemType();
            if (!(itemType instanceof ValueType.Primitive)) {
                var degree = 0;
                while (type instanceof ValueType.Array) {
                    type = ((ValueType.Array) type).getItemType();
                    ++degree;
                }
                WasmExpression result = new WasmGetGlobal(context.classInfoProvider().getClassInfo(type).getPointer());
                while (degree-- > 0) {
                    result = new WasmCall(context.classInfoProvider().getGetArrayClassFunction(), result);
                }
                return result;
            }
        }
        var classConstant = context.classInfoProvider().getClassInfo(type);
        return new WasmGetGlobal(classConstant.getPointer());
    }

    @Override
    protected WasmExpression nullLiteral(Expr expr) {
        var type = expectedType;
        if (type == WasmType.INT32) {
            return new WasmInt32Constant(0);
        }
        if (expr.getVariableIndex() >= 0) {
            var javaType = types.typeOf(expr.getVariableIndex());
            if (javaType != null) {
                type = mapType(javaType.valueType);
            }
        }
        return new WasmNullConstant(type instanceof WasmType.Reference
                ? (WasmType.Reference) type
                : context.classInfoProvider().getClassInfo("java.lang.Object").getType());
    }

    @Override
    protected WasmExpression nullLiteral(WasmType type) {
        return new WasmNullConstant((WasmType.Reference) type);
    }

    @Override
    protected WasmExpression genIsNull(WasmExpression value) {
        return new WasmIsNull(value);
    }

    @Override
    protected WasmExpression nullCheck(Expr value, TextLocation location) {
        var block = new WasmBlock(false);
        block.setLocation(location);

        accept(value);
        if (result instanceof WasmUnreachable) {
            return result;
        }
        result.acceptVisitor(typeInference);
        block.setType(typeInference.getSingleResult().asBlock());
        var check = new WasmNullBranch(WasmNullCondition.NOT_NULL, result, block);
        block.getBody().add(check);

        var callSiteId = generateCallSiteId(location);
        callSiteId.generateRegister(block.getBody(), location);
        generateThrowNPE(location, block.getBody());
        callSiteId.generateThrow(block.getBody(), location);

        return block;
    }

    @Override
    protected CallSiteIdentifier generateCallSiteId(TextLocation location) {
        return new SimpleCallSite();
    }

    @Override
    public void visit(BinaryExpr expr) {
        if (expr.getType() == null) {
            switch (expr.getOperation()) {
                case EQUALS: {
                    isReferenceEq(expr);
                    result.setLocation(expr.getLocation());
                    return;
                }
                case NOT_EQUALS:
                    isReferenceEq(expr);
                    result = new WasmIntUnary(WasmIntType.INT32, WasmIntUnaryOperation.EQZ, result);
                    result.setLocation(expr.getLocation());
                    return;
                default:
                    break;
            }
        }
        super.visit(expr);
    }

    private void isReferenceEq(BinaryExpr expr) {
        if (isNull(expr.getFirstOperand())) {
            accept(expr.getSecondOperand());
            result.acceptVisitor(typeInference);
            if (typeInference.getSingleResult() == WasmType.INT32) {
                result = new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.EQ, result,
                        new WasmInt32Constant(0));
            } else {
                result = new WasmIsNull(result);
            }
        } else if (isNull(expr.getSecondOperand())) {
            accept(expr.getFirstOperand());
            result.acceptVisitor(typeInference);
            if (typeInference.getSingleResult() == WasmType.INT32) {
                result = new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.EQ, result,
                        new WasmInt32Constant(0));
            } else {
                result = new WasmIsNull(result);
            }
        } else {
            accept(expr.getFirstOperand());
            var first = result;
            accept(expr.getSecondOperand());
            var second = result;
            first.acceptVisitor(typeInference);
            if (typeInference.getSingleResult() == WasmType.INT32) {
                result = new WasmIntBinary(WasmIntType.INT32, WasmIntBinaryOperation.EQ, first, second);
            } else {
                result = new WasmReferencesEqual(first, second);
            }
        }
    }

    private boolean isNull(Expr expr) {
        if (!(expr instanceof ConstantExpr)) {
            return false;
        }
        return ((ConstantExpr) expr).getValue() == null;
    }

    @Override
    protected WasmExpression generateVirtualCall(WasmLocal instance, MethodReference method,
            List<WasmExpression> arguments) {
        return virtualCallGenerator.generate(method, isAsyncSplit(method), instance,
                arguments.subList(1, arguments.size()));
    }

    @Override
    protected void allocateObject(String className, TextLocation location, WasmLocal local,
            List<WasmExpression> target) {
        var classInfo = context.classInfoProvider().getClassInfo(className);
        var block = new WasmBlock(false);
        block.setType(classInfo.getType().asBlock());
        var targetVar = local;
        if (targetVar == null) {
            targetVar = tempVars.acquire(classInfo.getType());
        }

        var structNew = new WasmSetLocal(targetVar, new WasmStructNewDefault(classInfo.getStructure()));
        structNew.setLocation(location);
        target.add(structNew);

        // EAGLER wasm-gc fix (BLOCKER #7): when the scratch temp was coarsened to a supertype
        // (constructorTempType), narrow it back to the concrete struct for the vtable field write.
        WasmExpression selfRef = new WasmGetLocal(targetVar);
        if (targetVar.getType() != classInfo.getType()) {
            selfRef = new WasmCast(selfRef, (WasmType.Reference) classInfo.getType());
        }
        var initClassField = new WasmStructSet(classInfo.getStructure(), selfRef,
                WasmGCClassInfoProvider.VT_FIELD_OFFSET, new WasmGetGlobal(classInfo.getVirtualTablePointer()));
        initClassField.setLocation(location);
        target.add(initClassField);

        if (local == null) {
            var getLocal = new WasmGetLocal(targetVar);
            getLocal.setLocation(location);
            target.add(getLocal);
            tempVars.release(targetVar);
        }
    }

    @Override
    public void visit(ArrayFromDataExpr expr) {
        var wasmArrayType = (WasmType.CompositeReference) mapType(ValueType.arrayOf(expr.getType()));
        var wasmArrayStruct = (WasmStructure) wasmArrayType.composite;
        var wasmArrayDataType = (WasmType.CompositeReference) wasmArrayStruct.getFields()
                .get(WasmGCClassInfoProvider.ARRAY_DATA_FIELD_OFFSET).getUnpackedType();
        var wasmArray = (WasmArray) wasmArrayDataType.composite;

        var array = generationUtil.allocateArrayWithElements(expr.getType(), () -> {
            var items = new ArrayList<WasmExpression>();
            for (int i = 0; i < expr.getData().size(); ++i) {
                accept(expr.getData().get(i), wasmArray.getElementType().asUnpackedType());
                items.add(result);
            }
            return items;
        });
        array.setLocation(expr.getLocation());

        result = array;
    }

    @Override
    public void visit(NewArrayExpr expr) {
        accept(expr.getLength(), WasmType.INT32);
        var function = context.classInfoProvider().getArrayConstructor(expr.getType());
        var call = new WasmCall(function, classLiteral(expr.getType()), result);
        call.setLocation(expr.getLocation());
        result = call;
    }

    @Override
    protected WasmExpression allocateMultiArray(List<WasmExpression> target, ValueType arrayType,
            Supplier<List<WasmExpression>> dimensions, TextLocation location) {
        var args = new ArrayList<WasmExpression>();
        var dimensionsValue = dimensions.get();
        args.add(classLiteral(((ValueType.Array) arrayType).getItemType()));
        args.addAll(dimensionsValue);
        var function = context.classInfoProvider().getMultiArrayConstructor(dimensionsValue.size());
        var call = new WasmCall(function, args.toArray(new WasmExpression[0]));
        call.setLocation(location);
        return call;
    }

    @Override
    protected WasmExpression generateInstanceOf(WasmExpression expression, ValueType type) {
        context.classInfoProvider().getClassInfo(type);
        var block = new WasmBlock(false);
        block.setType(WasmType.INT32.asBlock());
        var supertypeCall = new WasmCall(context.supertypeFunctions().getIsSupertypeFunction(type));
        var vtRef = new WasmStructGet(
                context.standardClasses().objectClass().getStructure(),
                expression,
                WasmGCClassInfoProvider.VT_FIELD_OFFSET
        );
        var classRef = new WasmStructGet(
                context.standardClasses().objectClass().getVirtualTableStructure(),
                vtRef,
                WasmGCClassInfoProvider.CLASS_FIELD_OFFSET
        );
        var classClass = context.standardClasses().classClass().getType();
        var classRefCached = exprCache.create(classRef, classClass, expression.getLocation(), block.getBody());
        supertypeCall.getArguments().add(classRefCached.expr());
        supertypeCall.getArguments().add(classRefCached.expr());
        classRefCached.release();
        block.getBody().add(supertypeCall);
        return block;
    }

    @Override
    public void visit(InstanceOfExpr expr) {
        var type = expr.getType();
        if (canCastNatively(type)) {
            var wasmType = context.classInfoProvider().getClassInfo(type).getStructure().getNonNullReference();
            // instanceof: the value must NOT be pre-narrowed (see suppressNarrowingOnce) -- ref.test
            // must receive the wide value, else the narrowing ref.cast traps when the test is false.
            suppressNarrowingOnce = true;
            acceptWithType(expr.getExpr(), type);
            var wasmValue = result;
            result.acceptVisitor(typeInference);

            result = new WasmTest(wasmValue, wasmType);
            result.setLocation(expr.getLocation());
        } else {
            super.visit(expr);
        }
    }

    @Override
    public void visit(CastExpr expr) {
        // checkcast: keep the value wide (see suppressNarrowingOnce). This method performs the cast
        // itself (WasmCastBranch -> ClassCastException); a pre-narrowing ref.cast would instead trap
        // with "illegal cast" and, since it makes sourceType == target, skip the checked-cast logic.
        suppressNarrowingOnce = true;
        acceptWithType(expr.getValue(), expr.getTarget());
        if (expr.getTarget() instanceof ValueType.Object) {
            var className = ((ValueType.Object) expr.getTarget()).getClassName();
            if (context.classInfoProvider().getClassInfo(className).isHeapStructure()) {
                accept(expr.getValue(), WasmType.INT32);
                return;
            }
        }

        result.acceptVisitor(typeInference);
        var sourceType = (WasmType.Reference) typeInference.getSingleResult();
        if (sourceType == null) {
            return;
        }

        var targetType = (WasmType.Reference) context.typeMapper().mapType(expr.getTarget());
        WasmStructure targetStruct = null;
        if (targetType instanceof WasmType.CompositeReference) {
            var targetComposite = ((WasmType.CompositeReference) targetType).composite;
            if (targetComposite instanceof WasmStructure) {
                targetStruct = (WasmStructure) targetComposite;
            }
        }

        var canInsertCast = true;
        if (targetStruct != null && sourceType instanceof WasmType.CompositeReference) {
            var sourceComposite = (WasmType.CompositeReference) sourceType;
            if (!sourceType.isNullable()) {
                sourceType = sourceComposite.composite.getReference();
            }
            var sourceStruct = (WasmStructure) sourceComposite.composite;
            if (targetStruct.isSupertypeOf(sourceStruct)) {
                canInsertCast = false;
            } else if (!sourceStruct.isSupertypeOf(targetStruct)) {
                var block = new WasmBlock(false);
                block.setType(targetType.asBlock());
                block.setLocation(expr.getLocation());
                block.getBody().add(new WasmDrop(result));
                block.getBody().add(new WasmNullConstant(targetType));
                result = block;
                return;
            }
        }

        if (!expr.isWeak() && context.isStrict()) {
            result.acceptVisitor(typeInference);

            var block = new WasmBlock(false);
            block.setLocation(expr.getLocation());
            if (canCastNatively(expr.getTarget())) {
                block.setType(targetType.asBlock());
                if (!canInsertCast) {
                    return;
                }
                block.getBody().add(new WasmCastBranch(WasmCastCondition.SUCCESS, result, sourceType,
                        targetType, block));
                result = block;
            } else {
                block.setType(sourceType.asBlock());
                var valueToCheck = exprCache.create(result, sourceType, expr.getLocation(), block.getBody());
                block.getBody().add(new WasmNullConstant(sourceType));
                var nonNullValue = new WasmNullBranch(WasmNullCondition.NULL, valueToCheck.expr(), block);
                block.getBody().add(new WasmDrop(nonNullValue));
                block.getBody().add(new WasmDrop(new WasmPop(sourceType)));

                var supertypeCall = generateInstanceOf(valueToCheck.expr(), expr.getTarget());
                var breakIfPassed = new WasmBranch(supertypeCall, block);
                breakIfPassed.setResult(valueToCheck.expr());
                block.getBody().add(new WasmDrop(breakIfPassed));

                result = block;
                if (canInsertCast) {
                    var cast = new WasmCast(result, targetType);
                    cast.setLocation(expr.getLocation());
                    result = cast;
                }
            }
            generateThrowCCE(expr.getLocation(), block.getBody());
        } else if (canInsertCast) {
            result = new WasmCast(result, targetType);
            result.setLocation(expr.getLocation());
        }
    }

    private boolean canCastNatively(ValueType type) {
        if (type instanceof ValueType.Array) {
            return true;
        }
        var className = ((ValueType.Object) type).getClassName();
        var cls = context.classes().get(className);
        if (cls == null) {
            return false;
        }
        return !cls.hasModifier(ElementModifier.INTERFACE);
    }

    @Override
    protected boolean needsClassInitializer(String className) {
        if (className.equals(StringInternPool.class.getName())) {
            return false;
        }
        return context.classInfoProvider().getClassInfo(className).getInitializerPointer() != null;
    }

    @Override
    protected WasmExpression generateClassInitializer(String className, TextLocation location) {
        var pointer = context.classInfoProvider().getClassInfo(className).getInitializerPointer();
        var result = new WasmCallReference(new WasmGetGlobal(pointer),
                context.functionTypes().of(null));
        result.setSuspensionPoint(isAsyncSplit(new MethodReference(className, CLINIT)));
        result.setLocation(location);
        return result;
    }

    @Override
    protected void checkExceptionType(TryCatchStatement tryCatch, WasmLocal exceptionVar, List<WasmExpression> target,
            WasmBlock targetBlock) {
        var exceptionType = ValueType.object(tryCatch.getExceptionType());
        var wasmType = context.classInfoProvider().getClassInfo(tryCatch.getExceptionType()).getType();
        if (canCastNatively(exceptionType)) {
            // Normal exception: native br_on_cast(Throwable -> catchType) tests + narrows in one op.
            // catchType is a real Throwable subtype, so the cast target is a subtype of the source ->
            // valid. This is the stock wasm-gc path (byte-identical for all real exception types).
            var wasmSourceType = context.classInfoProvider().getClassInfo("java.lang.Throwable").getType();
            var br = new WasmCastBranch(WasmCastCondition.SUCCESS, new WasmGetLocal(exceptionVar),
                    wasmSourceType, wasmType, targetBlock);
            target.add(new WasmDrop(br));
        } else {
            // Blocker #13 (wasm-gc-only): the catch type is a MISSING / never-linked class
            // (context.classes().get(name) == null -> canCastNatively == false, same predicate the
            // non-native checkcast path uses). Its wasm struct is NOT a Throwable subtype, so a native
            // br_on_cast(Throwable -> catchType) is invalid (dst not a subtype of src -> V8
            // CompileError), and widening the br_on_cast SOURCE to Object instead makes its fallthrough
            // value Object-typed, which cascades a branch/coroutine type error. Mirror the non-native
            // checkcast lowering exactly: test membership with the runtime supertype function (returns
            // false for a never-instantiated class -> the catch is provably dead) and, on the
            // never-taken match, ref.cast the Throwable down to the catch type (valid across the shared
            // top `any` hierarchy). No br_on_cast and no Object-typed value -> no type cascade.
            // Semantics preserved: nothing is ever this type, so the handler is never entered.
            System.out.println("EAGLER_WASMGC_PHANTOM_CATCH runtime-instanceof (non-native) for catch type "
                    + tryCatch.getExceptionType());
            var isMatched = generateInstanceOf(new WasmGetLocal(exceptionVar), exceptionType);
            var br = new WasmBranch(isMatched, targetBlock);
            br.setResult(new WasmCast(new WasmGetLocal(exceptionVar), (WasmType.Reference) wasmType));
            target.add(new WasmDrop(br));
        }
    }

    @Override
    protected WasmType mapType(ValueType type) {
        return context.typeMapper().mapType(type);
    }

    @Override
    public void visit(SubscriptExpr expr) {
        accept(expr.getArray());
        var arrayData = result;
        arrayData.acceptVisitor(typeInference);
        var arrayTypeRef = (WasmType.CompositeReference) typeInference.getSingleResult();
        var arrayType = (WasmArray) arrayTypeRef.composite;

        accept(expr.getIndex());
        var index = result;

        var arrayGet = new WasmArrayGet(arrayType, arrayData, index);
        switch (expr.getType()) {
            case BYTE:
                arrayGet.setSignedType(WasmSignedType.SIGNED);
                break;
            case SHORT:
                arrayGet.setSignedType(WasmSignedType.SIGNED);
                break;
            case CHAR:
                arrayGet.setSignedType(WasmSignedType.UNSIGNED);
                break;
            default:
                break;
        }
        arrayGet.setLocation(expr.getLocation());
        result = arrayGet;
        if (expr.getType() == ArrayType.OBJECT && expr.getVariableIndex() >= 0) {
            var targetType = types.typeOf(expr.getVariableIndex());
            if (targetType != null) {
                var wasmTargetType = (WasmType.Reference) mapType(targetType.valueType);
                if (!isExtern(wasmTargetType)) {
                    result = new WasmCast(result, (WasmType.Reference) mapType(targetType.valueType));
                }
            }
        }
    }

    private boolean isExtern(WasmType.Reference type) {
        if (!(type instanceof WasmType.SpecialReference)) {
            return false;
        }
        return ((WasmType.SpecialReference) type).kind == WasmType.SpecialReferenceKind.EXTERN;
    }

    @Override
    public void visit(InvocationExpr expr) {
        result = invocation(expr, null, false);
    }

    @Override
    protected WasmExpression invocation(InvocationExpr expr, List<WasmExpression> resultConsumer, boolean willDrop) {
        var intrinsic = context.intrinsics().get(expr.getMethod());
        if (intrinsic != null) {
            var resultExpr = intrinsic.apply(expr, intrinsicContext);
            resultExpr.setLocation(expr.getLocation());
            if (resultConsumer != null) {
                if (willDrop) {
                    var drop = new WasmDrop(resultExpr);
                    drop.setLocation(expr.getLocation());
                    resultConsumer.add(drop);
                } else {
                    resultConsumer.add(resultExpr);
                }
                result = null;
                return null;
            } else {
                return resultExpr;
            }
        }
        return super.invocation(expr, resultConsumer, willDrop);
    }

    @Override
    protected WasmExpression mapFirstArgumentForCall(WasmExpression argument, WasmFunction function,
            MethodReference method) {
        return forceType(argument, function.getType().getParameterTypes().get(0));
    }

    @Override
    protected WasmExpression forceType(WasmExpression expression, ValueType type) {
        return forceType(expression, mapType(currentMethod.getReturnType()));
    }

    // EAGLER: made protected @Override so the shared BaseWasmGenerationVisitor can coerce
    // conditional-expression branch values to the block result type (fixes the wasm-gc
    // branch[0] type error where a widened Object value is yielded into a specific-struct
    // block). Legacy backend keeps the no-op base overload.
    @Override
    protected WasmExpression forceType(WasmExpression expression, WasmType expectedType) {
        expression.acceptVisitor(typeInference);
        var actualType = typeInference.getSingleResult();
        if (actualType == expectedType || !(actualType instanceof WasmType.CompositeReference)
                || !(expectedType instanceof WasmType.CompositeReference)) {
            return expression;
        }
        var actualComposite = ((WasmType.CompositeReference) actualType).composite;
        var expectedComposite = ((WasmType.CompositeReference) expectedType).composite;
        if (!(actualComposite instanceof WasmStructure) || !(expectedComposite instanceof WasmStructure)) {
            return expression;
        }

        var actualStruct = (WasmStructure) actualComposite;
        var expectedStruct = (WasmStructure) expectedComposite;
        if (!actualStruct.isSupertypeOf(expectedStruct)) {
            return expression;
        }

        return new WasmCast(expression, expectedComposite.getReference());
    }

    @Override
    public void visit(QualificationExpr expr) {
        if (expr.getQualified() == null) {
            var global = context.classInfoProvider().getStaticFieldLocation(expr.getField());
            result = new WasmGetGlobal(global);
            result.setLocation(expr.getLocation());
        } else {
            acceptWithType(expr.getQualified(), ValueType.object(expr.getField().getClassName()));
            var target = result;

            var classInfo = context.classInfoProvider().getClassInfo(expr.getField().getClassName());
            if (classInfo.isHeapStructure()) {
                var offset = context.classInfoProvider().getHeapFieldOffset(expr.getField());
                var cls = context.classes().get(expr.getField().getClassName());
                var field = cls.getField(expr.getField().getFieldName());
                result = getHeapField(field.getType(), target, offset);
            } else {
                result = getNormalField(expr, target);
            }
            result.setLocation(expr.getLocation());
        }
    }

    private WasmExpression getNormalField(QualificationExpr expr, WasmExpression target) {
        target.acceptVisitor(typeInference);
        var type = (WasmType.CompositeReference) typeInference.getSingleResult();
        if (type == null) {
            return new WasmUnreachable();
        }
        var struct = (WasmStructure) type.composite;
        var fieldIndex = context.classInfoProvider().getFieldIndex(expr.getField());
        if (fieldIndex < 0) {
            return new WasmUnreachable();
        }
        var structGet = new WasmStructGet(struct, target, fieldIndex);
        var cls = context.classes().get(expr.getField().getClassName());
        if (cls != null) {
            var field = cls.getField(expr.getField().getFieldName());
            if (field != null) {
                var fieldType = field.getType();
                if (fieldType instanceof ValueType.Primitive) {
                    switch (((ValueType.Primitive) fieldType).getKind()) {
                        case BOOLEAN:
                            structGet.setSignedType(WasmSignedType.UNSIGNED);
                            break;
                        case BYTE:
                            structGet.setSignedType(WasmSignedType.SIGNED);
                            break;
                        case SHORT:
                            structGet.setSignedType(WasmSignedType.SIGNED);
                            break;
                        case CHARACTER:
                            structGet.setSignedType(WasmSignedType.UNSIGNED);
                            break;
                        default:
                            break;
                    }
                }
            }
        }
        return structGet;
    }

    private WasmExpression getHeapField(ValueType type, WasmExpression target, int offset) {
        if (type instanceof ValueType.Primitive) {
            switch (((ValueType.Primitive) type).getKind()) {
                case BOOLEAN:
                case BYTE:
                    return new WasmLoadInt32(1, target, WasmInt32Subtype.INT8, offset);
                case CHARACTER:
                    return new WasmLoadInt32(2, target, WasmInt32Subtype.UINT16, offset);
                case SHORT:
                    return new WasmLoadInt32(2, target, WasmInt32Subtype.INT16, offset);
                case INTEGER:
                    return new WasmLoadInt32(4, target, WasmInt32Subtype.INT32, offset);
                case LONG:
                    return new WasmLoadInt64(8, target, WasmInt64Subtype.INT64, offset);
                case FLOAT:
                    return new WasmLoadFloat32(4, target, offset);
                case DOUBLE:
                    return new WasmLoadFloat64(8, target, offset);
                default:
                    break;
            }
        }
        return new WasmLoadInt32(4, target, WasmInt32Subtype.INT32, offset);
    }

    @Override
    protected WasmType condBlockType(WasmType thenType, WasmType elseType, ConditionalExpr conditional) {
        if (conditional.getVariableIndex() >= 0) {
            var javaType = types.typeOf(conditional.getVariableIndex());
            if (javaType != null) {
                return mapType(javaType.valueType);
            }
        }
        if (conditional.getConsequent().getVariableIndex() >= 0
                && conditional.getConsequent().getVariableIndex() == conditional.getAlternative().getVariableIndex()) {
            var javaType = types.typeOf(conditional.getConsequent().getVariableIndex());
            if (javaType != null) {
                return mapType(javaType.valueType);
            }
        }
        return super.condBlockType(thenType, elseType, conditional);
    }

    @Override
    protected boolean isAsyncSplit(MethodReference methodRef) {
        if (!isAsync()) {
            return false;
        }
        return isAsyncSplitImpl(findRealMethod(methodRef));
    }

    private boolean isAsyncSplitImpl(MethodReference methodRef) {
        if (asyncSplitMethods.isEmpty()) {
            return false;
        }
        if (asyncSplitMethods.contains(methodRef)) {
            return true;
        }

        var cls = context.classes().get(methodRef.getClassName());
        if (cls == null) {
            return false;
        }

        if (cls.getParent() != null) {
            if (isAsyncSplitImpl(new MethodReference(cls.getParent(), methodRef.getDescriptor()))) {
                return true;
            }
        }
        for (var itf : cls.getInterfaces()) {
            if (isAsyncSplitImpl(new MethodReference(itf, methodRef.getDescriptor()))) {
                return true;
            }
        }

        return false;
    }

    private MethodReference findRealMethod(MethodReference method) {
        var clsName = method.getClassName();
        while (clsName != null) {
            var cls = context.classes().get(clsName);
            if (cls == null) {
                break;
            }
            var methodReader = cls.getMethod(method.getDescriptor());
            if (methodReader != null) {
                return new MethodReference(clsName, method.getDescriptor());
            }
            clsName = cls.getParent();
            if (clsName != null && clsName.equals(cls.getName())) {
                break;
            }
        }
        return method;
    }

    private class SimpleCallSite extends CallSiteIdentifier {
        @Override
        public void generateRegister(List<WasmExpression> consumer, TextLocation location) {
        }

        @Override
        public void checkHandlerId(List<WasmExpression> target, TextLocation location) {
        }

        @Override
        public void generateThrow(List<WasmExpression> target, TextLocation location) {
        }
    }

    private WasmGCIntrinsicContext intrinsicContext = new WasmGCIntrinsicContext() {
        @Override
        public WasmExpression generate(Expr expr) {
            accept(expr);
            return result;
        }

        @Override
        public ResourceProvider resources() {
            return context.resources();
        }

        @Override
        public ClassLoader classLoader() {
            return context.classLoader();
        }

        @Override
        public WasmModule module() {
            return context.module();
        }

        @Override
        public WasmFunctionTypes functionTypes() {
            return context.functionTypes();
        }

        @Override
        public PreciseTypeInference types() {
            return types;
        }

        @Override
        public BaseWasmFunctionRepository functions() {
            return context.functions();
        }

        @Override
        public ClassHierarchy hierarchy() {
            return context.hierarchy();
        }

        @Override
        public WasmGCTypeMapper typeMapper() {
            return context.typeMapper();
        }

        @Override
        public WasmGCClassInfoProvider classInfoProvider() {
            return context.classInfoProvider();
        }

        @Override
        public WasmGCVirtualTableProvider virtualTables() {
            return context.virtualTables();
        }

        @Override
        public TemporaryVariablePool tempVars() {
            return tempVars;
        }

        @Override
        public ExpressionCache exprCache() {
            return exprCache;
        }

        @Override
        public WasmGCNameProvider names() {
            return context.names();
        }

        @Override
        public WasmGCStringProvider strings() {
            return context.strings();
        }

        @Override
        public WasmTag exceptionTag() {
            return context.getExceptionTag();
        }

        @Override
        public String entryPoint() {
            return context.entryPoint();
        }

        @Override
        public Diagnostics diagnostics() {
            return context.diagnostics();
        }

        @Override
        public MethodReference currentMethod() {
            return currentMethod;
        }

        @Override
        public ClassInitializerInfo classInitInfo() {
            return context.classInitInfo();
        }

        @Override
        public DependencyInfo dependency() {
            return context.dependency();
        }

        @Override
        public void addToInitializer(Consumer<WasmFunction> initializerContributor) {
            context.addToInitializer(initializerContributor);
        }

        @Override
        public boolean isAsync() {
            return WasmGCGenerationVisitor.this.isAsync();
        }

        @Override
        public boolean isAsyncMethod(MethodReference method) {
            return asyncSplitMethods.contains(method);
        }
    };
}
