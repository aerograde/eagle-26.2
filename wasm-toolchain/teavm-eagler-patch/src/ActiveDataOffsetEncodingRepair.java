/*
 * Copyright 2026 Eaglercraft contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.teavm.backend.wasm;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Re-encodes active data-segment offsets without making another module-sized
 * byte array. TeaVM's binary writer still owns the input array, but this class
 * validates it first and then streams the repaired representation directly to
 * the build target.
 */
final class ActiveDataOffsetEncodingRepair {
    private ActiveDataOffsetEncodingRepair() {
    }

    static void repair(byte[] wasm, OutputStream output) throws IOException {
        validate(wasm);

        output.write(wasm, 0, 8);
        var cursor = new int[] { 8 };
        while (cursor[0] < wasm.length) {
            var sectionId = wasm[cursor[0]++] & 0xFF;
            var sectionLength = readULEB32(wasm, cursor);
            var sectionStart = cursor[0];
            var sectionEnd = sectionStart + sectionLength;
            // validate() has already checked this, but keep the write pass
            // independently bounds-safe if this method is changed later.
            checkSectionBounds(wasm, sectionStart, sectionEnd);

            output.write(sectionId);
            if (sectionId == 11) {
                var repairedLength = transformDataSection(wasm, sectionStart, sectionEnd, null);
                writeULEB32(output, repairedLength);
                transformDataSection(wasm, sectionStart, sectionEnd, output);
            } else {
                writeULEB32(output, sectionLength);
                output.write(wasm, sectionStart, sectionLength);
            }
            cursor[0] = sectionEnd;
        }
    }

    private static void validate(byte[] wasm) throws IOException {
        if (wasm.length < 8 || wasm[0] != 0 || wasm[1] != 'a' || wasm[2] != 's' || wasm[3] != 'm') {
            throw new IllegalArgumentException("Invalid WebAssembly module header");
        }
        var cursor = new int[] { 8 };
        while (cursor[0] < wasm.length) {
            var sectionId = wasm[cursor[0]++] & 0xFF;
            var sectionLength = readULEB32(wasm, cursor);
            var sectionStart = cursor[0];
            var sectionEnd = sectionStart + sectionLength;
            checkSectionBounds(wasm, sectionStart, sectionEnd);
            if (sectionId == 11) {
                transformDataSection(wasm, sectionStart, sectionEnd, null);
            }
            cursor[0] = sectionEnd;
        }
    }

    private static void checkSectionBounds(byte[] wasm, int start, int end) {
        if (end < start || end > wasm.length) {
            throw new IllegalArgumentException("Invalid WebAssembly section length");
        }
    }

    /**
     * With a null output this is a validation and exact-size pass. Otherwise it
     * emits the same canonical representation as the former ByteArrayOutputStream
     * implementation. The return value is the emitted body length.
     */
    private static int transformDataSection(byte[] wasm, int start, int end, OutputStream output)
            throws IOException {
        var cursor = new int[] { start };
        var length = new int[] { 0 };
        var segmentCount = readULEB32(wasm, cursor);
        writeULEB32(output, segmentCount, length);
        for (var i = 0; i < segmentCount; ++i) {
            var flags = readULEB32(wasm, cursor);
            writeULEB32(output, flags, length);
            if (flags == 2) {
                writeULEB32(output, readULEB32(wasm, cursor), length);
            }
            if (flags == 0 || flags == 2) {
                if (cursor[0] >= end || (wasm[cursor[0]++] & 0xFF) != 0x41) {
                    throw new IllegalArgumentException("Unsupported active data offset expression");
                }
                writeByte(output, 0x41, length);
                var offset = readULEB32(wasm, cursor);
                writeSignedLEB32(output, offset, length);
                if (cursor[0] >= end || (wasm[cursor[0]++] & 0xFF) != 0x0B) {
                    throw new IllegalArgumentException("Unterminated active data offset expression");
                }
                writeByte(output, 0x0B, length);
            } else if (flags != 1) {
                throw new IllegalArgumentException("Unsupported WebAssembly data segment flags: " + flags);
            }
            var dataLength = readULEB32(wasm, cursor);
            writeULEB32(output, dataLength, length);
            if (dataLength < 0 || cursor[0] + dataLength < cursor[0] || cursor[0] + dataLength > end) {
                throw new IllegalArgumentException("Invalid WebAssembly data segment length");
            }
            writeBytes(output, wasm, cursor[0], dataLength, length);
            cursor[0] += dataLength;
        }
        if (cursor[0] != end) {
            throw new IllegalArgumentException("Trailing bytes in WebAssembly data section");
        }
        return length[0];
    }

    private static int readULEB32(byte[] data, int[] cursor) {
        var result = 0;
        for (var shift = 0; shift < 35; shift += 7) {
            if (cursor[0] >= data.length) {
                throw new IllegalArgumentException("Truncated unsigned LEB");
            }
            var digit = data[cursor[0]++] & 0xFF;
            result |= (digit & 0x7F) << shift;
            if ((digit & 0x80) == 0) {
                if (result < 0) {
                    throw new IllegalArgumentException("Unsigned LEB exceeds signed build limit");
                }
                return result;
            }
        }
        throw new IllegalArgumentException("Oversized unsigned LEB");
    }

    private static void writeULEB32(OutputStream output, int value) throws IOException {
        var ignoredLength = new int[] { 0 };
        writeULEB32(output, value, ignoredLength);
    }

    private static void writeULEB32(OutputStream output, int value, int[] length) throws IOException {
        do {
            var digit = value & 0x7F;
            value >>>= 7;
            writeByte(output, value == 0 ? digit : digit | 0x80, length);
        } while (value != 0);
    }

    private static void writeSignedLEB32(OutputStream output, int value, int[] length) throws IOException {
        while (true) {
            var digit = value & 0x7F;
            value >>= 7;
            var last = (value == 0 && (digit & 0x40) == 0)
                    || (value == -1 && (digit & 0x40) != 0);
            writeByte(output, last ? digit : digit | 0x80, length);
            if (last) {
                return;
            }
        }
    }

    private static void writeByte(OutputStream output, int value, int[] length) throws IOException {
        if (output != null) {
            output.write(value);
        }
        ++length[0];
    }

    private static void writeBytes(OutputStream output, byte[] data, int offset, int count, int[] length)
            throws IOException {
        if (output != null) {
            output.write(data, offset, count);
        }
        length[0] += count;
    }
}
