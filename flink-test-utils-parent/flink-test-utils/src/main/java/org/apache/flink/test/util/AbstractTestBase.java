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

package org.apache.flink.test.util;

import org.apache.flink.client.program.MiniClusterClient;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.test.junit5.InjectClusterClient;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.apache.flink.util.FileUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Base class for unit tests that run multiple tests and want to reuse the same Flink cluster. This
 * saves a significant amount of time, since the startup and shutdown of the Flink clusters
 * (including actor systems, etc) usually dominates the execution of the actual tests.
 *
 * <p>To write a unit test against this test base, simply extend it and add one or more regular test
 * methods and retrieve the StreamExecutionEnvironment from the context:
 *
 * <pre>
 *   {@literal @}Test
 *   public void someTest() {
 *       ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
 *       // test code
 *       env.execute();
 *   }
 *
 *   {@literal @}Test
 *   public void anotherTest() {
 *       StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
 *       // test code
 *       env.execute();
 *   }
 *
 * </pre>
 */
public abstract class AbstractTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractTestBase.class);

    private static final int DEFAULT_PARALLELISM = 4;

    @RegisterExtension
    public static final MiniClusterExtension MINI_CLUSTER_EXTENSION =
            new MiniClusterExtension(
                    new MiniClusterResourceConfiguration.Builder()
                            .setNumberTaskManagers(1)
                            .setNumberSlotsPerTaskManager(DEFAULT_PARALLELISM)
                            .build());

    @TempDir protected File temporaryFolder;

    @AfterEach
    public final void cleanupRunningJobs(@InjectClusterClient MiniClusterClient clusterClient)
            throws Exception {
        if (!MINI_CLUSTER_EXTENSION.isRunning()) {
            LOG.warn("Mini cluster is not running after the test!");
            return;
        }

        for (JobStatusMessage path : clusterClient.listJobs().get()) {
            if (!path.getJobState().isTerminalState()) {
                try {
                    clusterClient.cancel(path.getJobId()).get();
                } catch (Exception ignored) {
                    // ignore exceptions when cancelling dangling jobs
                }
            }
        }
    }

    // --------------------------------------------------------------------------------------------
    //  Temporary File Utilities
    // --------------------------------------------------------------------------------------------

    public String getTempDirPath(String dirName) throws IOException {
        File f = createAndRegisterTempFile(dirName);
        return f.toURI().toString();
    }

    public String getTempFilePath(String fileName) throws IOException {
        File f = createAndRegisterTempFile(fileName);
        return f.toURI().toString();
    }

    public String createTempFile(String fileName, String contents) throws IOException {
        File f = createAndRegisterTempFile(fileName);
        if (!f.getParentFile().exists()) {
            f.getParentFile().mkdirs();
        }
        f.createNewFile();
        FileUtils.writeFileUtf8(f, contents);
        return f.toURI().toString();
    }

    public File createAndRegisterTempFile(String fileName) throws IOException {
        return new File(temporaryFolder, fileName);
    }
}
