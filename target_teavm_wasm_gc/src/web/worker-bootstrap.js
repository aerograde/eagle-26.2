/*
 * Shared Wasm-GC bootstrap for the page and worker realms.
 * Workers reuse a page-compiled module and queue messages until main is ready. Server
 * workers receive the full image; mesh workers receive the DCE-stripped mesh image.
 */
(function () {
	"use strict";

	var isWorkerRealm = (typeof importScripts === "function") && (typeof document === "undefined");

	// Instantiate a precompiled module through TeaVM's import glue.
	function instantiateWasmGC(module, options) {
		options = options || {};
		var imports = {};
		var userExports = {};
		var controller = TeaVM.wasmGC.defaults(imports, userExports, options, module);
		if (typeof options.installImports !== "undefined") {
			options.installImports(imports);
		}
		return WebAssembly.instantiate(module, imports).then(function (instance) {
			controller.supplyExports(instance.exports);
			for (var key in instance.exports) {
				(function (v) {
					if (v instanceof WebAssembly.Global) {
						Object.defineProperty(userExports, key, { get: function () { return v.value; } });
					}
				})(instance.exports[key]);
			}
			return { exports: userExports, instance: instance, module: module };
		});
	}

	if (!isWorkerRealm) {
		// Page entry point.
		window.__eaglerWasmGCInstantiate = instantiateWasmGC;
		return;
	}

	// TeaVM may construct WeakReference(null), which native WeakRef rejects.
	(function () {
		var RealWeakRef = self.WeakRef;
		if (!RealWeakRef) { return; }
		self.WeakRef = class {
			constructor(t) { try { this._r = new RealWeakRef(t); } catch (e) { this._r = { deref: function () { return t; } }; } }
			deref() { return this._r.deref(); }
		};
	})();

	var queued = [];   // Messages received during instantiation.
	var booting = false;

	// The worker role is unknown until its queued role message is replayed.
	function postBootError(msg) {
		try { self.postMessage({ meta: "mesh-worker:compile-boot-error:wasm-bootstrap: " + msg }); } catch (e) {}
		try { self.postMessage({ meta: "server-worker:boot-error:wasm-bootstrap: " + msg }); } catch (e) {}
	}

	function errString(e) {
		try {
			var stack = e && e.stack ? String(e.stack).split("\n") : [];
			var stackLimit = self.__eaglerPerfEnabled === true ? 128 : 4;
			return (e && (e.name + ": " + e.message))
					+ (stack.length ? " | " + stack.slice(0, stackLimit).join(" <- ") : "");
		} catch (x) { return String(e); }
	}

	self.onmessage = function (ev) {
		var d = ev && ev.data;
		if (!booting && d && d.eaglerWasmBoot) {
			booting = true;
			bootWasm(d.eaglerWasmBoot);
		} else {
			// Replay startup messages after main installs the real handler.
			queued.push(ev);
		}
	};

	function bootWasm(boot) {
		try {
			self.__eaglerPerfEnabled = boot.perfDebug === true;
			self.__eaglerChunkUnloadHardCap = boot.chunkUnloadHardCap === true;
			if (self.__eaglerPerfEnabled === true) {
				// ErrorEvent.error is not exposed reliably to the parent Worker object in
				// Chromium. Capture the exception inside the worker realm so a recursive
				// Wasm stack retains all function frames in local diagnostic builds.
				self.addEventListener("error", function (evt) {
					try {
						console.error("[wasm-worker] uncaught: " + errString(evt && (evt.error || evt)));
					} catch (ignored) {}
				});
			}
			if (boot.logForward) {
				// Forward worker logs to the page when requested.
				(function () {
					var _l = console.log, _e = console.error, _w = console.warn;
					function fwd(tag, a) {
						try { self.postMessage({ meta: "wlog:" + tag + Array.prototype.join.call(a, " ") }); } catch (x) {}
					}
					console.log = function () { fwd("L:", arguments); _l.apply(console, arguments); };
					console.error = function () { fwd("E:", arguments); _e.apply(console, arguments); };
					console.warn = function () { fwd("W:", arguments); _w.apply(console, arguments); };
				})();
			}
			if (!boot.module) {
				postBootError("boot payload has no WebAssembly.Module");
				return;
			}
			// Single-file builds load the runtime before this script.
			if (typeof TeaVM === "undefined" || !TeaVM.wasmGC || !TeaVM.wasmGC.defaults) {
				importScripts(boot.runtime || "classes.wasm-runtime.js");
			}
			if (typeof TeaVM === "undefined" || !TeaVM.wasmGC || !TeaVM.wasmGC.defaults) {
				postBootError("runtime js did not define TeaVM.wasmGC.defaults");
				return;
			}
			console.log("[wasm-worker] runtime loaded, instantiating shared module...");
			instantiateWasmGC(boot.module, { installImports: function (i) {} }).then(function (tv) {
				var main = tv.exports && tv.exports.main;
				if (typeof main !== "function") {
					postBootError("no exports.main after instantiation");
					return;
				}
				console.log("[wasm-worker] instantiated; entering main(" + JSON.stringify(boot.args || ["_worker_process_"]) + ")");
				main(boot.args || ["_worker_process_"]);
				// main installs the worker handler synchronously.
				var q = queued;
				queued = [];
				for (var k = 0; k < q.length; ++k) {
					try { self.onmessage(q[k]); } catch (e) { console.error("[wasm-worker] replay handler threw: " + errString(e)); }
				}
			}).catch(function (e) {
				postBootError("instantiate/main failed: " + errString(e));
			});
		} catch (e) {
			postBootError("bootstrap threw: " + errString(e));
		}
	}
})();
