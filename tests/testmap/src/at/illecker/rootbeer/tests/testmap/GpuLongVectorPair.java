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

/**
 * Mutable class for key values.
 */
public class GpuLongVectorPair {

  private long m_key;
  private double[] m_value;
  private GpuLongVectorPair m_next;

  public GpuLongVectorPair(long key, double[] value) {
    this.m_key = key;
    this.m_value = value;
    this.m_next = null;
  }

  public void setKey(long key) {
    this.m_key = key;
  }

  public long getKey() {
    return m_key;
  }

  public void setValue(double[] value) {
    this.m_value = value;
  }

  public double[] getValue() {
    return m_value;
  }

  public void setNext(GpuLongVectorPair next) {
    this.m_next = next;
  }

  public GpuLongVectorPair getNext() {
    return m_next;
  }
}
