/*
 * Copyright 2019 Couchbase, Inc.
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

package com.couchbase.client.java.manager.collection;

import com.couchbase.client.core.env.IoConfig;
import com.couchbase.client.core.error.CollectionExistsException;
import com.couchbase.client.core.error.CollectionNotFoundException;
import com.couchbase.client.core.error.ScopeExistsException;
import com.couchbase.client.core.error.ScopeNotFoundException;
import com.couchbase.client.core.service.ServiceType;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.ClusterOptions;
import com.couchbase.client.java.env.ClusterEnvironment;
import com.couchbase.client.java.util.JavaIntegrationTest;
import com.couchbase.client.test.Capabilities;
import com.couchbase.client.test.IgnoreWhen;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.couchbase.client.test.Util.waitUntilCondition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@IgnoreWhen(missesCapabilities = Capabilities.COLLECTIONS)
class CollectionManagerIntegrationTest extends JavaIntegrationTest {

  private static Cluster cluster;
  private static ClusterEnvironment environment;
  private static CollectionManager collections;

  @BeforeAll
  static void setup() {
    environment = environment().ioConfig(IoConfig.captureTraffic(ServiceType.MANAGER)).build();
    cluster = Cluster.connect(seedNodes(), ClusterOptions.clusterOptions(authenticator()).environment(environment));
    Bucket bucket = cluster.bucket(config().bucketname());
    collections = bucket.collections();
    bucket.waitUntilReady(Duration.ofSeconds(5));
  }

  @AfterAll
  static void tearDown() {
    cluster.disconnect();
    environment.shutdown();
  }

  @Test
  void shouldCreateScopeAndCollection() {
    String scopeName = randomString();
    String collection = randomString();
    CollectionSpec collSpec = CollectionSpec.create(collection, scopeName);
    ScopeSpec scopeSpec = ScopeSpec.create(scopeName);

    assertFalse(collectionExists(collections, collSpec));
    assertThrows(ScopeNotFoundException.class, () -> collections.createCollection(collSpec));

    collections.createScope(scopeName);

    waitUntilCondition(() -> scopeExists(collections, scopeName));
    Optional<ScopeSpec> scope = collections.getAllScopes().stream().filter(ss -> ss.name().equals(scopeName)).findFirst();
    assertEquals(scopeSpec, scope.get());

    collections.createCollection(collSpec);
    waitUntilCondition(() -> collectionExists(collections, collSpec));
    scope = collections.getAllScopes().stream().filter(ss -> ss.name().equals(scopeName)).findFirst();
    assertTrue(scope.get().collections().contains(collSpec));
  }

  @Test
  void shouldThrowWhenScopeAlreadyExists() {
    String scope = randomString();

    collections.createScope(scope);
    waitUntilCondition(() -> scopeExists(collections, scope));
    assertThrows(ScopeExistsException.class, () -> collections.createScope(scope));
  }

  @Test
  void shouldThrowWhenCollectionAlreadyExists() {
    String scope = randomString();
    collections.createScope(scope);
    waitUntilCondition(() -> scopeExists(collections, scope));

    CollectionSpec collectionSpec = CollectionSpec.create(randomString(), scope);
    collections.createCollection(collectionSpec);

    assertThrows(CollectionExistsException.class, () -> collections.createCollection(collectionSpec));
  }

  @Test
  void shouldDropScopeAndCollections() {
    String scope = randomString();
    String collection1 = randomString();
    CollectionSpec collectionSpec1 = CollectionSpec.create(collection1, scope);
    String collection2 = randomString();
    CollectionSpec collectionSpec2 = CollectionSpec.create(collection2, scope);


    assertThrows(ScopeNotFoundException.class, () -> collections.dropScope("foobar"));
    assertThrows(ScopeNotFoundException.class, () -> collections.dropCollection(collectionSpec1));

    collections.createScope(scope);
    waitUntilCondition(() -> scopeExists(collections, scope));

    collections.createCollection(collectionSpec1);
    collections.createCollection(collectionSpec2);

    collections.dropCollection(collectionSpec1);
    waitUntilCondition(() -> !collectionExists(collections, collectionSpec1));
    assertThrows(CollectionNotFoundException.class, () -> collections.dropCollection(collectionSpec1));

    collections.dropScope(scope);
    waitUntilCondition(() -> !scopeExists(collections, scope));

    assertThrows(ScopeNotFoundException.class, () -> collections.dropCollection(collectionSpec2));
  }

  @Test
  @IgnoreWhen(missesCapabilities = Capabilities.ENTERPRISE_EDITION)
  void shouldCreateCollectionWithMaxExpiry() {
    String scope = randomString();
    String collection1 = randomString();
    CollectionSpec collectionSpec1 = CollectionSpec.create(collection1, scope, Duration.ofSeconds(30));
    String collection2 = randomString();
    CollectionSpec collectionSpec2 = CollectionSpec.create(collection2, scope);

    collections.createScope(scope);
    waitUntilCondition(() -> scopeExists(collections, scope));

    collections.createCollection(collectionSpec1);
    collections.createCollection(collectionSpec2);

    waitUntilCondition(() -> collectionExists(collections, collectionSpec1));
    waitUntilCondition(() -> collectionExists(collections, collectionSpec2));

    for (ScopeSpec ss : collections.getAllScopes()) {
      if (!ss.name().equals(scope)) {
        continue;
      }

      for (CollectionSpec cs : ss.collections()) {
        if (cs.name().equals(collection1)) {
          assertEquals(cs.maxExpiry(), Duration.ofSeconds(30));
        } else if (cs.name().equals(collection2)) {
          assertEquals(cs.maxExpiry(), Duration.ZERO);
        }
      }
    }
  }

  @Test
  void createMaxNumOfCollections() {
    String scopeName = randomString();
    int collectionsPerScope = 10;
    ScopeSpec scopeSpec = ScopeSpec.create(scopeName);
    collections.createScope(scopeName);
    waitUntilCondition(() -> scopeExists(collections, scopeName));

    List<ScopeSpec> scopeList = collections.getAllScopes();
    ScopeSpec scope = null;
    for (ScopeSpec sc : scopeList) {
      if (scopeName.equals(sc.name())) {
        scope = sc;
        break;
      }
    }

    assertEquals(scopeSpec, scope);
    for (int i = 0; i < collectionsPerScope; i++) {
      CollectionSpec collectionSpec = CollectionSpec.create(String.valueOf(collectionsPerScope + i), scopeName);
      collections.createCollection(collectionSpec);
      waitUntilCondition(() -> collectionExists(collections, collectionSpec));
      assertTrue(collections.getAllScopes().stream().anyMatch(ss -> ss.collections().contains(collectionSpec)));
    }
  }

  /**
   * This test tries to create a collection under a scope which does not exist.
   */
  @Test
  void failCollectionOpIfScopeNotFound() {
    assertThrows(
      ScopeNotFoundException.class,
      () -> collections.createCollection(CollectionSpec.create("jesse", "dude-where-is-my-scope"))
    );

    assertThrows(
      ScopeNotFoundException.class,
      () ->  collections.dropCollection(CollectionSpec.create("chester", "dude-where-is-my-scope"))
    );
  }

  /**
   * Creates a random string in the right size for collection and scope names, which only support
   * up to 30 chars it seems.
   *
   * @return the random string to use
   */
  private String randomString() {
    return UUID.randomUUID().toString().substring(0, 10);
  }

}
