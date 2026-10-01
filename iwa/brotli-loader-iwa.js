let decoderWorker = null;
let nextRequestId = 1;
let activeRequests = 0;
const requests = new Map();

const decoderWorkerURL = (() => {
  const url = new URL("brotli-decoder-worker.js", import.meta.url).href;
  if (!globalThis.trustedTypes) return url;
  return globalThis.trustedTypes.createPolicy("eagler-brotli-worker", {
    createScriptURL(value) {
      const resolved = new URL(value, import.meta.url);
      if (resolved.origin !== location.origin) throw new TypeError("Cross-origin decoder rejected");
      return resolved.href;
    }
  }).createScriptURL(url);
})();

function ensureWorker() {
  if (decoderWorker) return decoderWorker;
  decoderWorker = new Worker(decoderWorkerURL, { type: "module" });
  decoderWorker.onmessage = function(event) {
    const message = event.data || {};
    const request = requests.get(message.id);
    if (!request) return;
    if (message.chunk) {
      request.controller.enqueue(new Uint8Array(message.chunk));
      return;
    }
    if (message.done) {
      request.controller.close();
      finishRequest(message.id);
    } else if (message.error) {
      request.controller.error(new Error(message.error));
      finishRequest(message.id);
    }
  };
  decoderWorker.onerror = function(event) {
    const error = new Error(event.message || "Brotli decoder worker failed");
    for (const request of requests.values()) request.controller.error(error);
    requests.clear();
    activeRequests = 0;
    decoderWorker.terminate();
    decoderWorker = null;
  };
  return decoderWorker;
}

function finishRequest(id) {
  requests.delete(id);
  --activeRequests;
  if (activeRequests === 0 && decoderWorker) {
    decoderWorker.terminate();
    decoderWorker = null;
  }
}

window.__eagFetchBrotliWasm = function(url) {
  const id = nextRequestId++;
  ++activeRequests;
  const stream = new ReadableStream({
    start(controller) {
      requests.set(id, { controller });
      ensureWorker().postMessage({ id, url });
    },
    cancel() {
      if (requests.has(id)) finishRequest(id);
    }
  });
  return Promise.resolve(new Response(stream, {
    headers: { "Content-Type": "application/wasm" }
  }));
};
