/*
 * Copyright (c) 2008-2025, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl;

import com.hazelcast.core.DistributedObject;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.core.LifecycleService;
import com.hazelcast.flakeidgen.FlakeIdGenerator;
import com.hazelcast.internal.nio.IOUtil;
import com.hazelcast.internal.tpcengine.util.OS;
import com.hazelcast.internal.util.ExceptionUtil;
import com.hazelcast.jet.JetException;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.config.ResourceConfig;
import com.hazelcast.jet.core.JobNotFoundException;
import com.hazelcast.jet.core.metrics.JobMetrics;
import com.hazelcast.jet.impl.deployment.IMapOutputStream;
import com.hazelcast.jet.impl.execution.init.JetInitDataSerializerHook;
import com.hazelcast.jet.impl.metrics.RawJobMetrics;
import com.hazelcast.jet.impl.util.ConcurrentMemoizingSupplier;
import com.hazelcast.jet.impl.util.ImdgUtil;
import com.hazelcast.jet.impl.util.Util;
import com.hazelcast.logging.ILogger;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import com.hazelcast.map.impl.proxy.MapProxyImpl;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.query.Predicate;
import com.hazelcast.spi.impl.NodeEngine;
import com.hazelcast.spi.properties.ClusterProperty;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.hazelcast.internal.util.StringUtil.lowerCaseInternal;
import static com.hazelcast.jet.Util.idFromString;
import static com.hazelcast.jet.Util.idToString;
import static com.hazelcast.jet.impl.util.IOUtil.fileNameFromUrl;
import static com.hazelcast.jet.impl.util.IOUtil.packDirectoryIntoZip;
import static com.hazelcast.jet.impl.util.IOUtil.packStreamIntoZip;
import static com.hazelcast.jet.impl.util.Util.memoizeConcurrent;
import static com.hazelcast.map.impl.EntryRemovingProcessor.ENTRY_REMOVING_PROCESSOR;
import static com.hazelcast.map.impl.MapService.SERVICE_NAME;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

public class JobRepository {

    /**
     * Prefix of all Hazelcast internal objects used by Jet (such as job
     * metadata, snapshots etc.)
     */
    public static final String INTERNAL_JET_OBJECTS_PREFIX = "__jet.";

    /**
     * State snapshot exported using {@link Job#exportSnapshot(String)} is
     * currently stored in IMaps named with this prefix.
     */
    public static final String EXPORTED_SNAPSHOTS_PREFIX = INTERNAL_JET_OBJECTS_PREFIX + "exportedSnapshot.";

    /**
     * A cache to speed up access to details about exported snapshots.
     */
    public static final String EXPORTED_SNAPSHOTS_DETAIL_CACHE = INTERNAL_JET_OBJECTS_PREFIX + "exportedSnapshotsCache";

    /**
     * Name of internal IMap which stores job resources and attached files.
     */
    public static final String RESOURCES_MAP_NAME_PREFIX = INTERNAL_JET_OBJECTS_PREFIX + "resources.";

    /**
     * Key prefix for attached job files in the IMap named {@link JobRepository#RESOURCES_MAP_NAME_PREFIX}.
     */
    public static final String FILE_STORAGE_KEY_NAME_PREFIX = "f.";

    /**
     * Key prefix for added class resources in the IMap named {@link JobRepository#RESOURCES_MAP_NAME_PREFIX}.
     */
    public static final String CLASS_STORAGE_KEY_NAME_PREFIX = "c.";

    /**
     * Name of internal flake ID generator which is used for unique id generation.
     */
    public static final String RANDOM_ID_GENERATOR_NAME = INTERNAL_JET_OBJECTS_PREFIX + "ids";

    /**
     * Name of internal IMap which stores {@link JobRecord}s.
     */
    public static final String JOB_RECORDS_MAP_NAME = INTERNAL_JET_OBJECTS_PREFIX + "records";

    /**
     * Name of internal IMap which stores {@link JobExecutionRecord}s.
     */
    public static final String JOB_EXECUTION_RECORDS_MAP_NAME = INTERNAL_JET_OBJECTS_PREFIX + "executionRecords";

    /**
     * Name of internal IMap which stores job results
     */
    public static final String JOB_RESULTS_MAP_NAME = INTERNAL_JET_OBJECTS_PREFIX + "results";

    /**
     * Name of internal IMap which stores {@link JobMetrics}s.
     */
    public static final String JOB_METRICS_MAP_NAME = INTERNAL_JET_OBJECTS_PREFIX + "results.metrics";

    /**
     * Prefix for internal IMaps which store snapshot data. Snapshot data for
     * one snapshot is stored in either of the following two maps:
     * <ul>
     *      <li>{@code _jet.snapshot.<jobId>.0}
     *      <li>{@code _jet.snapshot.<jobId>.1}
     * </ul>
     * Which one of these is determined in {@link JobExecutionRecord}.
     */
    public static final String SNAPSHOT_DATA_MAP_PREFIX = INTERNAL_JET_OBJECTS_PREFIX + "snapshot.";

    /**
     * Only do the cleanup if the number of JobResults exceeds the maximum
     * number by at least 5% (1/20 = 0.05 = 5%).
     */
    private static final int MAX_NO_RESULTS_OVERHEAD = 20;
    private static final long DEFAULT_RESOURCES_EXPIRATION_MILLIS = HOURS.toMillis(2);
    private static final int JOB_ID_STRING_LENGTH = idToString(0L).length();


    private final HazelcastInstance instance;
    private final ILogger logger;

    private final ConcurrentMemoizingSupplier<IMap<Long, JobRecord>> jobRecords;
    private final ConcurrentMemoizingSupplier<IMap<Long, JobResult>> jobResults;
    private final Supplier<IMap<Long, JobExecutionRecord>> jobExecutionRecords;
    private final Supplier<IMap<Long, List<RawJobMetrics>>> jobMetrics;
    private final Supplier<IMap<String, SnapshotValidationRecord>> exportedSnapshotDetailsCache;
    private final Supplier<FlakeIdGenerator> idGenerator;

    private long resourcesExpirationMillis = DEFAULT_RESOURCES_EXPIRATION_MILLIS;

    public JobRepository(HazelcastInstance instance) {
        this.instance = instance;
        this.logger = instance.getLoggingService().getLogger(getClass());

        jobRecords = new ConcurrentMemoizingSupplier<>(() -> instance.getMap(JOB_RECORDS_MAP_NAME));
        jobResults = new ConcurrentMemoizingSupplier<>(() -> instance.getMap(JOB_RESULTS_MAP_NAME));
        jobExecutionRecords = memoizeConcurrent(() -> safeImap(instance.getMap(JOB_EXECUTION_RECORDS_MAP_NAME)));
        jobMetrics = memoizeConcurrent(() -> instance.getMap(JOB_METRICS_MAP_NAME));
        exportedSnapshotDetailsCache = memoizeConcurrent(() -> instance.getMap(EXPORTED_SNAPSHOTS_DETAIL_CACHE));
        idGenerator = memoizeConcurrent(() -> instance.getFlakeIdGenerator(RANDOM_ID_GENERATOR_NAME));
    }

    /**
     * Configures given IMap to fail on indeterminate operation state.
     *
     * @param map map to configure
     * @return the same map with applied configuration
     */
    public static <K, V> IMap<K, V> safeImap(IMap<K, V> map) {
        // On client side there is no setFailOnIndeterminateOperationState method.
        // Client should only read Jet maps.
        if (map instanceof MapProxyImpl) {
            ((MapProxyImpl<K, V>) map).setFailOnIndeterminateOperationState(true);
        }
        return map;
    }

    // for tests
    void setResourcesExpirationMillis(long resourcesExpirationMillis) {
        this.resourcesExpirationMillis = resourcesExpirationMillis;
    }

    /**
     * Uploads job resources and returns a unique job id generated for the job.
     * If the upload process fails for any reason, such as being unable to access a resource,
     * uploaded resources are cleaned up.
     */
    void uploadJobResources(long jobId, JobConfig jobConfig) {
        Map<String, byte[]> tmpMap = new HashMap<>();
        boolean resourceImapCreated = false;
        Supplier<IMap<String, byte[]>> jobFileStorage = Util.memoize(() -> getJobResources(jobId));
        try {
            for (ResourceConfig rc : jobConfig.getResourceConfigs().values()) {
                switch (rc.getResourceType()) {
                    case CLASSPATH_RESOURCE, CLASS -> {
                        try (InputStream in = rc.getUrl().openStream()) {
                            readStreamAndPutCompressedToMap(rc.getId(), tmpMap, in);
                        }
                    }
                    case FILE -> {
                        try (InputStream in = rc.getUrl().openStream();
                             IMapOutputStream os = new IMapOutputStream(jobFileStorage.get(), fileKeyName(rc.getId()))
                        ) {
                            resourceImapCreated = true;
                            packStreamIntoZip(in, os, requireNonNull(fileNameFromUrl(rc.getUrl())));
                        }
                    }
                    case DIRECTORY -> {
                        Path baseDir = validateAndGetDirectoryPath(rc);
                        try (IMapOutputStream os = new IMapOutputStream(jobFileStorage.get(), fileKeyName(rc.getId()))) {
                            resourceImapCreated = true;
                            packDirectoryIntoZip(baseDir, os);
                        }
                    }
                    case JAR -> loadJar(tmpMap, rc);
                    case JARS_IN_ZIP -> loadJarsInZip(tmpMap, rc.getUrl());
                    default -> throw new JetException("Unsupported resource type: " + rc.getResourceType());
                }
            }
        } catch (IOException | URISyntaxException e) {
            if (resourceImapCreated) {
                jobFileStorage.get().destroy();
            }
            throw new JetException("Job resource upload failed", e);
        }
        // avoid creating resources map if map is empty
        if (!tmpMap.isEmpty()) {
            IMap<String, byte[]> jobResourcesMap = jobFileStorage.get();
            // now upload it all
            try {
                jobResourcesMap.putAll(tmpMap);
            } catch (Exception e) {
                try {
                    jobResourcesMap.destroy();
                } catch (Exception ee) {
                    JetException wrapper = new JetException("Job resource upload failed", ee);
                    wrapper.addSuppressed(e);
                    throw wrapper;
                }
                throw new JetException("Job resource upload failed", e);
            }
        }
    }

    private Path validateAndGetDirectoryPath(ResourceConfig rc) throws URISyntaxException, IOException {
        Path baseDir = Paths.get(rc.getUrl().toURI());
        if (!Files.isDirectory(baseDir)) {
            throw new FileNotFoundException(baseDir + " is not a valid directory");
        }
        return baseDir;
    }

    public long newJobId() {
        return idGenerator.get().newId();
    }

    private void loadJar(Map<String, byte[]> tmpMap, ResourceConfig rc) throws IOException {
        try (InputStream in = rc.getUrl().openStream()) {
            loadJarFromInputStream(tmpMap, in);
        }
    }

    /**
     * Unzips the ZIP archive and processes JAR files
     */
    private void loadJarsInZip(Map<String, byte[]> map, URL url) throws IOException {
        try (InputStream inputStream = new BufferedInputStream(url.openStream())) {
            executeOnJarsInZIP(inputStream, zis -> {
                try {
                    loadJarFromInputStream(map, zis);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Extracts JARs from a ZIP, provided by an {@link InputStream}, passing them to a consumer to process.
     * <p>
     * Caller is responsible for closing stream.
     */
    public static void executeOnJarsInZIP(InputStream zip, Consumer<ZipInputStream> processor) throws IOException {
        ZipInputStream zis = new ZipInputStream(zip);
        ZipEntry zipEntry;

        while ((zipEntry = zis.getNextEntry()) != null) {
            if (zipEntry.isDirectory()) {
                continue;
            }
            if (lowerCaseInternal(zipEntry.getName()).endsWith(".jar")) {
                processor.accept(zis);
            }
        }
    }

    /**
     * Unzips the JAR archive and processes individual entries using
     * {@link #readStreamAndPutCompressedToMap(String, Map, InputStream)}.
     */
    private void loadJarFromInputStream(Map<String, byte[]> map, InputStream is) throws IOException {
        JarInputStream jis = new JarInputStream(is);
        JarEntry jarEntry;
        while ((jarEntry = jis.getNextJarEntry()) != null) {
            if (jarEntry.isDirectory()) {
                continue;
            }
            readStreamAndPutCompressedToMap(jarEntry.getName(), map, jis);
        }
    }

    private static void readStreamAndPutCompressedToMap(
            String resourceName, Map<String, byte[]> map, InputStream in
    ) throws IOException {
        // ignore duplicates: the first resource in first jar takes precedence
        map.putIfAbsent(classKeyName(resourceName), IOUtil.compress(in.readAllBytes()));
    }

    /**
     * Puts the given job record into the jobRecords map.
     * If another job record is already put, it checks if it has the same DAG.
     * If it has a different DAG, then the call fails with {@link IllegalStateException}
     */
    void putNewJobRecord(JobRecord jobRecord) {
        long jobId = jobRecord.getJobId();
        JobRecord prev = jobRecords.get().putIfAbsent(jobId, jobRecord);
        if (prev != null && !prev.getDag().equals(jobRecord.getDag())) {
            throw new IllegalStateException("Cannot put job record for job " + idToString(jobId)
                    + " because it already exists with a different DAG");
        }
    }

    /**
     * Updates the job record of {@linkplain JobRecord#getJobId the corresponding job}.
     */
    void updateJobRecord(JobRecord jobRecord) {
        jobRecords.get().set(jobRecord.getJobId(), jobRecord);
    }

    /**
     * Updates the job quorum size of all jobs so that it is at least {@code
     * newQuorumSize}.
     */
    void updateJobQuorumSizeIfSmaller(long jobId, int newQuorumSize) {
        jobExecutionRecords.get().executeOnKey(jobId, ImdgUtil.entryProcessor((key, value) -> {
            if (value == null) {
                return null;
            }
            value.setLargerQuorumSize(newQuorumSize);
            return value;
        }));
    }

    /**
     * Generates a new execution id for the given job id, guaranteed to be unique across the cluster
     */
    long newExecutionId() {
        return idGenerator.get().newId();
    }

    /**
     * Puts a JobResult for the given job and deletes the JobRecord.
     *
     * @throws JobNotFoundException  if the JobRecord is not found
     * @throws IllegalStateException if the JobResult is already present
     */
    void completeJob(
            @Nonnull MasterContext masterContext,
            @Nullable List<RawJobMetrics> terminalMetrics,
            @Nullable Throwable error,
            long completionTime,
            boolean userCancelled) {
        long jobId = masterContext.jobId();

        JobConfig config = masterContext.jobRecord().getConfig();
        long creationTime = masterContext.jobRecord().getCreationTime();
        JobResult jobResult = new JobResult(jobId, config, creationTime, completionTime, toErrorMsg(error), userCancelled);

        if (terminalMetrics != null) {
            try {
                List<RawJobMetrics> prevMetrics = jobMetrics.get().put(jobId, terminalMetrics);
                if (prevMetrics != null) {
                    logger.warning("Overwriting job metrics for job " + jobResult);
                }
            } catch (Exception e) {
                logger.warning("Storing the job metrics failed, ignoring: " + e, e);
            }
        }
        for (;;) {
            // keep trying to store the JobResult until it succeeds
            try {
                jobResults.get().set(jobId, jobResult);
                break;
            } catch (Exception e) {
                // if the local instance was shut down, re-throw the error
                LifecycleService lifecycleService = instance.getLifecycleService();
                if (e instanceof HazelcastInstanceNotActiveException && (!lifecycleService.isRunning())) {
                    throw e;
                }
                // retry otherwise, after a delay
                long retryTimeoutSeconds = 1;
                logger.warning("Failed to store JobResult, will retry in " + retryTimeoutSeconds + " seconds: " + e, e);
                LockSupport.parkNanos(SECONDS.toNanos(retryTimeoutSeconds));
            }
        }

        deleteJob(jobId, !config.getResourceConfigs().isEmpty());
    }

    /**
     * Performs cleanup after job completion.
     */
    void deleteJob(long jobId, boolean hasResources) {
        // delete the job record and related records
        // ignore the eventual failure - there's a separate cleanup process that will take care
        BiConsumer<Object, Throwable> callback = (v, t) -> {
            if (t != null) {
                logger.warning("Failed to remove " + v.getClass().getSimpleName() + " for job "
                            + idToString(jobId) + ", ignoring", t);
            }
        };
        jobExecutionRecords.get().removeAsync(jobId).whenComplete(callback);
        jobRecords.get().removeAsync(jobId).whenComplete(callback);
        if (hasResources) {
            // avoid creating resource map if that is not necessary
            getJobResources(jobId).destroy();
        }
    }

    /**
     * Cleans up stale maps related to jobs
     */
    void cleanup(NodeEngine nodeEngine) {
        if (!jobRecordsMapExists()) {
            // It is possible that master node does not see IMap when other members
            // are going through after-hot-restart tasks. To avoid possible snapshot
            // deletion we cannot perform cleanup in such case.
            //
            // The only drawback is that after job records IMap is somehow deleted,
            // old snapshots and resources will not be cleared until new a job is submitted.
            // But that should usually not happen.
            logger.fine("Skipping job cleanup because job records IMap does not exist");
            return;
        }

        long start = System.nanoTime();

        cleanupMaps(nodeEngine);
        cleanupJobResults(nodeEngine);

        long elapsed = System.nanoTime() - start;
        logger.fine("Job cleanup took %sms", TimeUnit.NANOSECONDS.toMillis(elapsed));
    }

    private void cleanupMaps(NodeEngine nodeEngine) {
        Collection<DistributedObject> maps =
                nodeEngine.getProxyService().getDistributedObjects(SERVICE_NAME);

        // we need to take the list of active job records after getting the list of maps --
        // otherwise the job records could be missing newly submitted jobs.
        // create a new set since the returned implementation uses iterator for `contains`
        Set<Long> activeJobs = new HashSet<>(jobRecordsMap().keySet());

        for (DistributedObject map : maps) {
            if (map.getName().startsWith(SNAPSHOT_DATA_MAP_PREFIX)) {
                long id = jobIdFromPrefixedName(map.getName(), SNAPSHOT_DATA_MAP_PREFIX);
                if (!activeJobs.contains(id)) {
                    logger.fine("Deleting snapshot data map '%s' because job already finished", map.getName());
                    map.destroy();
                }
            } else if (map.getName().startsWith(RESOURCES_MAP_NAME_PREFIX)) {
                deleteMap(activeJobs, map);
            }
        }
    }

    private void deleteMap(Set<Long> activeJobs, DistributedObject map) {
        long id = jobIdFromPrefixedName(map.getName(), RESOURCES_MAP_NAME_PREFIX);
        if (activeJobs.contains(id)) {
            // job is still active, do nothing
            return;
        }
        if (jobResults.get().containsKey(id)) {
            // if job is finished, we can safely delete the map
            logger.fine("Deleting job resource map '%s' because job is already finished", map.getName());
            map.destroy();
        } else {
            // Job might be in the process of uploading resources, check how long the map has been there.
            // If we happen to recreate a just-deleted map, it will be destroyed again after
            // resourcesExpirationMillis.
            @SuppressWarnings("rawtypes")
            IMap resourceMap = (IMap) map;
            long creationTime = resourceMap.getLocalMapStats().getCreationTime();
            if (isResourceMapExpired(creationTime)) {
                logger.fine("Deleting job resource map %s because the map %s", map.getName(),
                        "was created long ago and job record or result still doesn't exist");
                resourceMap.destroy();
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void cleanupJobResults(NodeEngine nodeEngine) {
        int maxNoResults = Math.max(1, nodeEngine.getProperties().getInteger(ClusterProperty.JOB_RESULTS_MAX_SIZE));
        // delete oldest job results
        Map<Long, JobResult> jobResultsMap = jobResultsMap();
        if (jobResultsMap.size() > Util.addClamped(maxNoResults, maxNoResults / MAX_NO_RESULTS_OVERHEAD)) {
            Set<Long> jobIds = jobResultsMap.values().stream().sorted(comparing(JobResult::getCompletionTime).reversed())
                    .skip(maxNoResults)
                    .map(JobResult::getJobId)
                    .collect(toSet());

            jobMetrics.get().submitToKeys(jobIds, (EntryProcessor) ENTRY_REMOVING_PROCESSOR);
            jobResults.get().submitToKeys(jobIds, (EntryProcessor) ENTRY_REMOVING_PROCESSOR);

            jobIds.forEach(jobId -> {
                String resourcesMapName = jobResourcesMapName(jobId);
                if (nodeEngine.getProxyService().existsDistributedObject(SERVICE_NAME, resourcesMapName)) {
                    instance.getMap(resourcesMapName).destroy();
                }
            });
        }
    }

    private static String toErrorMsg(@Nullable Throwable error) {
        if (error == null) {
            return null;
        }
        if (error.getClass().equals(JetException.class) && error.getMessage() != null) {
            String stackTrace = ExceptionUtil.toString(error);
            // The error message is later thrown as JetException
            // Remove leading 'com.hazelcast.jet.JetException: ' from the stack trace to avoid double JetException
            // in the final stacktrace
            return stackTrace.substring(stackTrace.indexOf(' ') + 1);
        }
        return ExceptionUtil.toString(error);
    }

    private static long jobIdFromPrefixedName(String name, String prefix) {
        int idx = prefix.length();
        String jobId = name.substring(idx, idx + JOB_ID_STRING_LENGTH);
        return idFromString(jobId);
    }

    private boolean isResourceMapExpired(long creationTime) {
        return (System.currentTimeMillis() - creationTime) >= resourcesExpirationMillis;
    }

    Set<Long> getAllJobIds() {
        Set<Long> ids = new HashSet<>();
        ids.addAll(jobRecordsMap().keySet());
        ids.addAll(jobResultsMap().keySet());
        return ids;
    }

    public Collection<String> getActiveJobNames() {
        Map<Long, String> res = getJobRecords().stream()
                .filter(record -> record.getConfig().getName() != null)
                .collect(toMap(JobRecord::getJobId, record -> record.getConfig().getName()));
        // When finalizing a job, we first create the JobResult, then delete the JobRecord.
        // So it can happen that we saw a JobRecord for a completed job. Here we remove those.
        for (JobResult result : getJobResults()) {
            res.remove(result.getJobId());
        }
        return res.values();
    }

    public Collection<JobRecord> getJobRecords() {
        return jobRecordsMap().values();
    }

    public boolean jobRecordsMapExists() {
        return ((AbstractJetInstance<?>) instance.getJet()).existsDistributedObject(SERVICE_NAME, JOB_RECORDS_MAP_NAME);
    }

    private Map<Long, JobRecord> jobRecordsMap() {
        if (jobRecords.remembered() != null || jobRecordsMapExists()) {
            return jobRecords.get();
        }
        return Collections.emptyMap();
    }

    private Map<Long, JobResult> jobResultsMap() {
        if (jobResults.remembered() != null ||
                ((AbstractJetInstance<?>) instance.getJet()).existsDistributedObject(SERVICE_NAME, JOB_RESULTS_MAP_NAME)) {
            return jobResults.get();
        }
        return Collections.emptyMap();
    }

    public JobRecord getJobRecord(long jobId) {
        return jobRecords.get().get(jobId);
    }

    public JobExecutionRecord getJobExecutionRecord(long jobId) {
        return jobExecutionRecords.get().get(jobId);
    }

    /**
     * Gets the job resources map
     */
    public IMap<String, byte[]> getJobResources(long jobId) {
        return instance.getMap(jobResourcesMapName(jobId));
    }

    @Nullable
    public JobResult getJobResult(long jobId) {
        return jobResults.get().get(jobId);
    }

    @Nullable
    List<RawJobMetrics> getJobMetrics(long jobId) {
        return jobMetrics.get().get(jobId);
    }

    Collection<JobResult> getJobResults() {
        return jobResults.get().values();
    }

    /**
     * Returns job results for jobs with the given name.
     */
    Collection<JobResult> getJobResults(@Nonnull String name) {
        return jobResults.get().values(new FilterJobResultByNamePredicate(name));
    }

    /**
     * Write the {@link JobExecutionRecord} to the IMap.
     * <p>
     * The write will be ignored if the timestamp of the given record is older
     * than the timestamp of the stored record. See {@link
     * UpdateJobExecutionRecordEntryProcessor#process}. It will also be ignored
     * if the key doesn't exist in the IMap.
     *
     * @return true if the record was written or ignored because canCreate=false
     *         and there is no record in IMap to update.
     */
    boolean writeJobExecutionRecord(long jobId, JobExecutionRecord record, boolean canCreate) {
        record.updateTimestamp();
        String message = (String) jobExecutionRecords.get().executeOnKey(jobId,
                new UpdateJobExecutionRecordEntryProcessor(jobId, record, canCreate));
        if (message != null) {
            logger.fine(message);
            if (message.endsWith("oldValue == null")) {
                // canCreate=false but there is no record in IMap to update.
                // There is no point in repeating.
                return true;
            }
        }
        return message == null;
    }

    /**
     * Returns map name in the form {@code "_jet.snapshot.<jobId>.<dataMapIndex>"}.
     */
    public static String snapshotDataMapName(long jobId, int dataMapIndex) {
        if (dataMapIndex < 0) {
            throw new IllegalStateException("Negative dataMapIndex - no successful snapshot");
        }

        return SNAPSHOT_DATA_MAP_PREFIX + idToString(jobId) + '.' + dataMapIndex;
    }

    /**
     * Returns the map name in the form {@code __jet.resources.<jobId>}
     */
    public static String jobResourcesMapName(long jobId) {
        return RESOURCES_MAP_NAME_PREFIX + idToString(jobId);
    }

    /**
     * Returns the key name in the form {@code file.<id>}
     */
    public static String fileKeyName(String id) {
        return OS.ensureUnixSeparators(FILE_STORAGE_KEY_NAME_PREFIX + id);
    }

    /**
     * Returns the key name in the form {@code class.<id>}
     */
    public static String classKeyName(String id) {
        return OS.ensureUnixSeparators(CLASS_STORAGE_KEY_NAME_PREFIX + id);
    }

    /**
     * Returns the map name in which an exported snapshot with the given `name`
     * is stored. It's {@code "_jet.exportedSnapshot.<name>"}.
     */
    public static String exportedSnapshotMapName(String name) {
        return JobRepository.EXPORTED_SNAPSHOTS_PREFIX + name;
    }

    void clearSnapshotData(long jobId, int dataMapIndex) {
        String mapName = snapshotDataMapName(jobId, dataMapIndex);
        try {
            instance.getMap(mapName).clear();
            logger.fine("Cleared snapshot data map %s", mapName);
        } catch (Exception logged) {
            logger.warning("Cannot delete old snapshot data  " + idToString(jobId), logged);
        }
    }

    void cacheValidationRecord(@Nonnull String snapshotName, @Nonnull SnapshotValidationRecord validationRecord) {
        try {
            exportedSnapshotDetailsCache.get().set(snapshotName, validationRecord);
        } catch (Exception e) {
            logger.warning("Snapshot name: '" + snapshotName + "', failed to store validation record to cache: " + e, e);
        }
    }

    public static final class UpdateJobExecutionRecordEntryProcessor implements
            EntryProcessor<Long, JobExecutionRecord, Object>,
            IdentifiedDataSerializable {

        private long jobId;
        @SuppressFBWarnings(value = "SE_BAD_FIELD",
                justification = "this class is not going to be java-serialized")
        private JobExecutionRecord jobExecutionRecord;
        private boolean canCreate;

        public UpdateJobExecutionRecordEntryProcessor() {
        }

        UpdateJobExecutionRecordEntryProcessor(long jobId, JobExecutionRecord jobExecutionRecord, boolean canCreate) {
            this.jobId = jobId;
            this.jobExecutionRecord = jobExecutionRecord;
            this.canCreate = canCreate;
        }

        @Override
        public Object process(Entry<Long, JobExecutionRecord> entry) {
            if (entry.getValue() == null && !canCreate) {
                // ignore missing value - this method of updating cannot be used for initial JobRecord creation
                return "Update to JobRecord for job " + idToString(jobId) + " ignored, oldValue == null";
            }
            if (entry.getValue() != null && entry.getValue().getTimestamp() >= jobExecutionRecord.getTimestamp()) {
                // ignore older update.
                // It can happen because we allow to execute updates in parallel, and they can overtake each other.
                // We don't want to overwrite newer update.
                return "Update to JobRecord for job " + idToString(jobId) + " ignored, newer timestamp found. "
                        + "Stored timestamp=" + entry.getValue().getTimestamp() + ", timestamp of the update="
                        + jobExecutionRecord.getTimestamp();
            }
            entry.setValue(jobExecutionRecord);
            return null;
        }

        @Override
        public int getFactoryId() {
            return JetInitDataSerializerHook.FACTORY_ID;
        }

        @Override
        public int getClassId() {
            return JetInitDataSerializerHook.UPDATE_JOB_EXECUTION_RECORD_EP;
        }

        @Override
        public void writeData(ObjectDataOutput out) throws IOException {
            out.writeLong(jobId);
            out.writeObject(jobExecutionRecord);
            out.writeBoolean(canCreate);
        }

        @Override
        public void readData(ObjectDataInput in) throws IOException {
            jobId = in.readLong();
            jobExecutionRecord = in.readObject();
            canCreate = in.readBoolean();
        }
    }

    public static class FilterJobResultByNamePredicate
            implements Predicate<Long, JobResult>, IdentifiedDataSerializable {

        private String name;

        public FilterJobResultByNamePredicate() {
        }

        FilterJobResultByNamePredicate(String name) {
            this.name = name;
        }

        @Override
        public boolean apply(Entry<Long, JobResult> entry) {
            return name.equals(entry.getValue().getJobConfig().getName());
        }

        @Override
        public int getFactoryId() {
            return JetInitDataSerializerHook.FACTORY_ID;
        }

        @Override
        public int getClassId() {
            return JetInitDataSerializerHook.FILTER_JOB_RESULT_BY_NAME;
        }

        @Override
        public void writeData(ObjectDataOutput out) throws IOException {
            out.writeString(name);
        }

        @Override
        public void readData(ObjectDataInput in) throws IOException {
            name = in.readString();
        }
    }
}
