/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.slider.server.services.workflow;

import org.apache.hadoop.service.ServiceStateException;
import org.apache.slider.core.main.ServiceLaunchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service wrapper for an external program that is launched and can/will terminate.
 * This service is notified when the subprocess terminates, and stops itself 
 * and converts a non-zero exit code into a failure exception.
 * 
 * <p>
 * Key Features:
 * <ol>
 *   <li>The property {@link #executionTimeout} can be set to set a limit
 *   on the duration of a process</li>
 *   <li>Output is streamed to the output logger provided</li>.
 *   <li>The most recent lines of output are saved to a linked list</li>.
 *   <li>A synchronous callback, {@link LongLivedProcessLifecycleEvent}, is raised on the start
 *   and finish of a process.</li>
 * </ol>
 *
 * Usage:
 * <p></p>
 * The service can be built in the constructor, {@link #ForkedProcessService(String, Map, List)},
 * or have its simple constructor used to instantiate the service, then the 
 * {@link #build(Map, List)} command used to define the environment variables
 * and list of commands to execute. One of these two options MUST be exercised
 * before calling the services's {@link #start()} method.
 * <p></p>
 * The forked process is executed in the service's {@link #serviceStart()} method;
 * if still running when the service is stopped, {@link #serviceStop()} will
 * attempt to stop it.
 * <p></p>
 * 
 * The service delegates process execution to {@link LongLivedProcess},
 * receiving callbacks via the {@link LongLivedProcessLifecycleEvent}.
 * When the service receives a callback notifying that the process has completed,
 * it calls its {@link #stop()} method. If the error code was non-zero, 
 * the service is logged as having failed.
 */
public class ForkedProcessService
    extends WorkflowExecutorService<ExecutorService>
    implements LongLivedProcessLifecycleEvent, Runnable {

  /**
   * Log for the forked master process
   */
  private static final Logger LOG =
    LoggerFactory.getLogger(ForkedProcessService.class);

  private final AtomicBoolean processTerminated = new AtomicBoolean(false);
  private boolean processStarted = false;
  private LongLivedProcess process;
  private int executionTimeout = -1;
  private int timeoutCode = 1;
  /** 
  log to log to; defaults to this service log
   */
  private Logger processLog = LOG;
  
  /**
   * Exit code set when the spawned process exits
   */
  private AtomicInteger exitCode = new AtomicInteger(0);

  /**
   * Create an instance of the service
   * @param name a name
   */
  public ForkedProcessService(String name) {
    super(name);
  }

  /**
   * Create an instance of the service,  set up the process
   * @param name a name
   * @param commandList list of commands is inserted on the front
   * @param env environment variables above those generated by
   * @throws IOException IO problems
   */
  public ForkedProcessService(String name,
      Map<String, String> env,
      List<String> commandList) throws IOException {
    super(name);
    build(env, commandList);
  }

  @Override //AbstractService
  protected void serviceStart() throws Exception {
    if (process == null) {
      throw new ServiceStateException("Process not yet configured");
    }
    //now spawn the process -expect updates via callbacks
    process.start();
  }

  @Override //AbstractService
  protected void serviceStop() throws Exception {
    completed();
    stopForkedProcess();
  }

  private void stopForkedProcess() {
    if (process != null) {
      process.stop();
    }
  }

  /**
   * Set the process log. This may be null for "do not log"
   * @param processLog process log
   */
  public void setProcessLog(Logger processLog) {
    this.processLog = processLog;
    process.setProcessLog(processLog);
  }

  /**
   * Set the timeout by which time a process must have finished -or -1 for forever
   * @param timeout timeout in milliseconds
   */
  public void setTimeout(int timeout, int code) {
    this.executionTimeout = timeout;
    this.timeoutCode = code;
  }

  public void setRecentLineLimit(int limit) {
    process.setRecentLineLimit(limit);
  }

  /**
   * Build the process to execute when the service is started
   * @param commandList list of commands is inserted on the front
   * @param env environment variables above those generated by
   * @throws IOException IO problems
   */
  public void build(Map<String, String> env,
                    List<String> commandList)
      throws IOException {
    assert process == null;

    process = new LongLivedProcess(getName(), processLog, commandList);
    process.setLifecycleCallback(this);
    //set the env variable mapping
    process.putEnvMap(env);
  }

  @Override // notification from executed process
  public synchronized void onProcessStarted(LongLivedProcess process) {
    LOG.debug("Process has started");
    processStarted = true;
    if (executionTimeout > 0) {
      setExecutor(ServiceThreadFactory.singleThreadExecutor(getName(), true));
      execute(this);
    }
  }

  @Override  // notification from executed process
  public void onProcessExited(LongLivedProcess process,
      int uncorrected,
      int code) {
    try {
      synchronized (this) {
        completed();
        //note whether or not the service had already stopped
        LOG.debug("Process has exited with exit code {}", code);
        if (code != 0) {
          reportFailure(code, getName() + " failed with code " + code);
        }
      }
    } finally {
      stop();
    }
  }

  private void reportFailure(int code, String text) {
    //error
    ServiceLaunchException execEx = new ServiceLaunchException(code, text);
    LOG.debug("Noting failure", execEx);
    noteFailure(execEx);
  }

  /**
   * handle timeout response by escalating it to a failure
   */
  @Override
  public void run() {
    try {
      synchronized (processTerminated) {
        if (!processTerminated.get()) {
          processTerminated.wait(executionTimeout);
        }
      }

    } catch (InterruptedException e) {
      //assume signalled; exit
    }
    //check the status; if the marker isn't true, bail
    if (!processTerminated.getAndSet(true)) {
      LOG.info("process timeout: reporting error code {}", timeoutCode);

      //timeout
      if (isInState(STATE.STARTED)) {
        //trigger a failure
        stopForkedProcess();
      }
      reportFailure(timeoutCode, getName() + ": timeout after " + executionTimeout
                   + " millis: exit code =" + timeoutCode);
    }
  }

  /**
   * Note the process as having completed.
   * The process marked as terminated
   * -and anything synchronized on <code>processTerminated</code>
   * is notified
   */
  protected void completed() {
    processTerminated.set(true);
    synchronized (processTerminated) {
      processTerminated.notify();
    }
  }

  public boolean isProcessTerminated() {
    return processTerminated.get();
  }

  public synchronized boolean isProcessStarted() {
    return processStarted;
  }

  /**
   * Is a process running: between started and terminated
   * @return true if the process is up.
   */
  public synchronized boolean isProcessRunning() {
    return processStarted && !isProcessTerminated();
  }


  public Integer getExitCode() {
    return process.getExitCode();
  }
  
  public int getExitCodeSignCorrected() {
    Integer exitCode = process.getExitCodeSignCorrected();
    if (exitCode == null) return -1;
    return exitCode;
  }

  /**
   * Get the recent output from the process, or [] if not defined
   * @return a possibly empty list
   */
  public List<String> getRecentOutput() {
    return process != null
           ? process.getRecentOutput()
           : new LinkedList<String>();
  }

  /**
   * Get the recent output from the process, or [] if not defined
   *
   * @param finalOutput flag to indicate "wait for the final output of the process"
   * @param duration the duration, in ms, 
   * to wait for recent output to become non-empty
   * @return a possibly empty list
   */
  public List<String> getRecentOutput(boolean finalOutput, int duration) {
    if (process == null) {
      return new LinkedList<>();
    }
    return process.getRecentOutput(finalOutput, duration);
  }
  
}
