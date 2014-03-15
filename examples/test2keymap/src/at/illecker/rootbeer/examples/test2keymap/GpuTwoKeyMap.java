/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package at.illecker.rootbeer.examples.test2keymap;

public final class GpuTwoKeyMap {
  public static final int DEFAULT_CAPACITY = 16;
  private GpuKeyValuePair[] m_values = null;

  public GpuTwoKeyMap() {
    this(DEFAULT_CAPACITY);
  }

  public GpuTwoKeyMap(int size) {
    this.m_values = new GpuKeyValuePair[size];
  }

  private boolean equalsKey(GpuKeyValuePair entry, long userId, long itemId) {
    if (entry != null) {
      GpuLongLongPair key = entry.getKey();
      if (key != null) {
        return (key.getKey() == userId) && (key.getValue() == itemId);
      }
    }
    return false;
  }

  public int indexForKey(long a, long b) {
    // Cantor pairing function
    // (a + b) * (a + b + 1) / 2 + a; where a, b >= 0
    // long val = (long) ((0.5 * (a + b) * (a + b + 1)) + b);

    // Szudzik's function
    // a >= b ? a * a + a + b : a + b * b; where a, b >= 0
    long val = a >= b ? a * a + a + b : a + b * b;

    return (int) val % m_values.length;
  }

  public Double get(long userId, long itemId) {
    GpuKeyValuePair entry = m_values[indexForKey(userId, itemId)];
    while (entry != null && !equalsKey(entry, userId, itemId)) {
      entry = entry.getNext();
    }
    return (entry != null) ? (Double) entry.getValue() : null;
  }

  public synchronized void put(long userId, long itemId, double value) {
    int bucketIndex = indexForKey(userId, itemId);
    GpuKeyValuePair entry = m_values[bucketIndex];
    if (entry != null) {
      boolean done = false;
      while (!done) {
        if (equalsKey(entry, userId, itemId)) {
          entry.setValue(value);
          done = true;
        } else if (entry.getNext() == null) {
          entry.setNext(new GpuKeyValuePair(
              new GpuLongLongPair(userId, itemId), value));
          done = true;
        }
        entry = entry.getNext();
      }
    } else {
      m_values[bucketIndex] = new GpuKeyValuePair(new GpuLongLongPair(userId,
          itemId), value);
    }
  }

}
