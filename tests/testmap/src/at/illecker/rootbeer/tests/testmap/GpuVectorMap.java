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
package at.illecker.rootbeer.tests.testmap;

public final class GpuVectorMap {
  public static final int DEFAULT_CAPACITY = 16;
  private GpuLongVectorPair[] m_values = null;
  private boolean m_used = false;

  public GpuVectorMap() {
    this(DEFAULT_CAPACITY);
  }

  public GpuVectorMap(int size) {
    this.m_values = new GpuLongVectorPair[size];
  }

  public int size() {
    return m_values.length;
  }

  public void clear() {
    if (m_used) {
      for (int i = 0; i < m_values.length; i++) {
        m_values[i] = null;
      }
    }
  }

  private boolean equalsKey(GpuLongVectorPair entry, long otherKey) {
    if (entry != null) {
      return (entry.getKey() == otherKey);
    }
    return false;
  }

  public int indexForKey(long key) {
    return (int) (key % m_values.length);
  }

  public double[] get(long key) {
    GpuLongVectorPair entry = m_values[indexForKey(key)];
    while (entry != null && !equalsKey(entry, key)) {
      entry = entry.getNext();
    }
    return (entry != null) ? entry.getValue() : null;
  }

  public synchronized void put(long key, double[] value) {
    m_used = true;
    int bucketIndex = indexForKey(key);
    GpuLongVectorPair entry = m_values[bucketIndex];
    if (entry != null) {
      boolean done = false;
      while (!done) {
        if (equalsKey(entry, key)) {
          entry.setValue(value);
          done = true;
        } else if (entry.getNext() == null) {
          entry.setNext(new GpuLongVectorPair(key, value));
          done = true;
        }
        entry = entry.getNext();
      }
    } else {
      m_values[bucketIndex] = new GpuLongVectorPair(key, value);
    }
  }

  public synchronized void add(long key, double[] value) {
    m_used = true;
    int bucketIndex = indexForKey(key);
    GpuLongVectorPair entry = m_values[bucketIndex];
    if (entry != null) {
      boolean done = false;
      while (!done) {
        if (equalsKey(entry, key)) {
          double[] vector = (double[]) entry.getValue();
          for (int i = 0; i < vector.length; i++) {
            vector[i] += value[i];
          }
          entry.setValue(vector);
          done = true;
        } else if (entry.getNext() == null) {
          entry.setNext(new GpuLongVectorPair(key, value));
          done = true;
        }
        entry = entry.getNext();
      }
    } else {
      m_values[bucketIndex] = new GpuLongVectorPair(key, value);
    }
  }

}
