/*
 * Copyright 2013 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.multiway;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import com.github.benmanes.multiway.ResourceKey.Status;
import com.google.common.base.Ticker;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A policy that enforces that a resource may reside idle in the pool for up to a specified time
 * limit. A resource is considered idle when it is available to be borrowed, resulting in expiration
 * if the duration since the previous release exceeds a threshold.
 *
 * @author Ben Manes (ben.manes@gmail.com)
 */
@ThreadSafe
final class TimeToIdlePolicy<K, R> {
  final LinkedDeque<ResourceKey<K>> idleQueue;
  final EvictionListener<K> evictionListener;
  final long expireAfterAccessNanos;
  final Queue<Runnable> taskQueue;
  final Lock idleLock;
  final Ticker ticker;

  TimeToIdlePolicy(long expireAfterAccessNanos,
      Ticker ticker, EvictionListener<K> evictionListener) {
    checkArgument(expireAfterAccessNanos >= 0L);

    this.ticker = checkNotNull(ticker);
    this.idleQueue = new LinkedDeque<>();
    this.idleLock = new ReentrantLock();
    this.taskQueue = new ConcurrentLinkedQueue<>();
    this.expireAfterAccessNanos = expireAfterAccessNanos;
    this.evictionListener = checkNotNull(evictionListener);
  }

  /** Adds an idle resource to be tracked for expiration. */
  void add(ResourceKey<K> key) {
    schedule(new AddTask<K>(idleQueue, key, ticker.read()));
  }

  /** Removes a resource that is no longer idle. */
  void invalidate(ResourceKey<K> key) {
    schedule(new RemovalTask<K>(idleQueue, key));
  }

  /** Schedules the task to be applied to the idle policy. */
  void schedule(Runnable task) {
    taskQueue.add(task);
    cleanUp();
  }

  /** Determines whether the resource has expired. */
  boolean hasExpired(ResourceKey<K> resourceKey) {
    return hasExpired(resourceKey, ticker.read());
  }

  /** Determines whether the resource has expired. */
  boolean hasExpired(ResourceKey<K> resourceKey, long currentTimeNanos) {
    return (currentTimeNanos - resourceKey.getAccessTime()) >= expireAfterAccessNanos;
  }

  /** Performs any pending maintenance operations needed by the policy. */
  void cleanUp() {
    if (idleLock.tryLock()) {
      try {
        drainTaskQueue();
        evict();
      } finally {
        idleLock.unlock();
      }
    }
  }

  /** Applies the pending operations in the task queue. */
  @GuardedBy("idleLock")
  void drainTaskQueue() {
    Runnable task;
    while ((task = taskQueue.poll()) != null) {
      task.run();
    }
  }

  /** Evicts the resources that have exceeded the threshold for remaining idle. */
  @GuardedBy("idleLock")
  void evict() {
    long now = ticker.read();
    ResourceKey<K> resourceKey;
    while ((resourceKey = idleQueue.peek()) != null) {
      if (hasExpired(resourceKey, now)) {
        idleQueue.remove();
        evictionListener.onEviction(resourceKey);
      } else {
        break;
      }
    }
  }

  /** A listener that is invoked when the policy has evicted an expired resource. */
  interface EvictionListener<K> {

    /** A call-back notification that the entry was evicted. */
    void onEviction(ResourceKey<K> resourceKey);
  }

  /** Adds the available resource to the policy to be evaluated for expiration. */
  static final class AddTask<K> implements Runnable {
    final LinkedDeque<ResourceKey<K>> idleQueue;
    final long accessTimeNanos;
    final ResourceKey<K> key;

    AddTask(LinkedDeque<ResourceKey<K>> idleQueue, ResourceKey<K> key, long accessTimeNanos) {
      this.accessTimeNanos = accessTimeNanos;
      this.idleQueue = idleQueue;
      this.key = key;
    }

    @Override
    @GuardedBy("idleLock")
    public void run() {
      if (key.getStatus() == Status.IDLE) {
        key.setAccessTime(accessTimeNanos);
        idleQueue.add(key);
      }
    }
  }

  /** Removes the resource from the policy, e.g. when borrowed or discarded by the pool. */
  static final class RemovalTask<K> implements Runnable {
    final LinkedDeque<ResourceKey<K>> idleQueue;
    final ResourceKey<K> key;

    RemovalTask(LinkedDeque<ResourceKey<K>> idleQueue, ResourceKey<K> key) {
      this.idleQueue = idleQueue;
      this.key = key;
    }

    @Override
    @GuardedBy("idleLock")
    public void run() {
      idleQueue.remove(key);
    }
  }
}