package org.my.aotagent.internal;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

/**
 * internal implementation of the AOT agent
 */
public class AOTAgentImpl {

    private static Instrumentation inst = null;
    // this defaults to false but for full AOT compatibility it should default to true
    private static boolean retransform = false;

    public static void premain(String args, Instrumentation inst) {
        AOTAgentImpl.inst = inst;
        handleArgs(args);
        AOTAgentTransformer transformer = new AOTAgentTransformer();
        inst.addTransformer(transformer, true);
        if (retransform) {
            tryRetransform();
        }
    }

    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    /**
     * Process any arguments provided to the -javaagent option.
     * @param args a comma-separated sequence of arguments
     */
    private static void handleArgs(String args) {
        if (args != null) {
            String[] argsArray = args.split(",");
            for (String arg : argsArray) {
                switch (arg) {
                    case "retransform":
                        retransform = true;
                        break;
                    case "noretransform":
                        retransform = false;
                        break;
                    default:
                        throw new AOTAgentException("Unknown argument : " + arg);
                }
            }
        }
    }

    /**
     *  Check whether any of the classes we want to transform are  already loaded
     *  and if so retransform them
     */
    private static void tryRetransform() {
        // we only have one class to check and it will belong to the system loader
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
