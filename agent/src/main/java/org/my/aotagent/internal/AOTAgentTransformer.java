package org.my.aotagent.internal;

import org.my.aotagent.api.AOTAgentStatistics;
import org.objectweb.asm.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * A transformer that applies one simple transformations.
 *
 * 1. HelloAgent.main(): RETURN -> INVOKESTATIC AOTAgentStatistics.printStats() ; RETURN.
 *
 * The transformation is performed unconditionally using the JDK's builtin class bytecode
 * manipulation library. The owner of the injected method target is an API class exported by
 * the AOT agent jar in its api subpackage.
 */
public class AOTAgentTransformer implements ClassFileTransformer {
    // constants needed to inject an INVOKE into HelloAgent.main
    private final static String API_CLASS_NAME = Type.getInternalName(AOTAgentStatistics.class);
    private final static String INCREMENT_RUN_COUNT_METHOD_NAME = "incrementRunCount";
    private final static String PRINT_STATS_METHOD_NAME = "print";
    private final static String VOID_VOID_DESC = Type.getMethodDescriptor(Type.getType(void.class));

    public AOTAgentTransformer() {
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        return transform(null, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        switch (className) {
            case "HelloAgent":
                return doHelloTransform(module, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
            case "java/lang/Thread":
                return doThreadTransform(module, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
            default:
                return null;
        }
    }
    public byte[]  doHelloTransform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        // use an ASM class visitor to print thread run stats
        // at the end of the main routine
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("main") ||
                        (access & Opcodes.ACC_PUBLIC)  == 0 ||
                        (access & Opcodes.ACC_STATIC)  == 0) {
                    return mv;
                } else {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitInsn(final int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                // print stats before returning
                                visitMethodInsn(Opcodes.INVOKESTATIC, API_CLASS_NAME, PRINT_STATS_METHOD_NAME, VOID_VOID_DESC, false);
                                mv.visitInsn(opcode);
                            } else {
                                mv.visitInsn(opcode);
                            }
                        }
                    };
                }
            }
        };
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        dumpBytes(className, cw.toByteArray());
        return cw.toByteArray();
    }

    public byte[]  doThreadTransform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        if (!AOTAgentImpl.IN_BOOTSTRAP) {
            System.err.format("Unable to transform bootstrap class %s\n", classBeingRedefined.getName());
            return null;
        }
        // use an ASM class visitor to count a successful exit
        // from Thread.run()
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("run") || !descriptor.equals(VOID_VOID_DESC)) {
                    return mv;
                } else {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitInsn(final int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                // ensure the agent counts a successful return
                                visitMethodInsn(Opcodes.INVOKESTATIC, API_CLASS_NAME, INCREMENT_RUN_COUNT_METHOD_NAME, VOID_VOID_DESC, false);
                                mv.visitInsn(opcode);
                            } else {
                                mv.visitInsn(opcode);
                            }
                        }
                    };
                }
            }
        };
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        dumpBytes(className, cw.toByteArray());
        return cw.toByteArray();
    }

    private void dumpBytes(String className, byte[] bytes) {
        StringBuilder b = new StringBuilder();
        b.append("dump").append(File.separator);
        b.append(className.replace('/', File.separatorChar)).append(".class");
        try {
            File filePath = new File(b.toString()).getAbsoluteFile();
            File dirPath = filePath.getParentFile();
            dirPath.mkdirs();
            FileOutputStream fos = new FileOutputStream(filePath);
            fos.write(bytes);
            fos.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
