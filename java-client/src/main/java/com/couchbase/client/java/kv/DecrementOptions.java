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
import com.couchbase.client.java.CommonOptions;

import java.util.Optional;

import static com.couchbase.client.core.util.Validators.notNull;

public class DecrementOptions extends CommonOptions<DecrementOptions> {
  public static DecrementOptions DEFAULT = new DecrementOptions();

  private long delta;
  private Optional<Long> initial;
  private int expiry;
  private PersistTo persistTo;
  private ReplicateTo replicateTo;
  private DurabilityLevel durabilityLevel;

  public static DecrementOptions decrementOptions() {
    return new DecrementOptions();
  }

  private DecrementOptions() {
    delta = 1;
    initial = Optional.empty();
    expiry = 0;
  }

  public PersistTo persistTo() {
    return persistTo;
  }


  public ReplicateTo replicateTo() {
    return replicateTo;
  }

  public DecrementOptions withDurability(final PersistTo persistTo, final ReplicateTo replicateTo) {
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

  public DecrementOptions withDurabilityLevel(final DurabilityLevel durabilityLevel) {
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

  public long delta() {
    return delta;
  }

  public DecrementOptions delta(long delta) {
    if (delta < 0) {
      throw new IllegalArgumentException("The delta cannot be less than 0");
    }
    this.delta = delta;
    return this;
  }

  public Optional<Long> initial() {
    return initial;
  }

  public DecrementOptions initial(Optional<Long> initial) {
    this.initial = initial;
    return this;
  }

  public int expiry() {
    return expiry;
  }

  public DecrementOptions expiry(int expiry) {
    this.expiry = expiry;
    return this;
  }

}
