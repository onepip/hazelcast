/*
 * Copyright 2025 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.elastic;

import com.hazelcast.collection.IList;
import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.test.IgnoreInJenkinsOnWindows;
import com.hazelcast.jet.test.SerialTest;
import com.hazelcast.test.HazelcastSerialClassRunner;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.search.SearchHit;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hazelcast.test.DockerTestUtil.assumeDockerEnabled;
import static com.hazelcast.test.HazelcastTestSupport.smallInstanceConfig;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.elasticsearch.client.RequestOptions.DEFAULT;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;

/**
 * Base class for running Elasticsearch connector tests
 *
 * To use implement:
 * - {@link #elasticClientSupplier()}
 * - {@link #createHazelcastInstance()}
 * Subclasses are free to cache
 */
@RunWith(HazelcastSerialClassRunner.class)
@Category({SerialTest.class, IgnoreInJenkinsOnWindows.class})
public abstract class BaseElasticTest {

    protected static final int BATCH_SIZE = 42;

    protected RestHighLevelClient elasticClient;
    protected HazelcastInstance hz;
    protected IList<String> results;

    @BeforeClass
    public static void beforeClassCheckDocker() {
        assumeDockerEnabled();
    }

    @Before
    public void setUpBase() {
        if (elasticClient == null) {
            elasticClient = new RestHighLevelClient(elasticClientSupplier().get());
        }
        cleanElasticData();

        if (hz == null) {
            hz = createHazelcastInstance();
        }
        results = hz.getList("results");
        results.clear();
    }

    @After
    public void tearDown() throws Exception {
        if (elasticClient != null) {
            try {
                elasticClient.close();
            } finally {
                elasticClient = null;
            }
        }
    }

    /**
     * RestHighLevelClient supplier, it is used to create a client before each
     * test for use by all methods from this class interacting with elastic
     */
    protected SupplierEx<RestClientBuilder> elasticClientSupplier() {
        return ElasticSupport.elasticClientSupplier();
    }

    /**
     * RestHighLevelClient supplier, it is used to used as a parameter of
     * {@link ElasticSourceBuilder#clientFn(SupplierEx)}
     */
    protected SupplierEx<RestClientBuilder> elasticPipelineClientSupplier() {
        return ElasticSupport.elasticClientSupplier();
    }

    protected abstract HazelcastInstance createHazelcastInstance();

    /**
     * Creates an index with given name with 3 shards
     */
    protected void initShardedIndex(String index) throws IOException {
        createShardedIndex(index, 3, 0);
        indexBatchOfDocuments(index);
    }

    protected Settings.Builder configureShardedIndices(Settings.Builder settings) {
        return settings.put("index.unassigned.node_left.delayed_timeout", "1s");
    }

    /**
     * Creates an index with given name with 3 shards
     */
    protected void createShardedIndex(String index, int shards, int replicas) throws IOException {
        CreateIndexRequest request = new CreateIndexRequest(index);
        request.settings(configureShardedIndices(Settings.builder()
                  .put("index.number_of_shards", shards)
                  .put("index.number_of_replicas", replicas)));

        elasticClient.indices().create(request, RequestOptions.DEFAULT);
    }

    /**
     * Deletes all documents in all indexes and drops all indexes
     */
    protected void cleanElasticData() {
        try {
            // All documents are deleted when an index is deleted
            elasticClient.indices().delete(new DeleteIndexRequest("*"), DEFAULT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Deletes all documents in all indexes
     */
    protected void deleteDocuments() throws IOException {
        DeleteByQueryRequest request = new DeleteByQueryRequest("*")
                .setQuery(matchAllQuery())
                .setRefresh(true);
        elasticClient.deleteByQuery(request, DEFAULT);
    }

    /**
     * Indexes a batch of documents to an index with given name
     */
    protected List<String> indexBatchOfDocuments(String index) {
        return indexBatchOfDocuments(index, CommonElasticSourcesTest.BATCH_SIZE);
    }

    /**
     * Indexes a batch of documents to an index with given name
     */
    protected List<String> indexBatchOfDocuments(String index, int batchSize) {
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            docs.add(Map.of("title", "document " + i));
        }
        return indexDocuments(index, docs);
    }

    /**
     * Indexes a given document to an index with given name
     */
    protected String indexDocument(String index, Map<String, Object> document) {
        return indexDocuments(index, List.of(document)).get(0);
    }

    /**
     * Indexes a given list of documents to an index with given name
     */
    protected List<String> indexDocuments(String index, List<Map<String, Object>> documents) {
        BulkRequest request = new BulkRequest()
                .setRefreshPolicy(RefreshPolicy.IMMEDIATE);

        for (Map<String, Object> document : documents) {
            request.add(new IndexRequest(index)
                    .source(document));
        }

        try {
            BulkResponse response = elasticClient.bulk(request, RequestOptions.DEFAULT);
            return Arrays.stream(response.getItems())
                         .map(BulkItemResponse::getId)
                         .collect(Collectors.toList());

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected void refreshIndex() throws IOException {
        // Need to refresh index because the default bulk request doesn't do it and we may not see the result
        elasticClient.indices().refresh(new RefreshRequest("my-index"), DEFAULT);
    }

    protected void assertSingleDocument() throws IOException {
        assertSingleDocument("id", "Frantisek");
    }

    protected void assertSingleDocument(String id, String name) throws IOException {
        SearchResponse response = elasticClient.search(new SearchRequest("my-index"), DEFAULT);
        SearchHit[] hits = response.getHits().getHits();
        assertThat(hits).hasSize(1);
        Map<String, Object> document = hits[0].getSourceAsMap();
        assertThat(document).contains(
                entry("id", id),
                entry("name", name)
        );
    }

    protected void assertNoDocuments(String index) throws IOException {
        SearchResponse response = elasticClient.search(new SearchRequest(index), DEFAULT);
        SearchHit[] hits = response.getHits().getHits();
        assertThat(hits).hasSize(0);
    }

    /**
     * Creates a new job from given Pipeline
     *
     * Adds this.getClass to config so any lambdas used in a test class can be deserialized when run in remote cluster.
     */
    protected void submitJob(Pipeline p) {
        Job job = submitJobNoWait(p);
        job.join();
    }

    protected Job submitJobNoWait(Pipeline p) {
        JobConfig config = new JobConfig();

        Class<?> clazz = this.getClass();
        while (clazz.getSuperclass() != null) {
            config.addClass(clazz);
            clazz = clazz.getSuperclass();
        }

        return hz.getJet().newJob(p, config);
    }

    protected static Config config() {
        Config config = smallInstanceConfig();
        config.getJetConfig().setResourceUploadEnabled(true);
        return config;
    }
}
