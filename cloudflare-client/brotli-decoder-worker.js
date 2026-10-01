import initBrotli, * as brotli from "./brotli_dec_wasm.js";

const brotliReady = initBrotli().then(() => brotli);

self.onmessage = async function (event) {
	const { id, url } = event.data;
	let decoder = null;
	try {
		const [codec, compressed] = await Promise.all([brotliReady, fetch(url)]);
		if (!compressed.ok || !compressed.body) {
			throw new Error("Unable to download " + url + ": HTTP " + compressed.status);
		}

		decoder = new codec.DecompressStream();
		const reader = compressed.body.getReader();
		let finished = false;
		while (!finished) {
			const read = await reader.read();
			if (read.done) break;
			const chunk = read.value;
			let offset = 0;
			let code = codec.BrotliStreamResultCode.NeedsMoreOutput;
			while (!finished && (offset < chunk.byteLength ||
					code === codec.BrotliStreamResultCode.NeedsMoreOutput)) {
				const result = decoder.decompress(chunk.subarray(offset), 262144);
				offset += result.input_offset;
				code = result.code;
				if (result.buf.byteLength) {
					const output = result.buf;
					self.postMessage({ id, chunk: output.buffer }, [output.buffer]);
				}
				if (code === codec.BrotliStreamResultCode.ResultSuccess) {
					finished = true;
				} else if (code !== codec.BrotliStreamResultCode.NeedsMoreInput &&
						code !== codec.BrotliStreamResultCode.NeedsMoreOutput) {
					throw new Error("Brotli decompression failed with code " + code);
				}
			}
		}
		if (!finished) throw new Error("Truncated Brotli Wasm payload: " + url);
		self.postMessage({ id, done: true });
	} catch (error) {
		self.postMessage({ id, error: String(error && error.message || error) });
	} finally {
		if (decoder) decoder.free();
	}
};
