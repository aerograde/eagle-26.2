import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const sourceUrl = new URL(
	'../../main/java/net/lax1dude/eaglercraft/v1_8/internal/teavm/TeaVMWebSocketClient.java',
	import.meta.url,
);
const source = await readFile(sourceUrl, 'utf8');
const sourceMarker = '@JSBody(params = { "uri" }, script =';
const methodStart = source.indexOf(sourceMarker);
assert.notEqual(methodStart, -1, 'native WebSocket JSBody exists');
const methodEnd = source.indexOf(')\n\tprivate static native WebSocket createClientNativeWebSocket', methodStart);
assert.notEqual(methodEnd, -1, 'native WebSocket JSBody ends before its native declaration');
const annotationBody = source.slice(methodStart, methodEnd);
const scriptStart = annotationBody.indexOf('script =') + 'script ='.length;
const jsBody = [...annotationBody.slice(scriptStart).matchAll(/"((?:[^"\\]|\\.)*)"/g)]
	.map((match) => JSON.parse(`"${match[1]}"`))
	.join('');

function makeNativeWebSocket() {
	function NativeWebSocket(uri) {
		if (!new.target) throw new TypeError('WebSocket requires new');
		this.uri = uri;
		this.kind = 'native';
	}
	NativeWebSocket.prototype.send = function () {};
	NativeWebSocket.prototype.close = function () {};
	NativeWebSocket.prototype.addEventListener = function () {};
	NativeWebSocket.prototype.removeEventListener = function () {};
	return NativeWebSocket;
}

function contextWithWebSocket(WebSocket) {
	const context = { WebSocket };
	context.window = context;
	return vm.createContext(context);
}

function makeRoute(context) {
	return vm.runInContext(`(function(uri) { ${jsBody} })`, context);
}

test('site-injector order: a late capture of the UMD Proxy is corrected from its native prototype', () => {
	const NativeWebSocket = makeNativeWebSocket();
	const context = contextWithWebSocket(NativeWebSocket);
	context.autoWsCalls = 0;
	vm.runInContext(`
		(function(root, factory) {
			factory((root = globalThis).wispcraft = {});
		})(this, function(exports) {
			exports.setWispUrl = function() {};
			exports.showSettingsUI = function() {};
		});
		window.WebSocket = new Proxy(WebSocket, {
			construct() {
				globalThis.autoWsCalls++;
				throw new Error('Wispcraft AutoWS must not receive the client relay');
			}
		});
		globalThis.__eaglerNativeWebSocket = window.WebSocket;
	`, context);

	const socket = makeRoute(context)('wss://relay.example/');
	assert.equal(socket.kind, 'native');
	assert.equal(socket.uri, 'wss://relay.example/');
	assert.equal(context.autoWsCalls, 0);
});

test('a constructor captured before Wispcraft remains preferred over the patched global', () => {
	const NativeWebSocket = makeNativeWebSocket();
	const context = contextWithWebSocket(NativeWebSocket);
	context.__eaglerNativeWebSocket = NativeWebSocket;
	context.wispcraft = { setWispUrl() {}, showSettingsUI() {} };
	context.autoWsCalls = 0;
	vm.runInContext(`
		window.WebSocket = new Proxy(WebSocket, {
			construct() {
				globalThis.autoWsCalls++;
				throw new Error('patched global must not receive the client relay');
			}
		});
	`, context);

	const socket = makeRoute(context)('wss://relay.example/');
	assert.equal(socket.kind, 'native');
	assert.equal(context.autoWsCalls, 0);
});

test('an unmodified global declines recovery and leaves normal TeaVM fallback available', () => {
	const NativeWebSocket = makeNativeWebSocket();
	const context = contextWithWebSocket(NativeWebSocket);
	context.WebSocket.create = (uri) => new context.WebSocket(uri);
	const recovered = makeRoute(context)('wss://relay.example/');

	assert.equal(recovered, null);
	assert.match(source, /nativeSocket != null \? nativeSocket : WebSocket\.create\(socketURI\)/);
	const ordinaryFallback = recovered || context.WebSocket.create('wss://relay.example/');
	assert.equal(ordinaryFallback.kind, 'native');
});

test('malformed Wispcraft markers and non-WebSocket constructors do not trigger recovery', () => {
	const NativeWebSocket = makeNativeWebSocket();
	const context = contextWithWebSocket(NativeWebSocket);
	context.wispcraft = { setWispUrl() {} };
	context.__eaglerNativeWebSocket = NativeWebSocket;
	context.WebSocket = function AutoWS() {};
	context.WebSocket.prototype = {};

	assert.equal(makeRoute(context)('wss://relay.example/'), null);
});

test('a captured constructor without Wispcraft leaves ordinary socket creation untouched', () => {
	const NativeWebSocket = makeNativeWebSocket();
	const context = contextWithWebSocket(NativeWebSocket);
	context.__eaglerNativeWebSocket = NativeWebSocket;
	assert.equal(makeRoute(context)('wss://relay.example/'), null);
});

test('client teardown detaches callbacks before closing a possibly synchronous adapter', () => {
	const closeStart = source.indexOf('\n\tpublic void close() {');
	const closeEnd = source.indexOf('\n\t@Override', closeStart);
	assert.notEqual(closeStart, -1, 'client close method exists');
	assert.ok(closeEnd > closeStart, 'client close method ends before the next override');
	const closeMethod = source.slice(closeStart, closeEnd);
	assert.match(closeMethod, /if\s*\(closeStarted\)\s*\{\s*return;/,
		'close is guarded against repeated calls');
	assert.match(closeMethod, /closeStarted\s*=\s*true;/,
		'close becomes idempotent before any teardown work');
	assert.ok(closeMethod.indexOf('sockIsConnecting = false;') < closeMethod.indexOf('closeSocketAfterCallback('));
	assert.ok(closeMethod.indexOf('sockIsConnected = false;') < closeMethod.indexOf('closeSocketAfterCallback('));
	assert.ok(closeMethod.indexOf('sockIsFailed = false;') < closeMethod.indexOf('closeSocketAfterCallback('));
	assert.ok(closeMethod.indexOf('clearFrames();') < closeMethod.indexOf('closeSocketAfterCallback('));
	assert.match(closeMethod, /closeSocketAfterCallback\(sock,\s*openListener,\s*closeListener,\s*messageListener,\s*errorListener\)/,
		'close delegates detachment and deferred adapter close to the exercised helper');
	assert.equal((source.match(/if \(closeStarted\) return;/g) || []).length, 4,
		'all four TeaVM callbacks ignore events after close starts');
});

const closeBodyMarker = '@JSBody(params = { "socket", "openListener", "closeListener", "messageListener", "errorListener" }, script =';
const closeBodyStart = source.indexOf(closeBodyMarker);
assert.notEqual(closeBodyStart, -1, 'safe adapter close JSBody exists');
const closeBodyEnd = source.indexOf(')\n\tprivate static native void closeSocketAfterCallback', closeBodyStart);
assert.notEqual(closeBodyEnd, -1, 'safe adapter close JSBody ends before its native declaration');
const closeAnnotation = source.slice(closeBodyStart, closeBodyEnd);
const closeScriptStart = closeAnnotation.indexOf('script =') + 'script ='.length;
const closeScript = [...closeAnnotation.slice(closeScriptStart).matchAll(/"((?:[^"\\]|\\.)*)"/g)]
	.map((match) => JSON.parse(`"${match[1]}"`))
	.join('');

function makeCloseHelper({ microtasks = true } = {}) {
	const scheduled = [];
	const context = microtasks
		? { queueMicrotask: (callback) => scheduled.push(callback), setTimeout: (callback) => scheduled.push(callback) }
		: { queueMicrotask: undefined, setTimeout: (callback) => scheduled.push(callback) };
	const close = vm.runInContext(
		`(function(socket, openListener, closeListener, messageListener, errorListener) { ${closeScript} })`,
		vm.createContext(context),
	);
	return { close, scheduled };
}

function makeFakeAdapter(initialState, options = {}) {
	let state = initialState;
	const listeners = new Map();
	const removed = [];
	let closeCalls = 0;
	let closeAfterStack = true;
	let insideCallerStack = false;
	const dispatch = (type) => {
		for (const listener of [...(listeners.get(type) || [])]) listener({ type });
	};
	const socket = {
		get readyState() {
			if (options.throwReadyState) throw new Error('readyState getter failed');
			return state;
		},
		addEventListener(type, listener) {
			if (!listeners.has(type)) listeners.set(type, new Set());
			listeners.get(type).add(listener);
		},
		removeEventListener(type, listener) {
			removed.push(type);
			if (options.throwRemove) throw new Error('removeEventListener failed');
			listeners.get(type)?.delete(listener);
		},
		close() {
			closeCalls++;
			if (insideCallerStack) closeAfterStack = false;
			if (options.throwClose) throw new Error('close failed');
			state = 3;
			dispatch('close');
		},
		dispatch,
		get closeCalls() { return closeCalls; },
		get closeAfterStack() { return closeAfterStack; },
		get detachedListenerCount() {
			return [...listeners.values()].reduce((count, entries) => count + entries.size, 0);
		},
		get removedTypes() { return removed.slice(); },
		set insideCallerStack(value) { insideCallerStack = value; },
	};
	return socket;
}

function makeClientClose(socket, helper) {
	let closeStarted = false;
	let callbacks = 0;
	const listeners = ['open', 'close', 'message', 'error'].map((type) => {
		const listener = () => { if (!closeStarted) callbacks++; };
		socket.addEventListener(type, listener);
		return listener;
	});
	const close = () => {
		if (closeStarted) return;
		closeStarted = true;
		helper.close(socket, listeners[0], listeners[1], listeners[2], listeners[3]);
	};
	return { close, get callbacks() { return callbacks; }, get closeStarted() { return closeStarted; } };
}

test('fake adapter: OPEN and CONNECTING close once after the callback stack; repeated close detaches all listeners', () => {
	for (const state of [0, 1]) {
		const helper = makeCloseHelper();
		const socket = makeFakeAdapter(state);
		const client = makeClientClose(socket, helper);
		socket.insideCallerStack = true;
		client.close();
		client.close();
		assert.equal(socket.closeCalls, 0, `readyState ${state} remains open through caller stack`);
		assert.equal(socket.detachedListenerCount, 0, 'all four TeaVM callbacks are detached immediately');
		assert.deepEqual(socket.removedTypes.sort(), ['close', 'error', 'message', 'open']);
		socket.insideCallerStack = false;
		assert.equal(helper.scheduled.length, 1, 'repeated close queues only one adapter close');
		helper.scheduled.shift()();
		assert.equal(socket.closeCalls, 1);
		assert.equal(socket.closeAfterStack, true);
		assert.equal(client.callbacks, 0, 'synchronous close dispatch reaches no TeaVM callbacks');
		assert.equal(client.closeStarted, true);
	}
});

test('fake adapter: CLOSING and CLOSED sockets are detached but never closed again', () => {
	for (const state of [2, 3]) {
		const helper = makeCloseHelper();
		const socket = makeFakeAdapter(state);
		const client = makeClientClose(socket, helper);
		client.close();
		assert.equal(socket.detachedListenerCount, 0);
		helper.scheduled.shift()();
		assert.equal(socket.closeCalls, 0, `readyState ${state} skips duplicate close`);
		assert.equal(client.callbacks, 0);
	}
});

test('fake adapter: setTimeout fallback defers close when queueMicrotask is unavailable', () => {
	const helper = makeCloseHelper({ microtasks: false });
	const socket = makeFakeAdapter(1);
	const client = makeClientClose(socket, helper);
	client.close();
	assert.equal(socket.closeCalls, 0);
	helper.scheduled.shift()();
	assert.equal(socket.closeCalls, 1);
	assert.equal(client.callbacks, 0);
});

test('fake adapter: throwing listener removal, readyState getter, or close are contained', () => {
	const removalHelper = makeCloseHelper();
	const removalSocket = makeFakeAdapter(1, { throwRemove: true });
	const removalClient = makeClientClose(removalSocket, removalHelper);
	assert.doesNotThrow(() => removalClient.close());
	removalHelper.scheduled.shift()();
	assert.equal(removalSocket.closeCalls, 1);
	assert.equal(removalClient.callbacks, 0);

	const getterHelper = makeCloseHelper();
	const getterSocket = makeFakeAdapter(1, { throwReadyState: true });
	const getterClient = makeClientClose(getterSocket, getterHelper);
	getterClient.close();
	assert.doesNotThrow(() => getterHelper.scheduled.shift()());
	assert.equal(getterSocket.closeCalls, 0);

	const closeHelper = makeCloseHelper();
	const closeSocket = makeFakeAdapter(1, { throwClose: true });
	const closeClient = makeClientClose(closeSocket, closeHelper);
	closeClient.close();
	assert.doesNotThrow(() => closeHelper.scheduled.shift()());
	assert.equal(closeSocket.closeCalls, 1);
	assert.equal(closeClient.callbacks, 0);
});
