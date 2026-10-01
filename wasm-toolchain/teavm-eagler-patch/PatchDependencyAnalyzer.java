import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.teavm.asm.ClassReader;
import org.teavm.asm.ClassVisitor;
import org.teavm.asm.ClassWriter;
import org.teavm.asm.MethodVisitor;
import org.teavm.asm.Opcodes;

/**
 * Losslessly replaces TeaVM's transient pending-type sets with an adaptive
 * sparse/blocked representation. Precise dependency propagation and every
 * inserted type stay unchanged.
 */
public final class PatchDependencyAnalyzer {

    private static final String ENTRY = "org/teavm/dependency/DependencyAnalyzer.class";
    private static final String SET_TYPE = "org/teavm/hppc/IntHashSet";
    private static final String COMPACT_SET_TYPE = "org/teavm/hppc/CompactIntHashSet";
    private static final int STOCK_INITIAL_CAPACITY = 50;
    private static final int COMPACT_INITIAL_CAPACITY = 8;

    private PatchDependencyAnalyzer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: PatchDependencyAnalyzer <teavm-core.jar> <output.class>");
        }
        byte[] input;
        try (ZipFile zip = new ZipFile(args[0])) {
            ZipEntry entry = zip.getEntry(ENTRY);
            if (entry == null) {
                throw new IllegalStateException("missing " + ENTRY + " in " + args[0]);
            }
            try (InputStream stream = zip.getInputStream(entry)) {
                input = stream.readAllBytes();
            }
        }

        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(0);
        int[] capacityPatches = { 0 };
        int[] constructorPatches = { 0 };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean target = name.equals("schedulePropagation")
                        && (descriptor.equals("(Lorg/teavm/dependency/Transition;Lorg/teavm/dependency/DependencyType;)V")
                        || descriptor.equals("(Lorg/teavm/dependency/Transition;[Lorg/teavm/dependency/DependencyType;)V"));
                if (!target) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private boolean awaitingInitialCapacity;
                    private boolean awaitingConstructor;

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW && SET_TYPE.equals(type)) {
                            awaitingInitialCapacity = true;
                            awaitingConstructor = true;
                            type = COMPACT_SET_TYPE;
                        }
                        super.visitTypeInsn(opcode, type);
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        if (awaitingInitialCapacity && opcode == Opcodes.BIPUSH
                                && operand == STOCK_INITIAL_CAPACITY) {
                            operand = COMPACT_INITIAL_CAPACITY;
                            awaitingInitialCapacity = false;
                            ++capacityPatches[0];
                        }
                        super.visitIntInsn(opcode, operand);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                            String methodDescriptor, boolean isInterface) {
                        if (awaitingConstructor && opcode == Opcodes.INVOKESPECIAL
                                && owner.equals(SET_TYPE) && methodName.equals("<init>")) {
                            owner = COMPACT_SET_TYPE;
                            awaitingConstructor = false;
                            ++constructorPatches[0];
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }
                };
            }
        }, 0);
        if (capacityPatches[0] != 2 || constructorPatches[0] != 2) {
            throw new IllegalStateException("expected 2 pending-set patches, found capacity="
                    + capacityPatches[0] + " constructor=" + constructorPatches[0]);
        }
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
        System.out.println("[patch] DependencyAnalyzer pending sets: adaptive sparse/blocked implementation (2 exact sites)");
    }
}
