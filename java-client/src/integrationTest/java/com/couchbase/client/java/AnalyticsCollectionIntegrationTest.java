/*
 * Copyright (c) 2020 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.java;

import com.couchbase.client.core.error.IndexExistsException;
import com.couchbase.client.core.error.ParsingFailureException;
import com.couchbase.client.core.error.ScopeNotFoundException;
import com.couchbase.client.core.service.ServiceType;
import com.couchbase.client.java.analytics.AnalyticsMetaData;
import com.couchbase.client.java.analytics.AnalyticsResult;
import com.couchbase.client.java.analytics.AnalyticsStatus;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.manager.analytics.AnalyticsDataverse;
import com.couchbase.client.java.manager.analytics.AnalyticsIndexManager;
import com.couchbase.client.java.manager.analytics.DisconnectLinkAnalyticsOptions;
import com.couchbase.client.java.manager.analytics.DropDatasetAnalyticsOptions;
import com.couchbase.client.java.manager.collection.CollectionManager;
import com.couchbase.client.java.manager.collection.CollectionSpec;
import com.couchbase.client.java.util.JavaIntegrationTest;
import com.couchbase.client.test.Capabilities;
import com.couchbase.client.test.ClusterType;
import com.couchbase.client.test.IgnoreWhen;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.couchbase.client.core.util.CbCollections.setOf;
import static com.couchbase.client.java.analytics.AnalyticsOptions.analyticsOptions;
import static com.couchbase.client.java.manager.analytics.CreateDatasetAnalyticsOptions.createDatasetAnalyticsOptions;
import static com.couchbase.client.java.manager.analytics.DropDatasetAnalyticsOptions.dropDatasetAnalyticsOptions;
import static com.couchbase.client.java.manager.analytics.DropIndexAnalyticsOptions.dropIndexAnalyticsOptions;
import static com.couchbase.client.test.Util.waitUntilCondition;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the basic functionality of analytics queries in an end-to-end fashion.
 */
@IgnoreWhen(
  missesCapabilities = {Capabilities.ANALYTICS, Capabilities.COLLECTIONS},
  clusterTypes = ClusterType.CAVES
)
class AnalyticsCollectionIntegrationTest extends JavaIntegrationTest {

  private static Cluster cluster;

  private static final String dataverse = "myDataverse";
  private static final String dataset = "myDataset";
  private static final String index = "myIndex";
  private static String scopeName = "myScope" + randomString();
  private static String collectionName = "myCollection" + randomString();
  private static String delimitedDataverseName;


  private static CollectionManager collectionManager;
  private static AnalyticsIndexManager analytics;
  private static Bucket bucket;

  /**
   * Holds sample content for simple assertions.
   */
  private static final JsonObject FOO_CONTENT = JsonObject.create().put("foo", "bar");
  private static final JsonObject DEFAULT_CONTENT = JsonObject.create().put("some", "stuff");

  @BeforeAll
  static void setup() {
    cluster = Cluster.connect(seedNodes(), clusterOptions());
    bucket = cluster.bucket(config().bucketname());
    analytics = cluster.analyticsIndexes();
    collectionManager = bucket.collections();
    bucket.waitUntilReady(Duration.ofSeconds(5));
    waitForService(bucket, ServiceType.ANALYTICS);

    collectionManager.createScope(scopeName);
    CollectionSpec collSpec = CollectionSpec.create(collectionName, scopeName);
    collectionManager.createCollection(collSpec);
    waitUntilCondition(() -> collectionExists(collectionManager, collSpec));

    waitForQueryIndexerToHaveBucket(cluster, config().bucketname());

    // this inserts two documents in bucket.scope.collection and creates a primary index.
    // then inserts one document in bucket._default._default and creates a primary index.

    cluster.query("insert into `" + config().bucketname() + "`.`" + scopeName + "`.`" + collectionName + "` (key, value ) values ( '123',  { \"test\" : \"hello\" })");
    insertDoc(bucket.scope(scopeName).collection(collectionName), FOO_CONTENT);
    cluster.query("create primary index on `" + config().bucketname() + "`.`" + scopeName + "`.`" + collectionName + "`");
    insertDoc(bucket.defaultCollection(), DEFAULT_CONTENT);
    try {
      cluster.query("create primary index on `" + config().bucketname() + "`.`" + "_default" + "`." + "_default");
    } catch (IndexExistsException e) {
      //Primary index is already created, ignore
    }

    delimitedDataverseName = "`" + config().bucketname() + "`.`" + scopeName + "`";
  }

  @BeforeEach
  void reset() {
    final Set<String> builtIns = setOf("Default", "Metadata");

    getAllDataverseNames().stream()
      .filter(name -> !builtIns.contains(name))
      .forEach(name -> {
        disconnectLocalLink(name);
      });

    getAllDataverseNames().stream()
      .filter(name -> !builtIns.contains(name))
      .forEach(name -> {
        dropDataverse(name);
      });

    // clean up the Default dataverse
    dropAllDatasets();
    dropAllIndexes();
    analytics.disconnectLink();
  }

  private Set<String> getAllDataverseNames() {
    return analytics.getAllDataverses().stream()
      .map(AnalyticsDataverse::name)
      .collect(Collectors.toSet());
  }

  private void disconnectLocalLink(String dvName) {
    DisconnectLinkAnalyticsOptions opts = DisconnectLinkAnalyticsOptions.disconnectLinkAnalyticsOptions()
      .dataverseName(dvName)
      .linkName("Local");
    analytics.disconnectLink(opts);
  }

  private void dropAllDatasets() {
    analytics.getAllDatasets().forEach(ds ->
      dropDataset(ds.name(), dropDatasetAnalyticsOptions()
        .dataverseName(ds.dataverseName())));
  }

  private void dropDataset(String name, DropDatasetAnalyticsOptions dataversOpts) {
    analytics.dropDataset(name, dataversOpts);
  }

  private void dropDataverse(String name) {
    analytics.dropDataverse(name);
  }

  private void dropAllIndexes() {
    analytics.getAllIndexes().forEach(idx ->
      analytics.dropIndex(idx.name(), idx.datasetName(), dropIndexAnalyticsOptions()
        .dataverseName(idx.dataverseName())));
  }


  private static boolean dataverseExists(Cluster cluster, String dataverse) {
    try {
      AnalyticsResult result = cluster.analyticsQuery("SELECT DataverseName FROM Metadata.`Dataverse` where DataverseName=\"" + dataverse + "\"");
      return result.rowsAsObject().size() != 0;
    } catch (ScopeNotFoundException e) {
      return false;
    }
  }

  @AfterAll
  static void tearDown() {
    cluster.disconnect();
  }

  @Test
  void createDataset() {
    analytics.createDataset(dataset, bucket.name()); // default

    analytics.createDataverse(dataverse);
    analytics.createDataset(dataset, bucket.name(),
      createDatasetAnalyticsOptions()
        .dataverseName(dataverse));

    Set<String> actual = analytics.getAllDatasets().stream()
      .map(ds -> ds.dataverseName() + "::" + ds.name())
      .collect(Collectors.toSet());
    assertEquals(setOf("Default::" + dataset, dataverse + "::" + dataset), actual);
  }

  @Test
  void performsDataverseQuery() {
    analytics.createDataset(dataset, bucket.name()); // default
    analytics.createDataverse(dataverse);
    analytics.createDataset(dataset, bucket.name(),
      createDatasetAnalyticsOptions()
        .dataverseName(dataverse));

    // REQUEST_PLUS makes query hang
    // AnalyticsOptions opts = AnalyticsOptions.analyticsOptions().scanConsistency(AnalyticsScanConsistency.REQUEST_PLUS).timeout(Duration.ofSeconds(300));

    AnalyticsResult result = cluster.analyticsQuery("SELECT * FROM " + dataverse + "." + dataset + " where " + dataset + ".test= \"hello\"");

    List<JsonObject> rows = result.rowsAs(JsonObject.class);
    assertFalse(!rows.isEmpty());

    AnalyticsMetaData meta = result.metaData();
    assertFalse(meta.clientContextId().isEmpty());
    assertTrue(meta.signature().isPresent());
    assertFalse(meta.requestId().isEmpty());
    assertEquals(AnalyticsStatus.SUCCESS, meta.status());

    assertFalse(meta.metrics().elapsedTime().isZero());
    assertFalse(meta.metrics().executionTime().isZero());
    assertEquals(rows.size(), meta.metrics().resultCount());
    // assertEquals(rows.size(), meta.metrics().processedObjects()); // fails
    // assertTrue(meta.metrics().resultSize() > 0); // fails
    assertTrue(meta.warnings().isEmpty());
    // assertEquals(1, meta.metrics().errorCount()); //fails
  }

  @Test
  void performsDataverseCollectionQuery() {
    cluster.analyticsQuery("ALTER COLLECTION `" + bucket.name() + "`.`" + scopeName + "`.`" + collectionName + "` ENABLE ANALYTICS");

    // REQUEST_PLUS makes query hang
    // AnalyticsOptions opts = AnalyticsOptions.analyticsOptions().scanConsistency(AnalyticsScanConsistency.REQUEST_PLUS);

    //Ensure doc ingested by analytics
    waitUntilCondition(() -> singletonMap(delimitedDataverseName, singletonMap(collectionName, 0L)).equals(analytics.getPendingMutations()));

    Scope scope = cluster.bucket(config().bucketname()).scope(scopeName);
    AnalyticsResult result = scope.analyticsQuery("SELECT * FROM `" + bucket.name() + "`.`" + scopeName + "`.`" + collectionName + "` WHERE `" + collectionName + "`.foo=\"bar\"");

    List<JsonObject> rows = result.rowsAs(JsonObject.class);
    assertFalse(rows.isEmpty());
  }

  @Test
  void performsDataverseCollectionQueryWithQueryContext() {
    cluster.analyticsQuery("ALTER COLLECTION `" + bucket.name() + "`.`" + scopeName + "`.`" + collectionName + "` ENABLE ANALYTICS");

    //Ensure doc ingested by analytics
    waitUntilCondition(() -> singletonMap(delimitedDataverseName, singletonMap(collectionName, 0L)).equals(analytics.getPendingMutations()));

    //AnalyticsOptions opts = AnalyticsOptions.analyticsOptions();
    Scope scope = cluster.bucket(config().bucketname()).scope(scopeName);
    AnalyticsResult result = scope.analyticsQuery("SELECT * FROM `" + collectionName + "` where `" + collectionName + "`.foo= \"bar\"");

    List<JsonObject> rows = result.rowsAs(JsonObject.class);
    assertFalse(rows.isEmpty());
  }

  @Test
  void failsOnError() {
    assertThrows(ParsingFailureException.class, () -> cluster.analyticsQuery("SELECT 1="));
  }

  @Test
  void canSetCustomContextId() {
    String contextId = "mycontextid";
    AnalyticsResult result = cluster.analyticsQuery(
      "SELECT DataverseName FROM Metadata.`Dataverse`",
      analyticsOptions().clientContextId(contextId)
    );
    assertEquals(result.metaData().clientContextId(), contextId);
  }

  private static String randomString() {
    return UUID.randomUUID().toString().substring(0, 10);
  }

  /**
   * Inserts a document into the collection and returns the ID of it. It inserts {@link #FOO_CONTENT}.
   */
  public static String insertDoc(Collection collection, JsonObject content) {
    String id = UUID.randomUUID().toString();
    collection.insert(id, content);
    return id;
  }
}
