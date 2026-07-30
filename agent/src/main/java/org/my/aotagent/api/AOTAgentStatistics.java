package org.my.aotagent.api;

/**
 * Public API class for the AOT Agent injected into a JDK bootstrap class.
 */
public class AOTAgentStatistics {
    public static synchronized void print() {
        System.out.println("No agent statistics to report");
    }
}
