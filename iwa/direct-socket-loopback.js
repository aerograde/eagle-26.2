(function () {
  "use strict";

  function makePayload() {
    var out = new Uint8Array(32768);
    for (var i = 0; i < out.length; ++i) out[i] = (i * 73 + (i >>> 3) * 19 + 41) & 255;
    return out;
  }

  globalThis.runEaglerDirectSocketLoopback = async function (port) {
    port = port || 25567;
    if (typeof globalThis.TCPSocket !== "function") {
      throw new Error("TCPSocket is unavailable; launch the installed IWA, not the ordinary web build");
    }
    var expected = makePayload();
    var socket = new TCPSocket("127.0.0.1", port, { noDelay: true });
    var opened = await socket.opened;
    var writer = opened.writable.getWriter();
    var reader = opened.readable.getReader();
    try {
      for (var offset = 0, step = 1; offset < expected.length; step = step % 97 + 1) {
        var end = Math.min(expected.length, offset + step);
        await writer.write(expected.slice(offset, end));
        offset = end;
      }
      var actual = new Uint8Array(expected.length);
      var received = 0;
      while (received < actual.length) {
        var readResult = await reader.read();
        if (readResult.done) throw new Error("Loopback closed after " + received + " bytes");
        var count = Math.min(readResult.value.byteLength, actual.length - received);
        actual.set(readResult.value.subarray(0, count), received);
        received += count;
      }
      for (var i = 0; i < expected.length; ++i) {
        if (actual[i] !== expected[i]) throw new Error("Loopback mismatch at byte " + i);
      }
      var pendingRead = reader.read();
      await reader.cancel();
      var canceledRead = await pendingRead;
      if (!canceledRead.done) throw new Error("Loopback reader did not cancel");
    } finally {
      try { reader.releaseLock(); } catch (e) {}
      try { writer.releaseLock(); } catch (e) {}
      await socket.close();
    }
    var result = { pass: true, bytes: expected.length, port: port };
    globalThis.__eaglerDirectSocketLoopback = result;
    console.log("[IWA Direct Sockets] loopback parity passed", result);
    return result;
  };
})();
