package org.my.aotagent.internal;

/**
 * An exception class used by the agent to notify unrecoverable problems.
 */
public class AOTAgentException extends RuntimeException{
    public AOTAgentException(String s) {
        super(s);
    }
}
