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
        // if we need to hoist the jar into the bootstrap classpath then
        // we have to do it before we load any other classes from the jar.
        // So, we process that argument up front rather than passing it on
        // to the agent.
        String newArgs = checkHoist(args);
        if (newArgs != args) {
            // The check found a hoist argument and removed it
            doHoist(inst);
        }
        AOTAgentImpl.premain(newArgs, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        AOTAgentImpl.agentmain(args, inst);
    }

    private static String checkHoist(String args) {
        if (args == null || args.equals("hoist")) {
            return null;
        }
        if (args.indexOf("hoist") < 0) {
            return args;
        }
        String[] argArray = args.split(",");
        for (int i = 0; i < argArray.length; i++) {
            if (argArray[i].equals("hoist")) {
                StringBuilder builder = new StringBuilder();
                String separator = "";
                for (int j = 0; j < argArray.length; j++) {
                    if (j != i) {
                        builder.append(separator);
                        builder.append(argArray[j]);
                        separator = ",";
                    }
                }
                return builder.toString();
            }
            // we can drop off the end if "hoist" is embedded in
            // some other argument (or surrounded by white space).
            // let the agent handle it.
        }
        return args;
    }

    private static void doHoist(Instrumentation inst) throws AOTAgentException {
        // use the -javaagent Main class to find the jar on disk
        URL agentURL = AOTAgentMain.class.getProtectionDomain().getCodeSource().getLocation();
        if (agentURL == null || !"file".equals(agentURL.getProtocol())) {
            throw new AOTAgentException("Failed to find an agent jar URL " + agentURL);
        }
        // we have a file URL with an embedded entry path so look up the file part
        // between the ":" and the "!" separator
        String agentFilePath = null;
        try {
            agentFilePath = agentURL.getPath();
        } catch (Exception e) {
            throw new AOTAgentException("Failed to find an agent jar file path " + agentURL);
        }

        File agentFile = new File(agentFilePath);

        if (!agentFile.isFile()) {
            throw new AOTAgentException("Failed to find agent jar file using path " + agentFile.getAbsolutePath());
        }

        JarFile agentJar = null;
        try {
            agentJar = new JarFile(agentFile, false);
        } catch (IOException e) {
            throw new AOTAgentException("Failed to create jar from agent file " + agentFile.getAbsolutePath());
        }
        inst.appendToBootstrapClassLoaderSearch(agentJar);
    }
}