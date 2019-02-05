/*
 * Copyright (c) 2018 Couchbase, Inc.
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

package com.couchbase.client.java.kv;

import com.couchbase.client.core.msg.kv.DurabilityLevel;
import com.couchbase.client.core.msg.kv.ReplicaGetRequest;
import com.couchbase.client.java.CommonOptions;
import com.couchbase.client.java.codec.DefaultEncoder;
import com.couchbase.client.java.codec.Encoder;

import java.time.Duration;

import static com.couchbase.client.core.util.Validators.notNull;

public class UpsertOptions extends CommonOptions<UpsertOptions> {

  public static UpsertOptions DEFAULT = new UpsertOptions();

  private Duration expiry = Duration.ZERO;
  private PersistTo persistTo;
  private ReplicateTo replicateTo;
  private DurabilityLevel durabilityLevel;
  private Encoder encoder = DefaultEncoder.INSTANCE;

  private UpsertOptions() { }

  public static UpsertOptions upsertOptions() {
    return new UpsertOptions();
  }


  public Duration expiry() {
    return expiry;
  }

  public UpsertOptions expiry(final Duration expiry) {
    this.expiry = expiry;
    return this;
  }


  public PersistTo persistTo() {
    return persistTo;
  }


  public ReplicateTo replicateTo() {
    return replicateTo;
  }

  public UpsertOptions withDurability(final PersistTo persistTo, final ReplicateTo replicateTo) {
    notNull(persistTo, "PersistTo");
    notNull(persistTo, "ReplicateTo");
    if (durabilityLevel != null) {
      throw new IllegalStateException("Durability and DurabilityLevel cannot be set both at " +
        "the same time!");
    }
    this.persistTo = persistTo;
    this.replicateTo = replicateTo;
    return this;
  }

  public UpsertOptions withDurabilityLevel(final DurabilityLevel durabilityLevel) {
    notNull(persistTo, "DurabilityLevel");
    if (persistTo != null || replicateTo != null) {
      throw new IllegalStateException("Durability and DurabilityLevel cannot be set both at " +
        "the same time!");
    }
    this.durabilityLevel = durabilityLevel;
    return this;
  }

  public DurabilityLevel durabilityLevel() {
    return durabilityLevel;
  }

  public Encoder encoder() {
    return encoder;
  }

  public UpsertOptions encoder(final Encoder encoder) {
    notNull(encoder, "Encoder");

    this.encoder = encoder;
    return this;
  }
}
