/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
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

import java.util.Arrays;
import java.util.List;

import org.trifort.rootbeer.runtime.Context;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.runtime.RootbeerGpu;
import org.trifort.rootbeer.runtime.StatsRow;
import org.trifort.rootbeer.runtime.ThreadConfig;
import org.trifort.rootbeer.runtime.util.Stopwatch;

public class TestMapKernel implements Kernel {
  // gridSize = amount of blocks and multiprocessors
  public static final int GRID_SIZE = 1; // 14;
  // blockSize = amount of threads
  public static final int BLOCK_SIZE = 1; // 1024;

  private GpuVectorMap m_map;

  public TestMapKernel(GpuVectorMap map) {
    this.m_map = map;
  }

  @Override
  public void gpuMethod() {
    int thread_idxx = RootbeerGpu.getThreadIdxx();
    int block_idxx = RootbeerGpu.getBlockIdxx();

    // Setup sharedMemory from Map
    if (thread_idxx == 0) {
      double[] vector = m_map.get(block_idxx);
      debug(block_idxx, vector);
      for (int i = 0; i < vector.length; i++) {
        RootbeerGpu.setSharedDouble(i * 8, vector[i]);
        // System.out.println(vector[i]);
      }
    }
    RootbeerGpu.syncthreads();

    // Each kernel increments one item
    double val = RootbeerGpu.getSharedDouble(thread_idxx * 8);
    RootbeerGpu.setSharedDouble(thread_idxx * 8, val + 1);

    RootbeerGpu.syncthreads();

    // Put sharedMemory back into Map
    if (thread_idxx == 0) {
      double[] vector = new double[RootbeerGpu.getBlockDimx()];
      for (int i = 0; i < vector.length; i++) {
        vector[i] = RootbeerGpu.getSharedDouble(i * 8);
      }
      m_map.put(block_idxx, vector);
    }
  }

  private synchronized void debug(int val, double[] arr) {
    int x = arr.length; // ERROR arr.length sets array values to 0
    /*
    System.out.print("(");
    System.out.print(val);
    System.out.print(",");
    if (arr != null) {
      for (int i = 0; i < arr.length; i++) {
        System.out.print(Double.toString(arr[i]));
        if (i + 1 < arr.length) {
          System.out.print(",");
        }
      }
    }
    System.out.println(")");
    */
  }

  public static void main(String[] args) {

    int blockSize = BLOCK_SIZE;
    int gridSize = GRID_SIZE;

    // parse arguments
    if ((args.length > 0) && (args.length == 2)) {
      blockSize = Integer.parseInt(args[0]);
      gridSize = Integer.parseInt(args[1]);
    } else {
      System.out.println("Wrong argument size!");
      System.out.println("    Argument1=blockSize");
      System.out.println("    Argument2=gridSize");
      return;
    }

    System.out.println("blockSize: " + blockSize);
    System.out.println("gridSize: " + gridSize);

    boolean isDebugging = ((gridSize < 20) && (blockSize < 20));

    // Prepare vectorMap
    GpuVectorMap vectorMap = new GpuVectorMap(gridSize);
    if (isDebugging) {
      System.out.println("input: ");
    }
    for (int i = 0; i < gridSize; i++) {
      double[] vector = new double[blockSize];
      for (int j = 0; j < blockSize; j++) {
        vector[j] = (i * gridSize) + j;
      }
      vectorMap.put(i, vector);
      if (isDebugging) {
        System.out.println("(" + i + "," + Arrays.toString(vector) + ")");
      }
    }

    // Run GPU Kernels
    Rootbeer rootbeer = new Rootbeer();
    TestMapKernel kernel = new TestMapKernel(vectorMap);
    Context context = rootbeer.createDefaultContext();
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.run(kernel, new ThreadConfig(blockSize, gridSize, blockSize
        * gridSize), context);
    watch.stop();

    // Logging
    List<StatsRow> stats = context.getStats();
    for (StatsRow row : stats) {
      System.out.println("  StatsRow:");
      System.out.println("    serial time: " + row.getSerializationTime());
      System.out.println("    exec time: " + row.getExecutionTime());
      System.out.println("    deserial time: " + row.getDeserializationTime());
      System.out.println("    num blocks: " + row.getNumBlocks());
      System.out.println("    num threads: " + row.getNumThreads());
      System.out.println("GPUTime: " + watch.elapsedTimeMillis() + " ms");
    }

    // Verify
    boolean verified = true;
    if (isDebugging) {
      System.out.println("output: ");
    }
    for (int i = 0; i < gridSize; i++) {
      if (!verified) {
        break;
      }
      double[] v = kernel.m_map.get(i);
      if (isDebugging) {
        System.out.println("(" + i + "," + Arrays.toString(v) + ")");
      }
      for (int j = 0; j < blockSize; j++) {
        double value = v[j];
        double expected_value = (i * gridSize) + j + 1;
        if (value != expected_value) {
          verified = false;
          break;
        }
      }
    }

    if (verified) {
      System.out.println("Data verified!");
    } else {
      System.out.println("Error in verification!");
    }
  }

}
