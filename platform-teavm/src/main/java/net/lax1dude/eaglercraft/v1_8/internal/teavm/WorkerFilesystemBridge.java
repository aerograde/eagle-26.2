package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSString;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Uint8Array;

import net.lax1dude.eaglercraft.v1_8.internal.IEaglerFilesystem;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformFilesystem;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.VFSFilenameIterator;
import net.lax1dude.eaglercraft.v1_8.internal.buffer.ByteBuffer;
import net.lax1dude.eaglercraft.v1_8.internal.buffer.EaglerArrayBufferAllocator;
import net.lax1dude.eaglercraft.v1_8.internal.vfs2.EaglerFileSystemException;

/**
 * The page owns world creation and its storage choice. A worker must use that same
 * database, including when IndexedDB is forbidden (opaque blob/about:blank origins
 * or explicit RAMDisk mode). Two independent RAM disks lose level.dat at launch.
 *
 * Normal persistent worlds keep direct worker IndexedDB access. Only unavailable
 * worker storage uses this async bridge to the page's existing filesystem. It has
 * one authoritative copy, so Save and Quit, worker replacement, world export and
 * reopening see the same files without copying whole worlds or changing save format.
 */
public final class WorkerFilesystemBridge implements IEaglerFilesystem {

    private static IEaglerFilesystem workerFilesystem;
    private static boolean workerRealm;

    private final String name;
    private final boolean ramdisk;

    private WorkerFilesystemBridge(String name, boolean ramdisk) {
        this.name = name;
        this.ramdisk = ramdisk;
    }

    interface Message extends JSObject {
        @JSProperty String getOp();
        @JSProperty String getPath();
        @JSProperty String getDestination();
        @JSProperty JSArray<JSString> getPaths();
        @JSProperty JSArray<ArrayBuffer> getBuffers();
        @JSProperty int getValue();
        @JSProperty String getName();
        @JSProperty String getError();
    }

    @JSFunctor
    interface Handler extends JSObject {
        void accept(Message message);
    }

    /** Register before posting role:server. Listener lifetime is the worker lifetime. */
    public static Runnable attachPage(JSObject worker, IEaglerFilesystem filesystem) {
        if(filesystem == null) {
            throw new IllegalStateException("Game filesystem has not been mounted");
        }
        PageEndpoint endpoint = new PageEndpoint(worker, filesystem);
        attachPageNative(worker, endpoint::enqueue);
        return endpoint::close;
    }

    private static final class PageEndpoint {
        private final JSObject worker;
        private final IEaglerFilesystem filesystem;
        private final ArrayDeque<Message> requests = new ArrayDeque<>();
        private boolean running;
        private boolean closed;

        private PageEndpoint(JSObject worker, IEaglerFilesystem filesystem) {
            this.worker = worker;
            this.filesystem = filesystem;
        }

        private void enqueue(Message request) {
            if(closed) return;
            requests.addLast(request);
            if(!running) {
                running = true;
                // One green thread preserves request order, including compound
                // move/copy operations that suspend between IDB transactions.
                new Thread(this::drain, "WorldFilesystemRequests").start();
            }
        }

        private void drain() {
            while(!closed && !requests.isEmpty()) {
                Message request = requests.removeFirst();
                Message response;
                try {
                    response = execute(filesystem, request);
                } catch(Throwable error) {
                    response = failure(error.toString());
                }
                if(!closed) reply(worker, request, response);
            }
            running = false;
        }

        private void close() {
            closed = true;
            requests.clear();
            detachPageNative(worker);
            // An in-flight persistent transaction may finish, but its coroutine
            // releases the endpoint afterward. The page's actual store stays open.
        }
    }

    @JSBody(params = { "worker", "handler" }, script = "var listener = function(e) {"
            + " if (e.data && e.data.eaglerFsRequest === true) handler(e.data); };"
            + "worker.__eaglerFilesystemListener = listener; worker.addEventListener('message', listener);")
    private static native void attachPageNative(JSObject worker, Handler handler);

    @JSBody(params = { "worker" }, script = "var listener = worker.__eaglerFilesystemListener;"
            + "if(listener) worker.removeEventListener('message', listener);"
            + "delete worker.__eaglerFilesystemListener;")
    private static native void detachPageNative(JSObject worker);

    @JSBody(params = { "worker", "request", "response" }, script =
            "response.eaglerFsReply = true; response.id = request.id;"
            + "worker.postMessage(response, response.buffers || []);")
    private static native void reply(JSObject worker, Message request, Message response);

    /** Called only in the dedicated server worker, before platform context setup. */
    public static void initializeWorker() {
        if(workerRealm) return;
        workerRealm = true;
        Message configuration = call(message("configure", null, null, null, null));
        String database = configuration.getName();
        boolean temporary = configuration.getValue() != 0;
        if(!temporary) {
            workerFilesystem = PlatformFilesystem.initializePersist(database);
        }
        if(workerFilesystem == null) {
            workerFilesystem = new WorkerFilesystemBridge(database, temporary);
        }
    }

    public static IEaglerFilesystem getWorkerFilesystem() {
        return workerRealm ? workerFilesystem : null;
    }

    @JSBody(params = { "op", "path", "destination", "paths", "buffers" }, script =
            "return {op:op,path:path,destination:destination,paths:paths,buffers:buffers,error:null};")
    private static native Message message(String op, String path, String destination,
            JSArray<JSString> paths, JSArray<ArrayBuffer> buffers);

    @JSBody(params = { "value", "name", "paths", "buffers" }, script =
            "return {value:value,name:name,paths:paths,buffers:buffers,error:null};")
    private static native Message result(int value, String name, JSArray<JSString> paths,
            JSArray<ArrayBuffer> buffers);

    @JSBody(params = { "error" }, script = "return {error:error};")
    private static native Message failure(String error);

    @JSBody(params = { "view" }, script =
            "return view.buffer.slice(view.byteOffset, view.byteOffset + view.byteLength);")
    private static native ArrayBuffer copyBytes(Uint8Array view);

    private static ArrayBuffer copyBuffer(ByteBuffer buffer) {
        return copyBytes(EaglerArrayBufferAllocator.getDataView8Unsigned(buffer));
    }

    private static JSArray<JSString> paths(String[] values) {
        JSArray<JSString> result = JSArray.create();
        for(String value : values) result.push(JSString.valueOf(value));
        return result;
    }

    private static String[] paths(JSArray<JSString> values) {
        String[] result = new String[values.getLength()];
        for(int i = 0; i < result.length; ++i) result[i] = values.get(i).stringValue();
        return result;
    }

    private static JSArray<ArrayBuffer> buffers(ByteBuffer[] values) {
        JSArray<ArrayBuffer> result = JSArray.create();
        for(ByteBuffer value : values) result.push(copyBuffer(value));
        return result;
    }

    private static Message execute(IEaglerFilesystem fs, Message request) {
        String path = request.getPath();
        switch(request.getOp()) {
        case "configure": return result(fs.isRamdisk() ? 1 : 0, fs.getFilesystemName(), null, null);
        case "read": {
            ByteBuffer buffer = fs.eaglerRead(path);
            if(buffer == null) return result(0, null, null, null);
            try {
                JSArray<ArrayBuffer> data = JSArray.create();
                data.push(copyBuffer(buffer));
                return result(1, null, null, data);
            } finally {
                PlatformRuntime.freeByteBuffer(buffer);
            }
        }
        case "write": {
            String[] names = paths(request.getPaths());
            JSArray<ArrayBuffer> data = request.getBuffers();
            if(names.length != data.getLength()) throw new IllegalArgumentException("path/data batch length mismatch");
            ByteBuffer[] wrapped = new ByteBuffer[names.length];
            try {
                for(int i = 0; i < wrapped.length; ++i) {
                    wrapped[i] = EaglerArrayBufferAllocator.wrapByteBufferTeaVM(Int8Array.create(data.get(i)));
                }
                fs.eaglerWriteBatch(names, wrapped);
            } finally {
                for(ByteBuffer buffer : wrapped) if(buffer != null) PlatformRuntime.freeByteBuffer(buffer);
            }
            return result(1, null, null, null);
        }
        case "delete": return result(fs.eaglerDelete(path) ? 1 : 0, null, null, null);
        case "deleteBatch": fs.eaglerDeleteBatch(paths(request.getPaths())); break;
        case "deleteRecursive": fs.eaglerDeleteRecursive(path); break;
        case "exists": return result(fs.eaglerExists(path) ? 1 : 0, null, null, null);
        case "size": return result(fs.eaglerSize(path), null, null, null);
        case "move": return result(fs.eaglerMove(path, request.getDestination()) ? 1 : 0, null, null, null);
        case "copy": return result(fs.eaglerCopy(path, request.getDestination()), null, null, null);
        case "iterate":
        case "iterateRecursive":
        case "children": {
            List<String> names = new ArrayList<>();
            if("children".equals(request.getOp())) fs.eaglerIterateChildren(path, names::add);
            else fs.eaglerIterate(path, names::add, "iterateRecursive".equals(request.getOp()));
            return result(1, null, paths(names.toArray(new String[0])), null);
        }
        default: throw new IllegalArgumentException("Unknown filesystem request: " + request.getOp());
        }
        return result(1, null, null, null);
    }

    private static Message call(Message request) {
        Message response = AsyncRequests.request(request);
        if(response.getError() != null) throw new EaglerFileSystemException(response.getError());
        return response;
    }

    static final class AsyncRequests {
        @Async
        static native Message request(Message message);

        private static void request(Message message, AsyncCallback<Message> callback) {
            requestNative(message, callback::complete);
        }

        @JSBody(params = { "request", "done" }, script =
                "var state = self.__eaglerFilesystemRequests;"
                + "if (!state) {"
                + " state = self.__eaglerFilesystemRequests = {next:0,pending:new Map()};"
                + " self.addEventListener('message', function(e) {"
                + "  var r = e.data; if (!r || r.eaglerFsReply !== true) return;"
                + "  var cb = state.pending.get(r.id); if (!cb) return;"
                + "  state.pending.delete(r.id); cb(r);"
                + " });"
                + "}"
                + "request.id = ++state.next; request.eaglerFsRequest = true;"
                + "state.pending.set(request.id, done);"
                + "try { self.postMessage(request, request.buffers || []); } catch(e) {"
                + " state.pending.delete(request.id); done({error:String(e)}); }"
        )
        static native void requestNative(Message request, Handler done);
    }

    @Override public String getFilesystemName() { return name; }
    @Override public String getInternalDBName() { return "page:" + name; }
    @Override public boolean isRamdisk() { return ramdisk; }

    @Override public ByteBuffer eaglerRead(String pathName) {
        Message response = call(message("read", pathName, null, null, null));
        return response.getValue() == 0 ? null
                : EaglerArrayBufferAllocator.wrapByteBufferTeaVM(Int8Array.create(response.getBuffers().get(0)));
    }

    @Override public void eaglerWrite(String pathName, ByteBuffer data) {
        eaglerWriteBatch(new String[] { pathName }, new ByteBuffer[] { data });
    }

    @Override public void eaglerWriteBatch(String[] pathNames, ByteBuffer[] data) {
        if(pathNames.length != data.length) throw new IllegalArgumentException("path/data batch length mismatch");
        if(pathNames.length != 0) call(message("write", null, null, paths(pathNames), buffers(data)));
    }

    @Override public boolean eaglerDelete(String pathName) {
        return call(message("delete", pathName, null, null, null)).getValue() != 0;
    }

    @Override public void eaglerDeleteBatch(String[] pathNames) {
        if(pathNames.length != 0) call(message("deleteBatch", null, null, paths(pathNames), null));
    }

    @Override public void eaglerDeleteRecursive(String pathName) {
        call(message("deleteRecursive", pathName, null, null, null));
    }

    @Override public boolean eaglerExists(String pathName) {
        return call(message("exists", pathName, null, null, null)).getValue() != 0;
    }

    @Override public boolean eaglerMove(String pathNameOld, String pathNameNew) {
        return call(message("move", pathNameOld, pathNameNew, null, null)).getValue() != 0;
    }

    @Override public int eaglerCopy(String pathNameOld, String pathNameNew) {
        return call(message("copy", pathNameOld, pathNameNew, null, null)).getValue();
    }

    @Override public int eaglerSize(String pathName) {
        return call(message("size", pathName, null, null, null)).getValue();
    }

    @Override public void eaglerIterate(String pathName, VFSFilenameIterator iterator, boolean recursive) {
        iterate(recursive ? "iterateRecursive" : "iterate", pathName, iterator);
    }

    @Override public void eaglerIterateChildren(String pathName, VFSFilenameIterator iterator) {
        iterate("children", pathName, iterator);
    }

    private void iterate(String op, String pathName, VFSFilenameIterator iterator) {
        for(String path : paths(call(message(op, pathName, null, null, null)).getPaths())) iterator.next(path);
    }

    @Override public void closeHandle() {
        // The page owns this handle and retains worlds across server worker restarts.
    }
}
