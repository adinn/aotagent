package org.my.aotagent.main;

import org.my.aotagent.internal.AOTAgentException;
import org.my.aotagent.internal.AOTAgentImpl;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * Special, one-off, public API class for the AOT Agent provided as a
 * for target for the -javagent command
 */
public class AOTAgentMain {
    public static void premain(String args, Instrumentation inst) {
        AOTAgentImpl.premain(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        AOTAgentImpl.agentmain(args, inst);
    }
}