/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.junit.internal;

/**
 * A listener interface for observing the
 * execution of a test run.
 */
 public interface ITestRunListener {

	/* status identifiers */
 	public static final int STATUS_ERROR= 1;
 	public static final int STATUS_FAILURE= 2;
 
	public void testRunStarted(int testCount);
	public void testRunEnded(long elapsedTime);
	public void testRunStopped(long elapsedTime);
	public void testStarted(String testName);
	public void testEnded(String testName);
	public void testFailed(int status, String testName, String trace);	
}

