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

package org.apache.flink.streaming.api.operators.collect.utils;

import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.runtime.operators.coordination.CoordinationRequest;
import org.apache.flink.runtime.operators.coordination.CoordinationRequestGateway;
import org.apache.flink.runtime.operators.coordination.CoordinationRequestHandler;
import org.apache.flink.runtime.operators.coordination.CoordinationResponse;
import org.apache.flink.util.OptionalFailure;

import javax.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** A {@link JobClient} to test fetching SELECT query results. */
public class TestJobClient implements JobClient, CoordinationRequestGateway {

    private final JobID jobId;
    private final String operatorUid;
    private final CoordinationRequestHandler handler;
    private final JobInfoProvider infoProvider;

    private JobStatus jobStatus;
    private JobExecutionResult jobExecutionResult;

    public TestJobClient(
            JobID jobId,
            String operatorUid,
            CoordinationRequestHandler handler,
            JobInfoProvider infoProvider) {
        this.jobId = jobId;
        this.operatorUid = operatorUid;
        this.handler = handler;
        this.infoProvider = infoProvider;

        this.jobStatus = JobStatus.RUNNING;
        this.jobExecutionResult = null;
    }

    @Override
    public JobID getJobID() {
        return jobId;
    }

    @Override
    public CompletableFuture<JobStatus> getJobStatus() {
        return CompletableFuture.completedFuture(jobStatus);
    }

    @Override
    public CompletableFuture<Void> cancel() {
        jobStatus = JobStatus.CANCELED;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<String> stopWithSavepoint(
            boolean advanceToEndOfEventTime,
            @Nullable String savepointDirectory,
            SavepointFormatType formatType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<String> triggerSavepoint(
            @Nullable String savepointDirectory, SavepointFormatType formatType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Map<String, Object>> getAccumulators() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<JobExecutionResult> getJobExecutionResult() {
        return CompletableFuture.completedFuture(jobExecutionResult);
    }

    @Override
    public CompletableFuture<CoordinationResponse> sendCoordinationRequest(
            String operatorUid, CoordinationRequest request) {
        if (jobStatus.isGloballyTerminalState()) {
            throw new RuntimeException("Job terminated");
        }

        assertThat(operatorUid).isEqualTo(this.operatorUid);
        CoordinationResponse response;
        try {
            response = handler.handleCoordinationRequest(request).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (infoProvider.isJobFinished()) {
            jobStatus = JobStatus.FINISHED;
            jobExecutionResult =
                    new JobExecutionResult(jobId, 0, infoProvider.getAccumulatorResults());
        }

        return CompletableFuture.completedFuture(response);
    }

    /** Interface to provide job related info for {@link TestJobClient}. */
    public interface JobInfoProvider {

        boolean isJobFinished();

        Map<String, OptionalFailure<Object>> getAccumulatorResults();
    }
}
