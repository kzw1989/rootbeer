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
package at.illecker.rootbeer.tests.testmap4;

public final class GpuIntegerMap {
  public static final int DEFAULT_CAPACITY = 16;
  private GpuIntegerPair[] m_values = null;
  private boolean m_used = false;

  public GpuIntegerMap() {
    this(DEFAULT_CAPACITY);
  }

  public GpuIntegerMap(int size) {
    this.m_values = new GpuIntegerPair[size];
  }

  public void clear() {
    if (m_used) {
      for (int i = 0; i < m_values.length; i++) {
        m_values[i] = null;
      }
    }
  }

  private boolean equalsKey(GpuIntegerPair entry, int otherKey) {
    if (entry != null) {
      return (entry.getKey() == otherKey);
    }
    return false;
  }

  public int indexForKey(long key) {
    return (int) (key % m_values.length);
  }

  public int get(int key) {
    GpuIntegerPair entry = m_values[indexForKey(key)];
    while (entry != null && !equalsKey(entry, key)) {
      entry = entry.getNext();
    }
    return (entry != null) ? entry.getValue() : null;
  }

  public void put(int key, int value) {
    m_used = true;
    int bucketIndex = indexForKey(key);
    GpuIntegerPair entry = m_values[bucketIndex];
    if (entry != null) {
      boolean done = false;
      while (!done) {
        if (equalsKey(entry, key)) {
          entry.setValue(value);
          done = true;
        } else if (entry.getNext() == null) {
          entry.setNext(new GpuIntegerPair(key, value));
          done = true;
        }
        entry = entry.getNext();
      }
    } else {
      m_values[bucketIndex] = new GpuIntegerPair(key, value);
    }
  }

  public void add(int key, int value) {
    m_used = true;
    int bucketIndex = indexForKey(key);
    GpuIntegerPair entry = m_values[bucketIndex];
    if (entry != null) {
      entry.setValue(entry.getValue() + value);
    } else {
      m_values[bucketIndex] = new GpuIntegerPair(key, value);
    }
  }

}
