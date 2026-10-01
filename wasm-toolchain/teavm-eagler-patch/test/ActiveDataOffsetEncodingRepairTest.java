package org.teavm.backend.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ActiveDataOffsetEncodingRepairTest {
    private static final byte[] HEADER = { 0, 'a', 's', 'm', 1, 0, 0, 0 };
    private static int assertions;

    public static void main(String[] args) throws Exception {
        equivalence("empty", HEADER);
        equivalence("ordinary-active", module(section(1, bytes(0x60)), dataSection(active(1, 3))));
        equivalence("signed-offset-boundaries", module(dataSection(
                active(0, 0), active(63, 0), active(64, 0), active(8191, 0),
                active(8192, 0), active(1_057_229, 3))));
        equivalence("mixed-segment-kinds", module(dataSection(
                active(64, 2), passive(4), activeWithMemory(3, 8192, 5))));

        // Re-encoding offset 64 adds one byte. Exercise both outer section-size
        // ULEB transitions that are realistic for generated modules.
        boundaryEquivalence(121, 127, 128);
        boundaryEquivalence(16_376, 16_383, 16_384);

        // The old routine canonicalized outer and data-section ULEBs. Ensure the
        // streaming implementation retains that byte-for-byte behavior.
        equivalence("noncanonical-input-lebs", moduleRaw(concat(
                bytes(11, 0x89, 0x00),
                bytes(0x81, 0x00, 0x80, 0x00, 0x41, 0xC0, 0x00, 0x0B, 0x00))));

        sameFailure("invalid-header", bytes(0, 1, 2));
        sameFailure("invalid-section-length", moduleRaw(bytes(1, 1)));
        sameFailure("unsupported-flags", module(dataSectionRaw(bytes(1, 3))));
        sameFailure("unsupported-expression", module(dataSectionRaw(bytes(1, 0, 0x42))));
        sameFailure("unterminated-expression", module(dataSectionRaw(bytes(1, 0, 0x41, 0, 0))));
        sameFailure("invalid-data-length", module(dataSectionRaw(bytes(1, 1, 4, 1, 2))));
        sameFailure("trailing-data-bytes", module(dataSectionRaw(bytes(0, 1))));
        sameFailure("truncated-uleb", moduleRaw(bytes(1, 0x80)));
        sameFailure("oversized-uleb", moduleRaw(bytes(1, 0x80, 0x80, 0x80, 0x80, 0x80)));
        sameFailure("unsigned-leb-limit", moduleRaw(bytes(1, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F)));

        System.out.println("PASS ActiveDataOffsetEncodingRepairTest assertions=" + assertions);
    }

    private static void boundaryEquivalence(int payloadLength, int oldBodyLength, int newBodyLength)
            throws Exception {
        var input = module(dataSection(active(64, payloadLength)));
        assertEquals(oldBodyLength, dataSectionLength(input), "old data-section body length");
        var actual = repaired(input);
        assertEquals(newBodyLength, dataSectionLength(actual), "new data-section body length");
        assertArrayEquals(legacyRepair(input), actual, "boundary " + oldBodyLength + "->" + newBodyLength);
    }

    private static void equivalence(String name, byte[] input) throws Exception {
        assertArrayEquals(legacyRepair(input), repaired(input), name);
    }

    private static void sameFailure(String name, byte[] input) throws Exception {
        var expected = failure(() -> legacyRepair(input));
        var output = new ByteArrayOutputStream();
        var actual = failure(() -> ActiveDataOffsetEncodingRepair.repair(input, output));
        assertEquals(expected.getClass(), actual.getClass(), name + " exception class");
        assertEquals(expected.getMessage(), actual.getMessage(), name + " exception message");
        assertEquals(0, output.size(), name + " emits no partial module");
    }

    private static byte[] repaired(byte[] input) throws Exception {
        var output = new ByteArrayOutputStream();
        ActiveDataOffsetEncodingRepair.repair(input, output);
        return output.toByteArray();
    }

    private static RuntimeException failure(ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("expected failure");
    }

    private static byte[] module(byte[]... sections) {
        return moduleRaw(concat(sections));
    }

    private static byte[] moduleRaw(byte[] contents) {
        return concat(HEADER, contents);
    }

    private static byte[] section(int id, byte[] body) {
        return concat(bytes(id), uleb(body.length), body);
    }

    private static byte[] dataSection(byte[]... segments) {
        return dataSectionRaw(concat(uleb(segments.length), concat(segments)));
    }

    private static byte[] dataSectionRaw(byte[] body) {
        return section(11, body);
    }

    private static byte[] active(int offset, int payloadLength) {
        return concat(uleb(0), bytes(0x41), uleb(offset), bytes(0x0B),
                uleb(payloadLength), new byte[payloadLength]);
    }

    private static byte[] passive(int payloadLength) {
        return concat(uleb(1), uleb(payloadLength), new byte[payloadLength]);
    }

    private static byte[] activeWithMemory(int memory, int offset, int payloadLength) {
        return concat(uleb(2), uleb(memory), bytes(0x41), uleb(offset), bytes(0x0B),
                uleb(payloadLength), new byte[payloadLength]);
    }

    private static int dataSectionLength(byte[] module) {
        var cursor = new int[] { 8 };
        while (cursor[0] < module.length) {
            var id = module[cursor[0]++] & 0xFF;
            var length = readULEB32(module, cursor);
            if (id == 11) {
                return length;
            }
            cursor[0] += length;
        }
        throw new AssertionError("no data section");
    }

    private static byte[] bytes(int... values) {
        var result = new byte[values.length];
        for (var i = 0; i < values.length; ++i) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static byte[] concat(byte[]... arrays) {
        var length = 0;
        for (var array : arrays) {
            length += array.length;
        }
        var result = new byte[length];
        var cursor = 0;
        for (var array : arrays) {
            System.arraycopy(array, 0, result, cursor, array.length);
            cursor += array.length;
        }
        return result;
    }

    private static byte[] uleb(int value) {
        var output = new ByteArrayOutputStream();
        writeULEB32(output, value);
        return output.toByteArray();
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String message) {
        ++assertions;
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + Arrays.toString(expected)
                    + " actual=" + Arrays.toString(actual));
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        ++assertions;
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    // Frozen copy of the replaced implementation. It is intentionally local to
    // this test so byte equivalence and malformed-input behavior are independent
    // of the new streaming code.
    private static byte[] legacyRepair(byte[] wasm) {
        if (wasm.length < 8 || wasm[0] != 0 || wasm[1] != 'a' || wasm[2] != 's' || wasm[3] != 'm') {
            throw new IllegalArgumentException("Invalid WebAssembly module header");
        }
        var output = new ByteArrayOutputStream(wasm.length + 16);
        output.write(wasm, 0, 8);
        var cursor = new int[] { 8 };
        while (cursor[0] < wasm.length) {
            var sectionId = wasm[cursor[0]++] & 0xFF;
            var sectionLength = readULEB32(wasm, cursor);
            var sectionStart = cursor[0];
            var sectionEnd = sectionStart + sectionLength;
            if (sectionEnd < sectionStart || sectionEnd > wasm.length) {
                throw new IllegalArgumentException("Invalid WebAssembly section length");
            }
            byte[] body;
            if (sectionId == 11) {
                body = legacyRepairDataSection(wasm, sectionStart, sectionEnd);
            } else {
                body = Arrays.copyOfRange(wasm, sectionStart, sectionEnd);
            }
            output.write(sectionId);
            writeULEB32(output, body.length);
            output.write(body, 0, body.length);
            cursor[0] = sectionEnd;
        }
        return output.toByteArray();
    }

    private static byte[] legacyRepairDataSection(byte[] wasm, int start, int end) {
        var cursor = new int[] { start };
        var output = new ByteArrayOutputStream(end - start + 8);
        var segmentCount = readULEB32(wasm, cursor);
        writeULEB32(output, segmentCount);
        for (var i = 0; i < segmentCount; ++i) {
            var flags = readULEB32(wasm, cursor);
            writeULEB32(output, flags);
            if (flags == 2) {
                writeULEB32(output, readULEB32(wasm, cursor));
            }
            if (flags == 0 || flags == 2) {
                if (cursor[0] >= end || (wasm[cursor[0]++] & 0xFF) != 0x41) {
                    throw new IllegalArgumentException("Unsupported active data offset expression");
                }
                output.write(0x41);
                writeSignedLEB32(output, readULEB32(wasm, cursor));
                if (cursor[0] >= end || (wasm[cursor[0]++] & 0xFF) != 0x0B) {
                    throw new IllegalArgumentException("Unterminated active data offset expression");
                }
                output.write(0x0B);
            } else if (flags != 1) {
                throw new IllegalArgumentException("Unsupported WebAssembly data segment flags: " + flags);
            }
            var dataLength = readULEB32(wasm, cursor);
            writeULEB32(output, dataLength);
            if (dataLength < 0 || cursor[0] + dataLength < cursor[0] || cursor[0] + dataLength > end) {
                throw new IllegalArgumentException("Invalid WebAssembly data segment length");
            }
            output.write(wasm, cursor[0], dataLength);
            cursor[0] += dataLength;
        }
        if (cursor[0] != end) {
            throw new IllegalArgumentException("Trailing bytes in WebAssembly data section");
        }
        return output.toByteArray();
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

    private static void writeULEB32(ByteArrayOutputStream output, int value) {
        do {
            var digit = value & 0x7F;
            value >>>= 7;
            output.write(value == 0 ? digit : digit | 0x80);
        } while (value != 0);
    }

    private static void writeSignedLEB32(ByteArrayOutputStream output, int value) {
        while (true) {
            var digit = value & 0x7F;
            value >>= 7;
            var last = (value == 0 && (digit & 0x40) == 0)
                    || (value == -1 && (digit & 0x40) != 0);
            output.write(last ? digit : digit | 0x80);
            if (last) {
                return;
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
