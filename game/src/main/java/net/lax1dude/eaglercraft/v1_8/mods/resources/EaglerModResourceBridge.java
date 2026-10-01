package net.lax1dude.eaglercraft.v1_8.mods.resources;

import java.util.concurrent.CompletableFuture;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;

/** Staged Java/JavaScript boundary. Root runtime sources remain unchanged. */
final class EaglerModResourceBridge {
    static final int MAX_SNAPSHOT_JSON = 2 * 1024 * 1024;

    private EaglerModResourceBridge() { }

    @JSBody(script = "return !!globalThis.EaglerModResourceRegistry;")
    static native boolean registryPresent();

    @JSBody(script = "var r=globalThis.EaglerModResourceRegistry;try{if(!r||r.version!==1||r.validated!==true||typeof r.snapshotPacks!=='function')return null;var v=r.snapshotPacks();var s=JSON.stringify(v);return typeof s==='string'&&s.length<=2097152?s:null;}catch(e){console.warn('[EaglerModResources] snapshot failed',e);return null;}")
    static native String snapshotPacks();

    @JSBody(params = {"packHandle", "resourceHandle"}, script = "var r=globalThis.EaglerModResourceRegistry;try{if(!r||r.version!==1||r.validated!==true||typeof r.readResource!=='function')return null;var v=r.readResource(packHandle,resourceHandle),b=null;if(v instanceof ArrayBuffer)b=v;else if(ArrayBuffer.isView(v))b=v.buffer.slice(v.byteOffset,v.byteOffset+v.byteLength);return b&&b.byteLength<=16777216?b:null;}catch(e){console.warn('[EaglerModResources] read failed',e);return null;}")
    static native ArrayBuffer readResource(String packHandle, String resourceHandle);

    @JSBody(params = {"moduleId", "generation", "json"}, script = "var r=globalThis.EaglerModResourceRegistry;try{return !!r&&r.version===1&&r.validated===true&&typeof r.publishResolvedLanguage==='function'&&r.publishResolvedLanguage(moduleId,generation,json)===true;}catch(e){console.warn('[EaglerModResources] language publish failed',e);return false;}")
    static native boolean publishResolvedLanguage(String moduleId, int generation, String json);

    @JSBody(params = {"moduleId", "generation", "json"}, script = "var r=globalThis.EaglerModResourceRegistry;try{return !!r&&r.version===1&&r.validated===true&&typeof r.publishResourceReload==='function'&&r.publishResourceReload(moduleId,generation,json)===true;}catch(e){console.warn('[EaglerModResources] reload publish failed',e);return false;}")
    static native boolean publishResourceReload(String moduleId, int generation, String json);

    @JSBody(params = {"moduleId", "generation", "json"}, script = "var r=globalThis.EaglerModResourceRegistry;try{if(!r||r.version!==1||r.validated!==true||typeof r.publishResourceModel!=='function')return false;var ok=r.publishResourceModel(moduleId,generation,json)===true;if(ok){var g=globalThis.__eaglerNativeResourceModelPublication||(globalThis.__eaglerNativeResourceModelPublication={});g[moduleId]=JSON.parse(json);}return ok;}catch(e){console.warn('[EaglerModResources] model publish failed',e);return false;}")
    static native boolean publishResourceModel(String moduleId, int generation, String json);

    @JSBody(params = {"moduleId", "generation"}, script = "var r=globalThis.EaglerModResourceRegistry;try{return !!r&&r.version===1&&r.validated===true&&typeof r.clearResourceModel==='function'&&r.clearResourceModel(moduleId,generation)===true;}catch(e){console.warn('[EaglerModResources] model clear failed',e);return false;}")
    static native boolean clearResourceModel(String moduleId, int generation);

    @JSBody(params = {"moduleId", "generation"}, script = "var r=globalThis.EaglerModResourceRegistry;try{return !!r&&r.version===1&&r.validated===true&&typeof r.modelPublicationAllowed==='function'&&r.modelPublicationAllowed(moduleId,generation)===true;}catch(e){return false;}")
    static native boolean modelPublicationAllowed(String moduleId, int generation);

    /**
     * Starts one real ledger operation per registry mount owner and begins the
     * requested queue stage.  The operation and token objects are deliberately
     * retained only in the JS boundary: Java must never copy or manufacture
     * their identity.  The empty string means the optional test ledger is
     * disabled; an exclamation mark means the configured ledger rejected the
     * operation and must fail closed.
     */
    @JSBody(params = {"reloadGeneration", "queue", "scope"}, script =
            "var r=globalThis.EaglerModResourceRegistry;var started=[];try{"
          + "if(!r||!r.performanceTelemetry)return '';"
          + "var snapshot=typeof r.snapshotPacks==='function'?r.snapshotPacks():null;"
          + "var rows=snapshot&&Array.isArray(snapshot.mountRequests)?snapshot.mountRequests:[];"
          + "if(rows.length<1||!Number.isSafeInteger(reloadGeneration)||reloadGeneration<1)return '!';"
          + "var api=r.performanceTelemetry,store=globalThis.__eaglerNativeResourcePerformance;"
          + "if(!store)store=globalThis.__eaglerNativeResourcePerformance={serial:1,batches:Object.create(null)};"
          + "var batch='b'+(store.serial++),entries=[];"
          + "for(var i=0;i<rows.length;i++){var moduleId=rows[i]&&rows[i].moduleId;"
          + "if(typeof moduleId!=='string'||moduleId.length<1)throw new Error('RESOURCE_TELEMETRY_MODULE');"
          + "var operationId='native-resource-'+queue+'-'+moduleId+'-'+reloadGeneration+'-'+batch;"
          + "var operation=r.beginResourcePerformanceOperation(moduleId,reloadGeneration,{operationId:operationId});"
          + "if(!operation)throw new Error('RESOURCE_TELEMETRY_OPERATION');"
          + "started.push({operation:operation,queue:queue});"
          + "var token=api.beginStage(queue,operation);if(!token)throw new Error('RESOURCE_TELEMETRY_STAGE');"
          + "entries.push({moduleId:moduleId,operation:operation,queue:queue,token:token});}"
          + "store.batches[batch]={entries:entries};return batch;"
          + "}catch(e){for(var j=started.length-1;j>=0;j--){try{started[j].operation.cancel();}catch(ignore){}}return '!';}")
    private static native String nativeBeginPerformanceBatch(int reloadGeneration, String queue, String scope);

    @JSBody(params = {"batch", "success"}, script =
            "var r=globalThis.EaglerModResourceRegistry,store=globalThis.__eaglerNativeResourcePerformance,row=null;"
          + "try{if(batch===null||batch===undefined||batch==='')return true;"
          + "row=store&&store.batches&&store.batches[batch];if(!row||!r||!r.performanceTelemetry)return false;"
          + "var api=r.performanceTelemetry,ok=true;"
          + "if(success===true){for(var i=0;i<row.entries.length;i++){var e=row.entries[i];"
          + "try{if(!api.completeStage(e.queue,e.token,e.operation))ok=false;}catch(ignore){ok=false;}}"
          + "if(!ok){for(var c=0;c<row.entries.length;c++){try{row.entries[c].operation.cancel();}catch(ignore2){}}delete store.batches[batch];return false;}"
          + "var closed=[];for(var j=0;j<row.entries.length;j++){var close=row.entries[j].operation;try{"
          + "if(close.close())closed.push(close);else{ok=false;break;}"
          + "}catch(ignore3){ok=false;break;}"
          + "}if(!ok){for(var k=0;k<row.entries.length;k++){var candidate=row.entries[k].operation;"
          + "if(closed.indexOf(candidate)<0){try{candidate.cancel();}catch(ignore4){}}}delete store.batches[batch];return false;}"
          + "delete store.batches[batch];return true;}"
          + "for(var n=0;n<row.entries.length;n++){try{if(!row.entries[n].operation.cancel())ok=false;}catch(ignore5){ok=false;}}"
          + "delete store.batches[batch];return ok;"
          + "}catch(e){if(row&&row.entries){for(var z=0;z<row.entries.length;z++){try{row.entries[z].operation.cancel();}catch(ignore6){}}}"
          + "if(store&&store.batches)delete store.batches[batch];return false;}")
    private static native boolean nativeFinishPerformanceBatch(String batch, boolean success);

    @JSBody(params = {"batch", "success"}, script =
            "var r=globalThis.EaglerModResourceRegistry,store=globalThis.__eaglerNativeResourcePerformance,row=null;"
          + "try{if(batch===null||batch===undefined||batch==='')return true;"
          + "row=store&&store.batches&&store.batches[batch];if(!row||!r||!r.performanceTelemetry)return false;"
          + "if(success!==true){var canceled=true;for(var i=0;i<row.entries.length;i++){try{if(!row.entries[i].operation.cancel())canceled=false;}catch(ignore){canceled=false;}}"
          + "delete store.batches[batch];return canceled;}"
          + "var closed=[];for(var j=0;j<row.entries.length;j++){var op=row.entries[j].operation;try{"
          + "if(op.close())closed.push(op);else break;}catch(ignore2){break;}}"
          + "if(closed.length===row.entries.length){delete store.batches[batch];return true;}"
          + "for(var k=0;k<row.entries.length;k++){var candidate=row.entries[k].operation;if(closed.indexOf(candidate)<0){try{candidate.cancel();}catch(ignore3){}}}"
          + "delete store.batches[batch];return false;"
          + "}catch(e){if(row&&row.entries){for(var z=0;z<row.entries.length;z++){try{row.entries[z].operation.cancel();}catch(ignore4){}}}"
          + "if(store&&store.batches)delete store.batches[batch];return false;}")
    private static native boolean nativeClosePerformanceBatch(String batch, boolean success);

    @JSBody(params = {"batch", "moduleId", "accepted"}, script =
            "var r=globalThis.EaglerModResourceRegistry;try{"
          + "var store=globalThis.__eaglerNativeResourcePerformance,row=store&&store.batches&&store.batches[batch];"
          + "if(!row||!r||!r.performanceTelemetry)return false;"
          + "for(var i=0;i<row.entries.length;i++){var e=row.entries[i];if(e.moduleId===moduleId)"
          + "return r.recordResourcePerformancePublication(e.operation,accepted===true);}"
          + "return false;}catch(e){return false;}")
    private static native boolean nativeRecordPerformancePublication(String batch, String moduleId, boolean accepted);

    @JSBody(params = {"moduleId", "reloadGeneration", "accepted"}, script =
            "var r=globalThis.EaglerModResourceRegistry,store=globalThis.__eaglerNativeResourcePerformance,operation=null;"
          + "try{if(!r||!r.performanceTelemetry)return true;"
          + "if(typeof r.beginResourcePerformanceOperation!=='function')return false;"
          + "if(!store)store=globalThis.__eaglerNativeResourcePerformance={serial:1,batches:Object.create(null)};"
          + "var batch='d'+(store.serial++);operation=r.beginResourcePerformanceOperation(moduleId,reloadGeneration,"
          + "{operationId:'native-draw-'+moduleId+'-'+reloadGeneration+'-'+batch});"
          + "if(!operation)return false;var api=r.performanceTelemetry,token=api.beginStage('publication',operation);"
          + "if(!token){operation.cancel();return false;}var recorded=r.recordResourcePerformanceDraw(operation,accepted===true);"
          + "var completed=recorded&&api.completeStage('publication',token,operation);"
          + "if(completed&&operation.close())return true;operation.cancel();return false;"
          + "}catch(e){try{if(operation)operation.cancel();}catch(ignore){}return false;}")
    private static native boolean nativeRecordPerformanceDrawOutcome(String moduleId, int reloadGeneration,
            boolean accepted);

    @JSBody(params = {"batch"}, script =
            "var r=globalThis.EaglerModResourceRegistry;try{"
          + "var store=globalThis.__eaglerNativeResourcePerformance,row=store&&store.batches&&store.batches[batch];"
          + "if(!row||!r||!r.performanceTelemetry||row.entries.length<1)"
          + "return Promise.reject(new Error('RESOURCE_TELEMETRY_DECODE_BATCH'));"
          + "var api=r.performanceTelemetry;if(typeof api.finishDecodeAndAwait!=='function')"
          + "return Promise.reject(new Error('RESOURCE_TELEMETRY_ATOMIC_DECODE_API'));"
          + "var waits=[];for(var i=0;i<row.entries.length;i++){var e=row.entries[i];"
          + "if(e.queue!=='decode'||!e.operation)return Promise.reject(new Error('RESOURCE_TELEMETRY_DECODE_QUEUE'));"
          + "var p=api.finishDecodeAndAwait(e.token,e.operation);"
          + "if(!p||typeof p.then!=='function')return Promise.reject(new Error('RESOURCE_TELEMETRY_DECODE_AWAIT'));waits.push(p);}"
          + "return Promise.all(waits);"
          + "}catch(e){return Promise.reject(e);}")
    private static native JSObject nativeDecodeFinishAndAwaitPromise(String batch);

    @JSFunctor
    private interface PerformancePromiseCallback extends JSObject {
        void call(JSObject value);
    }

    @JSBody(params = {"promise", "resolve", "reject"}, script =
            "try{promise.then(function(v){resolve(v);},function(e){reject(e);});}catch(e){reject(e);}")
    private static native void nativePromiseThen(JSObject promise, PerformancePromiseCallback resolve,
            PerformancePromiseCallback reject);

    @Async
    private static native void awaitDecodeFinish(String batch);

    private static void awaitDecodeFinish(String batch, final AsyncCallback<Void> callback) {
        if (batch == null) {
            callback.complete(null);
            return;
        }
        final JSObject promise;
        try {
            promise = nativeDecodeFinishAndAwaitPromise(batch);
        } catch (Throwable failure) {
            callback.error(failure);
            return;
        }
        if (promise == null) {
            callback.complete(null);
            return;
        }
        nativePromiseThen(promise, ignored -> callback.complete(null), ignored ->
                callback.error(new IllegalStateException("Resource performance decode barrier rejected")));
    }

    /** Returns null when the optional telemetry ledger is disabled. */
    static String performanceBeginBatch(int reloadGeneration, String queue, String scope) {
        String result = nativeBeginPerformanceBatch(reloadGeneration, queue, scope == null ? "" : scope);
        if ("!".equals(result)) throw new IllegalStateException("Resource performance telemetry rejected " + queue);
        return result == null || result.isEmpty() ? null : result;
    }

    static void performanceFinishBatch(String batch, boolean success) {
        if (batch != null && !nativeFinishPerformanceBatch(batch, success)) {
            throw new IllegalStateException("Resource performance telemetry could not close a stage batch");
        }
    }

    static void performanceCloseBatch(String batch, boolean success) {
        if (batch != null && !nativeClosePerformanceBatch(batch, success)) {
            throw new IllegalStateException("Resource performance telemetry could not close a stage batch");
        }
    }

    /**
     * Test-only receipt barrier.  The returned future is already complete when
     * telemetry is disabled; otherwise it resolves only after every decode
     * operation in the batch atomically ends decode and releases its
     * generation-N continuation gate.
     */
    static CompletableFuture<Void> performanceAwaitDecodeFinish(String batch) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (batch == null) {
            result.complete(null);
            return result;
        }
        try {
            awaitDecodeFinish(batch, new AsyncCallback<Void>() {
                @Override
                public void complete(Void ignored) {
                    result.complete(null);
                }

                @Override
                public void error(Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    static void performanceRecordPublication(String batch, String moduleId, boolean accepted) {
        if (batch != null && !nativeRecordPerformancePublication(batch, moduleId, accepted)) {
            throw new IllegalStateException("Resource performance telemetry rejected publication verdict");
        }
    }

    /** Records the real ProbeScreen authorization result with a fresh, registry-derived operation. */
    static boolean performanceRecordDrawOutcome(String moduleId, int reloadGeneration, boolean accepted) {
        return nativeRecordPerformanceDrawOutcome(moduleId, reloadGeneration, accepted);
    }

    @JSBody(params = {"reason"}, script = "var r=globalThis.EaglerModResourceRegistry;try{return !!r&&typeof r.failResourceInitialization==='function'&&r.failResourceInitialization(reason)===true;}catch(e){console.warn('[EaglerModResources] failure publication failed',e);return false;}")
    static native boolean failResourceInitialization(String reason);
}
