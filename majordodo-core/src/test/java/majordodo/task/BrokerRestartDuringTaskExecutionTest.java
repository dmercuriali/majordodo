/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package majordodo.task;

import majordodo.task.BrokerConfiguration;
import majordodo.task.TasksHeap;
import majordodo.task.FileCommitLog;
import majordodo.task.Task;
import majordodo.task.GroupMapperFunction;
import majordodo.task.Broker;
import majordodo.client.TaskStatusView;
import majordodo.executors.TaskExecutor;
import majordodo.network.netty.NettyBrokerLocator;
import majordodo.network.netty.NettyChannelAcceptor;
import majordodo.worker.WorkerCore;
import majordodo.worker.WorkerCoreConfiguration;
import majordodo.worker.WorkerStatusListener;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * Basic tests for recovery
 *
 * @author enrico.olivelli
 */
public class BrokerRestartDuringTaskExecutionTest {

    protected Path workDir;

    @After
    public void deleteWorkdir() throws Exception {
        if (workDir != null) {
            Files.walkFileTree(workDir, new FileVisitor<Path>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

            });
        }

    }

    @Before
    public void setupLogger() throws Exception {
        Level level = Level.SEVERE;
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {

            @Override
            public void uncaughtException(Thread t, Throwable e) {
                System.err.println("uncaughtException from thread " + t.getName() + ": " + e);
                e.printStackTrace();
            }
        });
        java.util.logging.LogManager.getLogManager().reset();
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(level);
        SimpleFormatter f = new SimpleFormatter();
        ch.setFormatter(f);
        java.util.logging.Logger.getLogger("").setLevel(level);
        java.util.logging.Logger.getLogger("").addHandler(ch);
    }

    protected GroupMapperFunction createGroupMapperFunction() {
        return new GroupMapperFunction() {

            @Override
            public int getGroup(long taskid, String tasktype, String userid) {
                return groupsMap.getOrDefault(userid, 0);

            }
        };
    }

    protected Map<String, Integer> groupsMap = new HashMap<>();

    private static final String TASKTYPE_MYTYPE = "mytype";
    private static final String userId = "queue1";
    private static final int group = 12345;

    @Before
    public void before() throws Exception {
        groupsMap.clear();
        groupsMap.put(userId, group);
    }

    @Test
    public void taskRecoveryTest() throws Exception {

        Path mavenTargetDir = Paths.get("target").toAbsolutePath();
        workDir = Files.createTempDirectory(mavenTargetDir, "test" + System.nanoTime());
        System.out.println("SETUPWORKDIR:" + workDir);
        long taskId;
        String workerId = "abc";
        String taskParams = "param";

        CountDownLatch taskStartedLatch = new CountDownLatch(1);
        CountDownLatch taskFinishedLatch = new CountDownLatch(1);

        CountDownLatch newBrokerStartedLatch = new CountDownLatch(1);

        String host = "localhost";
        int port = 7000;

        // startAsWritable a worker, the broker is not started
        try (NettyBrokerLocator locator = new NettyBrokerLocator(host, port)) {
            Map<String, Integer> tags = new HashMap<>();
            tags.put(TASKTYPE_MYTYPE, 1);

            WorkerCoreConfiguration config = new WorkerCoreConfiguration();
            config.setWorkerId(workerId);
            config.setMaxThreadsByTaskType(tags);
            config.setGroups(Arrays.asList(group));
            config.setTasksRequestTimeout(1000);
            try (WorkerCore core = new WorkerCore(config, "process1", locator, null);) {
                core.start();
                core.setExecutorFactory(
                        (String tasktype, Map<String, Object> parameters) -> new TaskExecutor() {
                            @Override
                            public String executeTask(Map<String, Object> parameters) throws Exception {
                                System.out.println("executeTask: " + parameters);
                                taskStartedLatch.countDown();
                                newBrokerStartedLatch.await(10, TimeUnit.SECONDS);
                                taskFinishedLatch.countDown();
                                return "theresult";
                            }
                        }
                );

                // startAsWritable a broker and submit some work
                BrokerConfiguration brokerConfig = new BrokerConfiguration();
                brokerConfig.setMaxWorkerIdleTime(5000);
                try (Broker broker = new Broker(brokerConfig, new FileCommitLog(workDir, workDir), new TasksHeap(1000, createGroupMapperFunction()));) {
                    broker.startAsWritable();
                    taskId = broker.getClient().submitTask(TASKTYPE_MYTYPE, userId, taskParams, 0,0,null).getTaskId();
                    try (NettyChannelAcceptor server = new NettyChannelAcceptor(broker.getAcceptor());) {
                        server.setHost(host);
                        server.setPort(port);
                        server.start();

                        // wait the worker to startAsWritable execution
                        taskStartedLatch.await(10, TimeUnit.SECONDS);
                    }
                    // now the broker will die
                }

                // restart the broker
                try (Broker broker = new Broker(brokerConfig, new FileCommitLog(workDir, workDir), new TasksHeap(1000, createGroupMapperFunction()));) {
                    broker.startAsWritable();
                    try (NettyChannelAcceptor server = new NettyChannelAcceptor(broker.getAcceptor());) {
                        server.setHost(host);
                        server.setPort(port);
                        server.start();
                        newBrokerStartedLatch.countDown();
                        // wait the worker to finish execution
                        taskFinishedLatch.await(10, TimeUnit.SECONDS);

                        boolean ok = false;
                        for (int i = 0; i < 100; i++) {
                            Task task = broker.getBrokerStatus().getTask(taskId);
                            if (task.getStatus() == Task.STATUS_FINISHED) {
                                ok = true;
                                break;
                            }
                            Thread.sleep(1000);
                        }
                        assertTrue(ok);
                    }

                }

            }
        }

    }
}