package org.teavm.hppc;

import java.util.Iterator;
import java.util.NoSuchElementException;

import org.teavm.hppc.cursors.IntCursor;
import org.teavm.hppc.predicates.IntPredicate;
import org.teavm.hppc.procedures.IntProcedure;

/**
 * Non-negative integer set used only for TeaVM's temporary pending type
 * indices. Most transitions carry only a handful of sparse type IDs, while a
 * minority become large. Keep small sets in a sorted array and promote large
 * sets to sparse 64-value blocks. This preserves exact membership and ascending
 * iteration order without allocating a dense bitmap up to the highest type ID.
 */
public final class CompactIntHashSet extends IntHashSet {

    private static final int SPARSE_LIMIT = 64;
    private int[] sparseValues;
    private int[] blockKeys;
    private long[] blockMasks;
    private int blockCount;
    private int compactSize;

    public CompactIntHashSet(int ignoredInitialCapacity) {
        super(0);
    }

    @Override
    public boolean add(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative dependency type index: " + value);
        }
        if (blockKeys != null) {
            int key = value >>> 6;
            int index = findBlock(key);
            long bit = 1L << value;
            if (index >= 0) {
                if ((blockMasks[index] & bit) != 0L) {
                    return false;
                }
                blockMasks[index] |= bit;
            } else {
                index = -index - 1;
                ensureBlockCapacity(blockCount + 1);
                if (index < blockCount) {
                    System.arraycopy(blockKeys, index, blockKeys, index + 1, blockCount - index);
                    System.arraycopy(blockMasks, index, blockMasks, index + 1, blockCount - index);
                }
                blockKeys[index] = key;
                blockMasks[index] = bit;
                ++blockCount;
            }
            ++compactSize;
            return true;
        }
        int index = findSparse(value);
        if (index >= 0) {
            return false;
        }
        index = -index - 1;
        if (compactSize == SPARSE_LIMIT) {
            promoteToBlocks();
            return add(value);
        }
        ensureSparseCapacity(compactSize + 1);
        if (index < compactSize) {
            System.arraycopy(sparseValues, index, sparseValues, index + 1, compactSize - index);
        }
        sparseValues[index] = value;
        ++compactSize;
        return true;
    }

    private void promoteToBlocks() {
        int[] values = sparseValues;
        int valueCount = compactSize;
        blockKeys = new int[Math.min(valueCount, 8)];
        blockMasks = new long[blockKeys.length];
        blockCount = 0;
        for (int i = 0; i < valueCount; ++i) {
            int value = values[i];
            int key = value >>> 6;
            if (blockCount == 0 || blockKeys[blockCount - 1] != key) {
                ensureBlockCapacity(blockCount + 1);
                blockKeys[blockCount] = key;
                blockMasks[blockCount] = 0L;
                ++blockCount;
            }
            blockMasks[blockCount - 1] |= 1L << value;
        }
        sparseValues = null;
    }

    private void ensureBlockCapacity(int expected) {
        if (blockKeys != null && blockKeys.length >= expected) {
            return;
        }
        int capacity = blockKeys == null ? 4 : blockKeys.length;
        while (capacity < expected) {
            capacity = capacity < 1024 ? capacity << 1 : capacity + (capacity >>> 1);
        }
        int[] replacementKeys = new int[capacity];
        long[] replacementMasks = new long[capacity];
        if (blockCount > 0) {
            System.arraycopy(blockKeys, 0, replacementKeys, 0, blockCount);
            System.arraycopy(blockMasks, 0, replacementMasks, 0, blockCount);
        }
        blockKeys = replacementKeys;
        blockMasks = replacementMasks;
    }

    private int findBlock(int key) {
        int low = 0;
        int high = blockCount - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int candidate = blockKeys[mid];
            if (candidate < key) {
                low = mid + 1;
            } else if (candidate > key) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -low - 1;
    }

    private boolean containsBlockValue(int value) {
        int index = findBlock(value >>> 6);
        return index >= 0 && (blockMasks[index] & (1L << value)) != 0L;
    }

    private void removeBlockAt(int index) {
        if (index < blockCount - 1) {
            System.arraycopy(blockKeys, index + 1, blockKeys, index, blockCount - index - 1);
            System.arraycopy(blockMasks, index + 1, blockMasks, index, blockCount - index - 1);
        }
        --blockCount;
    }

    private void removeBlockValue(int value) {
        int index = findBlock(value >>> 6);
        long mask = blockMasks[index] & ~(1L << value);
        if (mask == 0L) {
            removeBlockAt(index);
        } else {
            blockMasks[index] = mask;
        }
    }

    private int blockValue(int blockIndex, long mask) {
        return (blockKeys[blockIndex] << 6) + Long.numberOfTrailingZeros(mask);
    }

    private int firstBlockValue() {
        return blockCount > 0 ? blockValue(0, blockMasks[0]) : -1;
    }

    private int nextBlockValue(int value) {
        int index = findBlock(value >>> 6);
        if (index < 0) {
            index = -index - 1;
        } else {
            long mask = blockMasks[index] & (-1L << ((value & 63) + 1));
            if ((value & 63) == 63) {
                mask = 0L;
            }
            if (mask != 0L) {
                return blockValue(index, mask);
            }
            ++index;
        }
        return index < blockCount ? blockValue(index, blockMasks[index]) : -1;
    }

    @Override
    public int addAll(IntContainer container) {
        int added = 0;
        for (IntCursor cursor : container) {
            if (add(cursor.value)) {
                ++added;
            }
        }
        return added;
    }

    @Override
    public int addAll(Iterable<? extends IntCursor> iterable) {
        int added = 0;
        for (IntCursor cursor : iterable) {
            if (add(cursor.value)) {
                ++added;
            }
        }
        return added;
    }

    @Override
    public boolean contains(int value) {
        return value >= 0 && (blockKeys != null ? containsBlockValue(value) : findSparse(value) >= 0);
    }

    @Override
    public boolean remove(int value) {
        if (!contains(value)) {
            return false;
        }
        if (blockKeys != null) {
            removeBlockValue(value);
        } else {
            int index = findSparse(value);
            if (index < compactSize - 1) {
                System.arraycopy(sparseValues, index + 1, sparseValues, index, compactSize - index - 1);
            }
        }
        --compactSize;
        return true;
    }

    @Override
    public int removeAll(int value) {
        return remove(value) ? 1 : 0;
    }

    @Override
    public int removeAll(IntPredicate predicate) {
        int removed = 0;
        if (blockKeys != null) {
            int write = 0;
            for (int read = 0; read < blockCount; ++read) {
                long sourceMask = blockMasks[read];
                long retainedMask = sourceMask;
                while (sourceMask != 0L) {
                    long bit = sourceMask & -sourceMask;
                    int value = blockValue(read, bit);
                    if (predicate.apply(value)) {
                        retainedMask &= ~bit;
                        --compactSize;
                        ++removed;
                    }
                    sourceMask ^= bit;
                }
                if (retainedMask != 0L) {
                    blockKeys[write] = blockKeys[read];
                    blockMasks[write] = retainedMask;
                    ++write;
                }
            }
            blockCount = write;
        } else {
            int write = 0;
            for (int read = 0; read < compactSize; ++read) {
                int value = sparseValues[read];
                if (predicate.apply(value)) {
                    ++removed;
                } else {
                    sparseValues[write++] = value;
                }
            }
            compactSize = write;
        }
        return removed;
    }

    @Override
    public void clear() {
        blockKeys = null;
        blockMasks = null;
        blockCount = 0;
        sparseValues = null;
        compactSize = 0;
    }

    @Override
    public void release() {
        clear();
    }

    @Override
    public boolean isEmpty() {
        return compactSize == 0;
    }

    @Override
    public void ensureCapacity(int expectedElements) {
        if (blockKeys == null && expectedElements > 0) {
            ensureSparseCapacity(Math.min(expectedElements, SPARSE_LIMIT));
        }
    }

    @Override
    public int size() {
        return compactSize;
    }

    @Override
    public int[] toArray() {
        int[] result = new int[compactSize];
        if (blockKeys == null) {
            if (compactSize > 0) {
                System.arraycopy(sparseValues, 0, result, 0, compactSize);
            }
            return result;
        }
        int offset = 0;
        for (int block = 0; block < blockCount; ++block) {
            long mask = blockMasks[block];
            while (mask != 0L) {
                long bit = mask & -mask;
                result[offset++] = blockValue(block, bit);
                mask ^= bit;
            }
        }
        return result;
    }

    @Override
    public Iterator<IntCursor> iterator() {
        return new Iterator<IntCursor>() {
            private final IntCursor cursor = new IntCursor();
            private int next = blockKeys != null ? firstBlockValue()
                    : compactSize > 0 ? sparseValues[0] : -1;
            private int ordinal;

            @Override
            public boolean hasNext() {
                return next >= 0;
            }

            @Override
            public IntCursor next() {
                if (next < 0) {
                    throw new NoSuchElementException();
                }
                cursor.index = ordinal++;
                cursor.value = next;
                next = blockKeys != null ? nextBlockValue(next)
                        : ordinal < compactSize ? sparseValues[ordinal] : -1;
                return cursor;
            }
        };
    }

    @Override
    public <T extends IntProcedure> T forEach(T procedure) {
        if (blockKeys != null) {
            for (int block = 0; block < blockCount; ++block) {
                long mask = blockMasks[block];
                while (mask != 0L) {
                    long bit = mask & -mask;
                    procedure.apply(blockValue(block, bit));
                    mask ^= bit;
                }
            }
        } else {
            for (int i = 0; i < compactSize; ++i) {
                procedure.apply(sparseValues[i]);
            }
        }
        return procedure;
    }

    @Override
    public <T extends IntPredicate> T forEach(T predicate) {
        if (blockKeys != null) {
            outer: for (int block = 0; block < blockCount; ++block) {
                long mask = blockMasks[block];
                while (mask != 0L) {
                    long bit = mask & -mask;
                    if (!predicate.apply(blockValue(block, bit))) {
                        break outer;
                    }
                    mask ^= bit;
                }
            }
        } else {
            for (int i = 0; i < compactSize; ++i) {
                if (!predicate.apply(sparseValues[i])) {
                    break;
                }
            }
        }
        return predicate;
    }

    @Override
    public String visualizeKeyDistribution(int characters) {
        return (blockKeys != null ? "SparseBlocks[" + blockCount + "," : "Sparse[") + compactSize + "]";
    }

    @Override
    public long ramBytesAllocated() {
        return 40L + (blockKeys != null
                ? 32L + (long) blockKeys.length * 4L + (long) blockMasks.length * 8L
                : sparseValues == null ? 0L : 16L + (long) sparseValues.length * 4L);
    }

    @Override
    public long ramBytesUsed() {
        return 40L + (blockKeys != null ? 32L + (long) blockCount * 12L
                : 16L + (long) compactSize * 4L);
    }

    private int findSparse(int value) {
        int low = 0;
        int high = compactSize - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int candidate = sparseValues[mid];
            if (candidate < value) {
                low = mid + 1;
            } else if (candidate > value) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -low - 1;
    }

    private void ensureSparseCapacity(int expected) {
        if (sparseValues != null && sparseValues.length >= expected) {
            return;
        }
        int capacity = sparseValues == null ? 4 : sparseValues.length;
        while (capacity < expected) {
            capacity = Math.min(SPARSE_LIMIT, capacity << 1);
        }
        int[] replacement = new int[capacity];
        if (compactSize > 0) {
            System.arraycopy(sparseValues, 0, replacement, 0, compactSize);
        }
        sparseValues = replacement;
    }
}
