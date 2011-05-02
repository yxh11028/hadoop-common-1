/**
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

package org.apache.hadoop.mapred;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.security.PrivilegedExceptionAction;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSError;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.security.TokenCache;
import org.apache.hadoop.mapreduce.security.token.JobTokenIdentifier;
import org.apache.hadoop.mapreduce.security.token.JobTokenSecretManager;
import org.apache.hadoop.metrics2.lib.DefaultMetricsSystem;
import org.apache.hadoop.metrics2.source.JvmMetrics;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.conf.YARNApplicationConstants;
import org.apache.log4j.LogManager;

/**
 * The main() for MapReduce task processes.
 */
class YarnChild {

  public static final Log LOG = LogFactory.getLog(YarnChild.class);

  static volatile TaskAttemptID taskid = null;

  public static void main(String[] args) throws Throwable {
    LOG.debug("Child starting");

    final JobConf defaultConf = new JobConf();
    defaultConf.addResource(YARNApplicationConstants.JOB_CONF_FILE);
    UserGroupInformation.setConfiguration(defaultConf);

    String host = args[0];
    int port = Integer.parseInt(args[1]);
    final InetSocketAddress address = new InetSocketAddress(host, port);
    final TaskAttemptID firstTaskid = TaskAttemptID.forName(args[2]);
    int jvmIdInt = Integer.parseInt(args[3]);
    JVMId jvmId = new JVMId(firstTaskid.getJobID(),
        firstTaskid.getTaskType() == TaskType.MAP, jvmIdInt);

    // initialize metrics
    DefaultMetricsSystem.initialize(
        StringUtils.camelize(firstTaskid.getTaskType().name()) +"Task");

    if (null == System.getenv().get(Constants.HADOOP_WORK_DIR)) {
      throw new IOException("Environment variable " +
          Constants.HADOOP_WORK_DIR + " is not set");
    }
    Token<JobTokenIdentifier> jt = loadCredentials(defaultConf, address);

    // Create TaskUmbilicalProtocol as actual task owner.
    UserGroupInformation taskOwner =
      UserGroupInformation.createRemoteUser(firstTaskid.getJobID().toString());
    taskOwner.addToken(jt);
    final TaskUmbilicalProtocol umbilical =
      taskOwner.doAs(new PrivilegedExceptionAction<TaskUmbilicalProtocol>() {
      @Override
      public TaskUmbilicalProtocol run() throws Exception {
        return (TaskUmbilicalProtocol)RPC.getProxy(TaskUmbilicalProtocol.class,
            TaskUmbilicalProtocol.versionID, address, defaultConf);
      }
    });

    // report non-pid to application master
    JvmContext context = new JvmContext(jvmId, "-1000");
    Task task = null;
    UserGroupInformation childUGI = null;

    try {
      int idleLoopCount = 0;
      JvmTask myTask = null;;
      // poll for new task
      for (int idle = 0; null == myTask; ++idle) {
        SECONDS.sleep(Math.min(idle * 500, 1500));
        myTask = umbilical.getTask(context);
      }
      if (myTask.shouldDie()) {
        return;
      }

      task = myTask.getTask();
      YarnChild.taskid = task.getTaskID();

      // Create the job-conf and set credentials
      final JobConf job =
        configureTask(task, defaultConf.getCredentials(), jt);

      // Initiate Java VM metrics
      JvmMetrics.initSingleton(jvmId.toString(), job.getSessionId());
      LOG.debug("Remote user: " + job.get("user.name"));
      childUGI = UserGroupInformation.createRemoteUser(job.get("user.name"));
      // Add tokens to new user so that it may execute its task correctly.
      for(Token<?> token : UserGroupInformation.getCurrentUser().getTokens()) {
        childUGI.addToken(token);
      }

      // Create a final reference to the task for the doAs block
      final Task taskFinal = task;
      childUGI.doAs(new PrivilegedExceptionAction<Object>() {
        @Override
        public Object run() throws Exception {
          // use job-specified working directory
          FileSystem.get(job).setWorkingDirectory(job.getWorkingDirectory());
          taskFinal.run(job, umbilical); // run the task
          return null;
        }
      });
    } catch (FSError e) {
      LOG.fatal("FSError from child", e);
      umbilical.fsError(taskid, e.getMessage());
    } catch (Exception exception) {
      LOG.warn("Exception running child : "
          + StringUtils.stringifyException(exception));
      try {
        if (task != null) {
          // do cleanup for the task
          if (childUGI == null) { // no need to job into doAs block
            task.taskCleanup(umbilical);
          } else {
            final Task taskFinal = task;
            childUGI.doAs(new PrivilegedExceptionAction<Object>() {
              @Override
              public Object run() throws Exception {
                taskFinal.taskCleanup(umbilical);
                return null;
              }
            });
          }
        }
      } catch (Exception e) {
        LOG.info("Exception cleaning up: " + StringUtils.stringifyException(e));
      }
      // Report back any failures, for diagnostic purposes
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      exception.printStackTrace(new PrintStream(baos));
      if (taskid != null) {
        umbilical.reportDiagnosticInfo(taskid, baos.toString());
      }
    } catch (Throwable throwable) {
      LOG.fatal("Error running child : "
    	        + StringUtils.stringifyException(throwable));
      if (taskid != null) {
        Throwable tCause = throwable.getCause();
        String cause = tCause == null
                                 ? throwable.getMessage()
                                 : StringUtils.stringifyException(tCause);
        umbilical.fatalError(taskid, cause);
      }
    } finally {
      RPC.stopProxy(umbilical);
      DefaultMetricsSystem.shutdown();
      // Shutting down log4j of the child-vm...
      // This assumes that on return from Task.run()
      // there is no more logging done.
      LogManager.shutdown();
    }
  }

  static Token<JobTokenIdentifier> loadCredentials(JobConf conf,
      InetSocketAddress address) throws IOException {
    //load token cache storage
    String jobTokenFile =
      new Path("container_tokens").makeQualified(FileSystem.getLocal(conf)).toUri().getPath();
    Credentials credentials =
      TokenCache.loadTokens(jobTokenFile, conf);
    LOG.debug("loading token. # keys =" +credentials.numberOfSecretKeys() +
        "; from file=" + jobTokenFile);
    Token<JobTokenIdentifier> jt = TokenCache.getJobToken(credentials);
    jt.setService(new Text(address.getAddress().getHostAddress() + ":"
        + address.getPort()));
    UserGroupInformation current = UserGroupInformation.getCurrentUser();
    current.addToken(jt);
    for (Token<? extends TokenIdentifier> tok : credentials.getAllTokens()) {
      current.addToken(tok);
    }
    // Set the credentials
    conf.setCredentials(credentials);
    return jt;
  }

  static void configureLocalDirs(Task task, JobConf job) {
    String[] localSysDirs = StringUtils.getTrimmedStrings(
        System.getenv(YARNApplicationConstants.LOCAL_DIR_ENV));
    job.setStrings(MRConfig.LOCAL_DIR, localSysDirs);
    LOG.info(MRConfig.LOCAL_DIR + " for child: " + job.get(MRConfig.LOCAL_DIR));
  }

  static JobConf configureTask(Task task, Credentials credentials,
      Token<JobTokenIdentifier> jt) throws IOException {
    final JobConf job = new JobConf(YARNApplicationConstants.JOB_CONF_FILE);
    job.setCredentials(credentials);
    // set tcp nodelay
    job.setBoolean("ipc.client.tcpnodelay", true);
    job.setClass(MRConfig.TASK_LOCAL_OUTPUT_CLASS,
        YarnOutputFiles.class, MapOutputFile.class);
    // set the jobTokenFile into task
    task.setJobTokenSecret(
        JobTokenSecretManager.createSecretKey(jt.getPassword()));

    // setup the child's MRConfig.LOCAL_DIR.
    configureLocalDirs(task, job);

    // setup the child's attempt directories
    // Do the task-type specific localization
    task.localizeConfiguration(job);
    //write the localized task jobconf
    LocalDirAllocator lDirAlloc = new LocalDirAllocator(MRConfig.LOCAL_DIR);
    Path localTaskFile =
      lDirAlloc.getLocalPathForWrite(Constants.JOBFILE, job);
    writeLocalJobFile(localTaskFile, job);
    task.setJobFile(localTaskFile.toString());
    task.setConf(job);
    return job;
  }

  private static final FsPermission urw_gr =
    FsPermission.createImmutable((short) 0640);

  /**
   * Write the task specific job-configuration file.
   * @throws IOException
   */
  public static void writeLocalJobFile(Path jobFile, JobConf conf)
      throws IOException {
    FileSystem localFs = FileSystem.getLocal(conf);
    localFs.delete(jobFile);
    OutputStream out = null;
    try {
      out = FileSystem.create(localFs, jobFile, urw_gr);
      conf.writeXml(out);
    } finally {
      IOUtils.cleanup(LOG, out);
    }
  }

}
