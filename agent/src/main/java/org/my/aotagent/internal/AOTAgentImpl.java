package org.my.aotagent.internal;

import org.my.aotagent.main.AOTAgentMain;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

/**
 * internal implementation of the AOT agent
 */
public class AOTAgentImpl {

    private static Instrumentation inst = null;
    // we can only perform transformations for bootstrap classes
    // when agent classes have been loaded by the bootstrap loader
    protected static boolean IN_BOOTSTRAP = AOTAgentImpl.class.getClassLoader() == null;

    public static void premain(String args, Instrumentation inst) {
        AOTAgentImpl.inst = inst;
        AOTAgentTransformer transformer = new AOTAgentTransformer();
        inst.addTransformer(transformer, true);
        tryRetransform();
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    /**
     *  Check whether any of the classes we want to transform are  already loaded
     *  and if so retransform them
     */
    private static void tryRetransform() {
        // class Thread will always be loaded so force a transform
        try {
            inst.retransformClasses(Thread.class);
        } catch (UnmodifiableClassException e) {
            throw new AOTAgentException("Unable to retransform already loaded class " + Thread.class.getName());
        }
        // we only have one other class to check and it will belong to the system loader
        for (Class<?> clazz : inst.getInitiatedClasses(ClassLoader.getSystemClassLoader())) {
            if (clazz.getName().equals("HelloAgent")) {
                try {
                    inst.retransformClasses(clazz);
                } catch (UnmodifiableClassException e) {
                    throw new AOTAgentException("Unable to retransform already loaded class " + clazz.getName());
                }
            }
        }
    }
}
