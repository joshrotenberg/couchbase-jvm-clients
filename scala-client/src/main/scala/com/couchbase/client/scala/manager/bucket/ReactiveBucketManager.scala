/*
 * Copyright (c) 2019 Couchbase, Inc.
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
package com.couchbase.client.scala.manager.bucket

import java.nio.charset.StandardCharsets

import com.couchbase.client.core.Core
import com.couchbase.client.core.annotation.Stability.Volatile
import com.couchbase.client.core.deps.io.netty.handler.codec.http.HttpMethod.{DELETE, GET, POST}
import com.couchbase.client.core.error.{
  BucketExistsException,
  BucketNotFoundException,
  CouchbaseException,
  InvalidArgumentException
}
import com.couchbase.client.core.logging.RedactableArgument.redactMeta
import com.couchbase.client.core.msg.ResponseStatus
import com.couchbase.client.core.retry.RetryStrategy
import com.couchbase.client.core.util.UrlQueryStringBuilder
import com.couchbase.client.core.util.UrlQueryStringBuilder.urlEncode
import com.couchbase.client.scala.durability.Durability.{
  Disabled,
  Majority,
  MajorityAndPersistToActive,
  PersistToMajority
}
import com.couchbase.client.scala.json.JsonObject
import com.couchbase.client.scala.manager.ManagerUtil
import com.couchbase.client.scala.manager.bucket.BucketType.{Couchbase, Ephemeral, Memcached}
import com.couchbase.client.scala.manager.bucket.EjectionMethod.{
  FullEviction,
  NoEviction,
  NotRecentlyUsed,
  ValueOnly
}
import com.couchbase.client.scala.util.CouchbasePickler
import com.couchbase.client.scala.util.DurationConversions._
import reactor.core.scala.publisher.{SFlux, SMono}

import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

@Volatile
class ReactiveBucketManager(core: Core) {
  private[scala] val defaultManagerTimeout =
    core.context().environment().timeoutConfig().managementTimeout()
  private[scala] val defaultRetryStrategy = core.context().environment().retryStrategy()

  private def pathForBuckets = "/pools/default/buckets/"

  private def pathForBucket(bucketName: String) = pathForBuckets + urlEncode(bucketName)

  private def pathForBucketFlush(bucketName: String) = {
    "/pools/default/buckets/" + urlEncode(bucketName) + "/controller/doFlush"
  }

  def create(
      settings: CreateBucketSettings,
      timeout: Duration = defaultManagerTimeout,
      retryStrategy: RetryStrategy = defaultRetryStrategy
  ): SMono[Unit] = {
    createUpdateBucketShared(settings, pathForBuckets, timeout, retryStrategy, update = false)
  }

  private def createUpdateBucketShared(
      settings: CreateBucketSettings,
      path: String,
      timeout: Duration,
      retryStrategy: RetryStrategy,
      update: Boolean
  ): SMono[Unit] = {
    checkValidEjectionMethod(settings) match {
      case Success(_) =>
        val params = convertSettingsToParams(settings, update)

        ManagerUtil
          .sendRequest(core, POST, path, params, timeout, retryStrategy)
          .flatMap(response => {
            if ((response.status == ResponseStatus.INVALID_ARGS) && response.content != null) {
              val content = new String(response.content, StandardCharsets.UTF_8)
              if (content.contains("Bucket with given name already exists")) {
                SMono.raiseError(BucketExistsException.forBucket(settings.name))
              } else {
                SMono.raiseError(new CouchbaseException(content))
              }
            } else {
              ManagerUtil
                .checkStatus(response, "create bucket [" + redactMeta(settings) + "]") match {
                case Failure(err) => SMono.raiseError(err)
                case _            => SMono.just(())
              }
            }
          })
      case Failure(err) => SMono.raiseError(err)
    }
  }

  private def checkValidEjectionMethod(settings: CreateBucketSettings): Try[Unit] = {
    val validEjectionType = settings.ejectionMethod match {
      case Some(FullEviction) | Some(ValueOnly) =>
        settings.bucketType match {
          case Some(Couchbase) | None => true
          case _                      => false
        }
      case Some(NoEviction) | Some(NotRecentlyUsed) =>
        settings.bucketType match {
          case Some(Ephemeral) => true
          case _               => false
        }
      case None => true
    }

    if (!validEjectionType) {
      Failure(
        new InvalidArgumentException(
          s"Cannot use ejection policy ${settings.ejectionMethod} together with bucket type ${settings.bucketType
            .getOrElse(Couchbase)}",
          null,
          null
        )
      );
    } else {
      Success(())
    }
  }

  def updateBucket(
      settings: CreateBucketSettings,
      timeout: Duration = defaultManagerTimeout,
      retryStrategy: RetryStrategy = defaultRetryStrategy
  ): SMono[Unit] = {
    checkValidEjectionMethod(settings) match {
      case Success(_) =>
        getAllBuckets(timeout, retryStrategy)
          .collectSeq()
          .map(buckets => buckets.exists(_.name == settings.name))
          .flatMap(bucketExists => {
            createUpdateBucketShared(
              settings,
              pathForBucket(settings.name),
              timeout,
              retryStrategy,
              bucketExists
            )
          })

      case Failure(err) => SMono.raiseError(err)
    }
  }

  private def convertSettingsToParams(settings: CreateBucketSettings, update: Boolean) = {
    val params = UrlQueryStringBuilder.createForUrlSafeNames
    params.add("ramQuotaMB", settings.ramQuotaMB)
    settings.bucketType match {
      case Some(Memcached) =>
      case _ =>
        settings.numReplicas.foreach(v => params.add("replicaNumber", v))
    }
    settings.flushEnabled.foreach(v => params.add("flushEnabled", if (v) 1 else 0))
    settings.maxTTL.foreach(v => params.add("maxTTL", v))
    settings.ejectionMethod.foreach(v => params.add("evictionPolicy", v.alias))
    settings.compressionMode.foreach(v => params.add("compressionMode", v.alias))

    settings.minimumDurabilityLevel
      .filterNot(d => d == Disabled)
      .map {
        case Majority                   => "majority"
        case MajorityAndPersistToActive => "majorityAndPersistActive"
        case PersistToMajority          => "persistToMajority"
        case _                          => throw new IllegalStateException("Unknown durability")
      }
      .foreach(v => params.add("durabilityMinLevel", v))

    // The following values must not be changed on update
    if (!update) {
      params.add("name", settings.name)
      settings.bucketType.foreach(v => params.add("bucketType", v.alias))
      settings.conflictResolutionType.foreach(v => params.add("conflictResolutionType", v.alias))
      settings.bucketType match {
        case Some(Ephemeral) =>
        case _ =>
          settings.replicaIndexes.foreach(v => params.add("replicaIndex", if (v) 1 else 0))
      }
      settings.storageBackend match {
        case Some(StorageBackend.Couchstore) => params.add("storageBackend", "couchstore")
        case Some(StorageBackend.Magma)      => params.add("storageBackend", "magma")
        case _                               =>
      }
    }
    params
  }

  def dropBucket(
      bucketName: String,
      timeout: Duration = defaultManagerTimeout,
      retryStrategy: RetryStrategy = defaultRetryStrategy
  ): SMono[Unit] = {
    ManagerUtil
      .sendRequest(core, DELETE, pathForBucket(bucketName), timeout, retryStrategy)
      .flatMap(response => {
        if (response.status == ResponseStatus.NOT_FOUND) {
          SMono.raiseError(BucketNotFoundException.forBucket(bucketName))
        } else {
          ManagerUtil.checkStatus(response, "drop bucket [" + redactMeta(bucketName) + "]") match {
            case Failure(err) => SMono.raiseError(err)
            case _            => SMono.just(())
          }
        }
      })
  }

  def getBucket(
      bucketName: String,
      timeout: Duration = defaultManagerTimeout,
      retryStrategy: RetryStrategy = defaultRetryStrategy
  ): SMono[BucketSettings] = {
    ManagerUtil
      .sendRequest(core, GET, pathForBucket(bucketName), timeout, retryStrategy)
      .flatMap(response => {
        if (response.status == ResponseStatus.NOT_FOUND) {
          SMono.raiseError(BucketNotFoundException.forBucket(bucketName))
        } else {
          ManagerUtil.checkStatus(response, "get bucket [" + redactMeta(bucketName) + "]") match {
            case Failure(err) => SMono.raiseError(err)
            case _ =>
              val bs = BucketSettings.parseFrom(response.content)
              SMono.just(bs)
          }
        }
      })
  }

  def getAllBuckets(
      timeout: Duration = defaultManagerTimeout,
      retryStrategy: RetryStrategy = defaultRetryStrategy
  ): SFlux[BucketSettings] = {
    ManagerUtil
      .sendRequest(core, GET, pathForBuckets, timeout, retryStrategy)
      .flatMapMany(response => {
        ManagerUtil.checkStatus(response, "get all buckets") match {
          case Failure(err) => SFlux.raiseError(err)
          case _ =>
            val bs = BucketSettings.parseSeqFrom(response.content)
            SFlux.fromIterable(bs)
        }
      })
  }

  def flushBucket(
      bucketName: String,
      timeout: Duration = defaultManagerTimeout,
      retryStrategy: RetryStrategy = defaultRetryStrategy
  ): SMono[Unit] = {
    ManagerUtil
      .sendRequest(core, POST, pathForBucketFlush(bucketName), timeout, retryStrategy)
      .flatMap(response => {
        if (response.status == ResponseStatus.NOT_FOUND) {
          SMono.raiseError(BucketNotFoundException.forBucket(bucketName))
        } else {
          ManagerUtil.checkStatus(response, "flush bucket [" + redactMeta(bucketName) + "]") match {
            case Failure(err) => SMono.raiseError(err)
            case _            => SMono.just(())
          }
        }
      })
  }
}
