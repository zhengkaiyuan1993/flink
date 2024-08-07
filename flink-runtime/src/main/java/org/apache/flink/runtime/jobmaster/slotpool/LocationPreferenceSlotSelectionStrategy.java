/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.jobmaster.slotpool;

import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.SlotProfile;
import org.apache.flink.runtime.jobmanager.scheduler.Locality;
import org.apache.flink.runtime.jobmaster.SlotInfo;
import org.apache.flink.runtime.taskmanager.TaskManagerLocation;
import org.apache.flink.util.CollectionUtil;

import javax.annotation.Nonnull;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * This class implements a {@link SlotSelectionStrategy} that is based on location preference hints.
 */
public abstract class LocationPreferenceSlotSelectionStrategy implements SlotSelectionStrategy {

    LocationPreferenceSlotSelectionStrategy() {}

    @Override
    public Optional<SlotInfoAndLocality> selectBestSlotForProfile(
            @Nonnull FreeSlotTracker freeSlotTracker, @Nonnull SlotProfile slotProfile) {

        Collection<TaskManagerLocation> locationPreferences = slotProfile.getPreferredLocations();

        if (freeSlotTracker.getAvailableSlots().isEmpty()) {
            return Optional.empty();
        }

        final ResourceProfile resourceProfile = slotProfile.getPhysicalSlotResourceProfile();

        // if we have no location preferences, we can only filter by the additional requirements.
        return locationPreferences.isEmpty()
                ? selectWithoutLocationPreference(freeSlotTracker, resourceProfile)
                : selectWithLocationPreference(
                        freeSlotTracker, locationPreferences, resourceProfile);
    }

    @Nonnull
    private Optional<SlotInfoAndLocality> selectWithLocationPreference(
            @Nonnull FreeSlotTracker freeSlotTracker,
            @Nonnull Collection<TaskManagerLocation> locationPreferences,
            @Nonnull ResourceProfile resourceProfile) {

        // we build up two indexes, one for resource id and one for host names of the preferred
        // locations.
        final Map<ResourceID, Integer> preferredResourceIDs =
                CollectionUtil.newHashMapWithExpectedSize(locationPreferences.size());
        final Map<String, Integer> preferredFQHostNames =
                CollectionUtil.newHashMapWithExpectedSize(locationPreferences.size());

        for (TaskManagerLocation locationPreference : locationPreferences) {
            preferredResourceIDs.merge(locationPreference.getResourceID(), 1, Integer::sum);
            preferredFQHostNames.merge(locationPreference.getFQDNHostname(), 1, Integer::sum);
        }

        SlotInfo bestCandidate = null;
        Locality bestCandidateLocality = Locality.UNKNOWN;
        double bestCandidateScore = Double.NEGATIVE_INFINITY;

        for (AllocationID allocationId : freeSlotTracker.getAvailableSlots()) {
            SlotInfo candidate = freeSlotTracker.getSlotInfo(allocationId);

            if (candidate.getResourceProfile().isMatching(resourceProfile)) {

                // this gets candidate is local-weigh
                int localWeigh =
                        preferredResourceIDs.getOrDefault(
                                candidate.getTaskManagerLocation().getResourceID(), 0);

                // this gets candidate is host-local-weigh
                int hostLocalWeigh =
                        preferredFQHostNames.getOrDefault(
                                candidate.getTaskManagerLocation().getFQDNHostname(), 0);

                double candidateScore =
                        calculateCandidateScore(
                                localWeigh,
                                hostLocalWeigh,
                                () -> freeSlotTracker.getTaskExecutorUtilization(candidate));
                if (candidateScore > bestCandidateScore) {
                    bestCandidateScore = candidateScore;
                    bestCandidate = candidate;
                    bestCandidateLocality =
                            localWeigh > 0
                                    ? Locality.LOCAL
                                    : hostLocalWeigh > 0 ? Locality.HOST_LOCAL : Locality.NON_LOCAL;
                }
            }
        }

        // at the end of the iteration, we return the candidate with best possible locality or null.
        return bestCandidate != null
                ? Optional.of(SlotInfoAndLocality.of(bestCandidate, bestCandidateLocality))
                : Optional.empty();
    }

    @Nonnull
    protected abstract Optional<SlotInfoAndLocality> selectWithoutLocationPreference(
            @Nonnull FreeSlotTracker freeSlotTracker, @Nonnull ResourceProfile resourceProfile);

    protected abstract double calculateCandidateScore(
            int localWeigh, int hostLocalWeigh, Supplier<Double> taskExecutorUtilizationSupplier);

    // -------------------------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------------------------

    public static LocationPreferenceSlotSelectionStrategy createDefault() {
        return new DefaultLocationPreferenceSlotSelectionStrategy();
    }

    public static LocationPreferenceSlotSelectionStrategy createEvenlySpreadOut() {
        return new EvenlySpreadOutLocationPreferenceSlotSelectionStrategy();
    }
}
