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
package at.illecker.rootbeer.examples.matrixmultiplication1;

import java.util.List;
import java.util.Random;

import org.trifort.rootbeer.runtime.Context;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.runtime.RootbeerGpu;
import org.trifort.rootbeer.runtime.StatsRow;
import org.trifort.rootbeer.runtime.ThreadConfig;
import org.trifort.rootbeer.runtime.util.Stopwatch;

/*
 * Known Restrictions:
 * Block Size must be 2^n (because of reduction sum)
 * Matrix size n <= blockSize or n = blocksize*Int
 */

public class MatrixMultiplication1Kernel implements Kernel {

  // input
  private double[][] rowsA;
  private double[][] matrixB;
  public int threadSliceSize;
  public int blockSliceSize;
  // output
  public ResultMatrix resultMatrix;

  public MatrixMultiplication1Kernel(double[][] rowsA, double[][] matrixB,
      int threadSliceSize, int blockSliceSize) {
    this.rowsA = rowsA;
    this.matrixB = matrixB;
    this.threadSliceSize = threadSliceSize;
    this.blockSliceSize = blockSliceSize;
    resultMatrix = new ResultMatrix(rowsA.length, this.matrixB[0].length);
  }

  public void gpuMethod() {

    // int blockSize = RootbeerGpu.getBlockDimx();
    // int gridSize = RootbeerGpu.getGridDimx();
    int block_idxx = RootbeerGpu.getBlockIdxx();
    int thread_idxx = RootbeerGpu.getThreadIdxx();
    // int globalThreadIndex = block_idxx * blockSize + thread_idxx;

    int matrixARowSize = rowsA.length;
    int matrixAColSize = rowsA[0].length;
    int matrixBRowSize = this.matrixB.length;
    int matrixBColSize = this.matrixB[0].length;

    // Check for wrong matrix sizes
    if (matrixAColSize != matrixBRowSize) {
      return;
    }

    // Init intermediateSums shared memory
    RootbeerGpu.setSharedDouble(thread_idxx * 8, 0);
    RootbeerGpu.syncthreads();

    // Check if thread and block is in matrix range
    if ((block_idxx < matrixBColSize * blockSliceSize)
        && (thread_idxx < matrixBRowSize * threadSliceSize)) {

      // Calculate scalar multiplication
      for (int k = 0; k < blockSliceSize; k++) {
        for (int i = 0; i < matrixARowSize; i++) {

          double sum = 0;
          for (int j = 0; j < threadSliceSize; j++) {

            double multiplier = 0;
            if ((((block_idxx * blockSliceSize) + k) < matrixBColSize)
                && (((thread_idxx * threadSliceSize) + j) < matrixBRowSize)) {

              multiplier = matrixB[(thread_idxx * threadSliceSize) + j][(block_idxx * blockSliceSize)
                  + k];

              double matrixAColValue = 0;
              if (((thread_idxx * threadSliceSize) + j) < matrixAColSize) {

                matrixAColValue = rowsA[i][(thread_idxx * threadSliceSize) + j];
              }

              sum += matrixAColValue * multiplier;

            }
          }

          RootbeerGpu.setSharedDouble(thread_idxx * 8, sum);
          RootbeerGpu.syncthreads();

          // do reduction sum in shared memory
          // 1-bit right shift = divide by two to the power 1

          // if (threadSliceSize==1) {
          for (int s = RootbeerGpu.getBlockDimx() / 2; s > 0; s >>= 1) {
            if (thread_idxx < s) {

              double val1 = RootbeerGpu.getSharedDouble(thread_idxx * 8);
              double val2 = RootbeerGpu.getSharedDouble((thread_idxx + s) * 8);
              RootbeerGpu.setSharedDouble(thread_idxx * 8, val1 + val2);
            }
            RootbeerGpu.syncthreads();
          }

          if (thread_idxx == 0) {

            // for (int t = 1; t < RootbeerGpu.getBlockDimx(); t++) {
            // sum += RootbeerGpu.getSharedDouble(t * 8);
            // }
            // sum =
            // RootbeerGpu.getSharedDouble(0)+RootbeerGpu.getSharedDouble(8);

            sum = RootbeerGpu.getSharedDouble(thread_idxx * 8);

            if (sum != 0) {
              if (((block_idxx * blockSliceSize) + k) < matrixBColSize) {
                resultMatrix.set(i, ((block_idxx * blockSliceSize) + k), sum);
              }
            }
          }

          // RootbeerGpu.setSharedDouble(thread_idxx * 8, 0);
          // RootbeerGpu.syncthreads();
        }
      }
    }
  }

  public static void main(String[] args) {
    // nvcc ~/.rootbeer/generated.cu --ptxas-options=-v -arch sm_35
    // ptxas info : Used 31 registers, 8228 bytes smem, 380 bytes cmem[0]

    // using -maxrregcount 32
    // using -shared-mem-size 1024*8 + 12 = 8192 + 12 = 8204
    // BlockSize = 1024
    // GridSize = 14

    boolean isDebugging = false;
    int blockSize = 1024; // threads
    int gridSize = 14; // blocks
    int n = 1024;

    // parse arguments
    if ((args.length > 0) && (args.length == 4)) {
      blockSize = Integer.parseInt(args[0]);
      gridSize = Integer.parseInt(args[1]);
      n = Integer.parseInt(args[2]);
      isDebugging = Boolean.parseBoolean(args[3]);
    } else {
      System.out.println("Wrong argument size!");
      System.out.println("    Argument1=blockSize");
      System.out.println("    Argument2=gridSize");
      System.out.println("    Argument3=n");
      System.out.println("    Argument4=debug(true|false=default)");
      return;
    }

    // threadSliceSize defines how much multipliers
    // of column B has to be multiplied with column A
    int threadSliceSize = divup(n, blockSize);

    // blockSliceSize defines the column slice amount
    // columns of B per blockIters
    int blockSliceSize = divup(n, gridSize);

    double[][] matrixA = createRandomArray(n, n, new Random(42L));
    double[][] matrixB = createRandomArray(n, n, new Random(1337L));
    // double[][] matrixC = createConstantArray(n, n, 0);

    if (isDebugging) {
      System.out.println("MatrixA");
      printArray(matrixA, n, n);
      System.out.println("MatrixB");
      printArray(matrixB, n, n);
      // System.out.println("MatrixC");
      // printArray(matrixC, n, n);
    }

    MatrixMultiplication1Kernel kernel = new MatrixMultiplication1Kernel(
        matrixA, matrixB, threadSliceSize, blockSliceSize);

    // Run GPU Kernels
    Rootbeer rootbeer = new Rootbeer();
    Context context = rootbeer.createDefaultContext();
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.run(kernel, new ThreadConfig(blockSize, gridSize, blockSize
        * gridSize), context);
    watch.stop();

    // Get GPU Result
    double[][] matrixC = kernel.resultMatrix.matrix;
    double[][] matrixD = multiply(matrixA, matrixB, n, n, n);

    // Debug
    List<StatsRow> stats = context.getStats();
    for (StatsRow row : stats) {
      System.out.println("  StatsRow:");
      System.out.println("    serial time: " + row.getSerializationTime());
      System.out.println("    exec time: " + row.getExecutionTime());
      System.out.println("    deserial time: " + row.getDeserializationTime());
      System.out.println("    num blocks: " + row.getNumBlocks());
      System.out.println("    num threads: " + row.getNumThreads());
    }
    System.out.println("GPUTime: " + watch.elapsedTimeMillis() + "ms");
    System.out.println("n: " + n);
    System.out.println("blockSize: " + blockSize);
    System.out.println("gridSize: " + gridSize);
    System.out.println("threadSliceSize: " + threadSliceSize);
    System.out.println("blockSliceSize: " + blockSliceSize);
    System.out.println("TotalThreads: " + blockSize * gridSize);

    boolean verifyResult = verify(matrixC, matrixD, n, n);
    if (verifyResult) {
      System.out.println("Verify PASSED!");
    } else {
      System.out.println("Verify FAILED!");
    }

    if (isDebugging) {
      System.out.println("MatrixC");
      printArray(matrixC, n, n);
      System.out.println("MatrixD");
      printArray(matrixD, n, n);
    }
  }

  static double[][] createConstantArray(int n, int m, double value) {
    final double data[][] = new double[n][m];
    for (int j = 0; j < n; ++j) {
      for (int i = 0; i < m; ++i) {
        data[j][i] = value;
      }
    }
    return data;
  }

  static double[][] createRandomArray(int n, int m, Random rand) {
    final double data[][] = new double[n][m];
    for (int j = 0; j < n; ++j) {
      for (int i = 0; i < m; ++i) {
        // matrix[i][j] = rand.nextDouble();
        data[i][j] = rand.nextInt(9) + 1; // between 1 and 10
      }
    }
    return data;
  }

  static void printArray(double[][] data, int n, int m) {
    for (int j = 0; j < n; ++j) {
      for (int i = 0; i < m; ++i) {
        if (i == m - 1) {
          System.out.println(data[j][i] + "]");
        } else if (i == 0) {
          System.out.print("[" + data[j][i] + ",");
        } else {
          System.out.print(data[j][i] + ",");
        }
      }
    }
    System.out.println();
  }

  static double[][] multiply(double[][] matrixA, double[][] matrixB,
      int a_rows, int a_cols, int b_cols) {
    final double data[][] = new double[a_rows][b_cols];

    for (int k = 0; k < a_cols; k++) {
      for (int i = 0; i < a_rows; i++) {
        for (int j = 0; j < b_cols; j++) {
          data[i][j] += matrixA[i][k] * matrixB[k][j];
        }
      }
    }
    return data;
  }

  static boolean verify(double[][] matrixA, double[][] matrixB, int n, int m) {
    for (int j = 0; j < n; ++j) {
      for (int i = 0; i < m; ++i) {
        if (matrixA[j][i] != matrixB[j][i]) {
          System.out.println("Verify ERROR at [" + j + "," + i + "]");
          return false;
        }
      }
    }
    return true;
  }

  static int divup(int x, int y) {
    if (x % y != 0) {
      // aufrunden
      return ((x + y - 1) / y);
    } else {
      return x / y;
    }
  }
}
