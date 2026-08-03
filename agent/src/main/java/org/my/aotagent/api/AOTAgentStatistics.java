package org.my.aotagent.api;

/**
 * Public API class for the AOT Agent injected into a JDK bootstrap class.
 */
public class AOTAgentStatistics {
    private static int threadRunCount = 0;

    public static void incrementRunCount() {
        threadRunCount++;
    }
    public static synchronized void print() {
        System.out.format("Total Thread.run count: %8d\n", threadRunCount);
    }
}
