/*
 * Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
 */

package com.continuuity.api.flow.flowlet;

import java.util.Map;

/**
 * This interface defines an emitter used for emitting events from
 * within a flowlet.
 */
public interface OutputEmitter<T> {
  /**
   * Emits an event of type T
   * @param data to be emitted by the emitter which is of type T
   */
  void emit(T data);

  /**
   * Emits an event of type T, associated with set of partitions hashes for selecting
   * downstream partitioned consumers ({@link Flowlet}).
   * @param data to be emitted by the emitter which is of type T
   * @param partitions mapping from partition key to object, which the {@link Object#hashCode()}
   *                   of the object value would be triggered to compute the actual partition value.
   */
  void emit(T data, Map<String, Object> partitions);
}
