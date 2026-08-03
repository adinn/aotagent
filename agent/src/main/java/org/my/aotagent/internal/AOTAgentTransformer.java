package org.my.aotagent.internal;

import org.my.aotagent.api.AOTAgentStatistics;

import java.lang.classfile.*;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.AccessFlag;
import java.security.ProtectionDomain;

import static java.lang.constant.ConstantDescs.CD_void;

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
    private final static String API_PACKAGE = AOTAgentStatistics.class.getPackageName();
    private final static String API_CLASS_NAME = AOTAgentStatistics.class.getSimpleName();
    private final static ClassDesc API_CLASS_DESC = ClassDesc.of(API_PACKAGE, API_CLASS_NAME);
    private final static String INCREMENT_RUN_COUNT_METHOD_NAME = "incrementRunCount";
    private final static String PRINT_STATS_METHOD_NAME = "print";
    private final static MethodTypeDesc VOID_VOID_DESC = MethodTypeDesc.of(CD_void);

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
        // use JDK built-in class file transformer to print thread run stats
        // at the end of the main routine
        ClassTransform classTransform = (classBuilder, classElement) -> {
            if (classElement instanceof MethodModel method &&
                    method.methodName().equalsString("main") &&
                    method.flags().has(AccessFlag.PUBLIC) &&
                    method.flags().has(AccessFlag.STATIC)) {
                MethodModel methodModel = (MethodModel) classElement;
                // transformer replaces return instructions with a call out to a static API method
                CodeTransform codeTransform = (CodeBuilder codeBuilder, CodeElement codeElement) -> {
                    if (codeElement instanceof ReturnInstruction) {
                        // call out to API method to print run stats
                        codeBuilder.invoke(Opcode.INVOKESTATIC, API_CLASS_DESC, PRINT_STATS_METHOD_NAME, VOID_VOID_DESC, false);
                    }
                    codeBuilder.with(codeElement);
                };
                // apply the transform to the method code
                MethodTransform methodTransform = MethodTransform.transformingCode(codeTransform);
                classBuilder.transformMethod(methodModel, methodTransform);
            } else {
                classBuilder.with(classElement);  // leaves the element in place
            }
        };
        ClassFile cm = ClassFile.of();
        return cm.transformClass(cm.parse(classfileBuffer), classTransform);
    }

    public byte[]  doThreadTransform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        if (!AOTAgentImpl.IN_BOOTSTRAP) {
            System.err.format("Unable to transform bootstrap class %s", classBeingRedefined.getName());
            return null;
        }
        // use JDK built-in class file transformer to cont a successful exit
        // from Thread.run()
        ClassTransform classTransform = (classBuilder, classElement) -> {
            if (classElement instanceof MethodModel method &&
                    method.methodName().equalsString("run") &&
                    method.flags().has(AccessFlag.PUBLIC)) {
                MethodModel methodModel = (MethodModel) classElement;
                // transformer replaces return instructions with a call out to a static API method
                CodeTransform codeTransform = (CodeBuilder codeBuilder, CodeElement codeElement) -> {
                    if (codeElement instanceof ReturnInstruction) {
                        // call out to API method which may throw an exception
                        codeBuilder.invoke(Opcode.INVOKESTATIC, API_CLASS_DESC, INCREMENT_RUN_COUNT_METHOD_NAME, VOID_VOID_DESC, false);
                    }
                    codeBuilder.with(codeElement);
                };
                // apply the transform to the method code
                MethodTransform methodTransform = MethodTransform.transformingCode(codeTransform);
                classBuilder.transformMethod(methodModel, methodTransform);
            } else {
                classBuilder.with(classElement);  // leaves the element in place
            }
        };
        ClassFile cm = ClassFile.of();
        return cm.transformClass(cm.parse(classfileBuffer), classTransform);
    }
}
