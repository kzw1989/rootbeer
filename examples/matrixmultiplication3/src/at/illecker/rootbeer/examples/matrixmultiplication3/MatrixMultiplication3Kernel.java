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
package at.illecker.rootbeer.examples.matrixmultiplication3;

import java.util.List;
import java.util.Random;

import org.trifort.rootbeer.runtime.Context;
import org.trifort.rootbeer.runtime.Kernel;
import org.trifort.rootbeer.runtime.Rootbeer;
import org.trifort.rootbeer.runtime.RootbeerGpu;
import org.trifort.rootbeer.runtime.StatsRow;
import org.trifort.rootbeer.runtime.ThreadConfig;
import org.trifort.rootbeer.runtime.util.Stopwatch;

public class MatrixMultiplication3Kernel implements Kernel {

  // input
  private double[][] m_matrixA;
  private double[][] m_matrixB;

  // output
  public double[][] m_resultMatrix;

  // temp
  private int m_N;
  private int m_M;
  private int m_L;

  public MatrixMultiplication3Kernel(double[][] matrixA, double[][] matrixB) {
    m_matrixA = matrixA;
    m_matrixB = matrixB;
    m_N = m_matrixA.length;
    m_M = m_matrixA[0].length;
    m_L = m_matrixB[0].length;
    m_resultMatrix = new double[m_M][m_L]; // M rows because A is transposed
  }

  public void gpuMethod() {
    // int blockSize = RootbeerGpu.getBlockDimx();
    int gridSize = RootbeerGpu.getGridDimx();
    int block_idxx = RootbeerGpu.getBlockIdxx();
    int thread_idxx = RootbeerGpu.getThreadIdxx();

    // A block handles a column and
    // each thread within this block takes one row

    int columnsPerBlock = divup(m_M, gridSize);
    int reductionStart = roundUpToNextPowerOfTwo(divup(m_N, 2));

    // DEBUG
    // if (RootbeerGpu.getThreadId() == 0) {
    // System.out.println("columnsPerBlock: " + columnsPerBlock);
    // System.out.println("reductionStart: " + reductionStart);
    // }

    // Loop over all columns of matrix A
    for (int i = 0; i < columnsPerBlock; i++) {

      int colId = (gridSize * i) + block_idxx;
      if (colId < m_M) {

        // Loop over all columns of matrix B
        for (int j = 0; j < m_L; j++) {

          if (thread_idxx < m_N) {
            RootbeerGpu.setSharedDouble(thread_idxx * 8,
                m_matrixA[thread_idxx][colId] * m_matrixB[thread_idxx][j]);
          }

          // Sync all threads within a block
          RootbeerGpu.syncthreads();

          // DEBUG
          // if (RootbeerGpu.getThreadId() == 0) {
          // for (int t = 0; t < m_N; t++) {
          // System.out.println("colId: " + colId + " j: " + j + " value: "
          // + RootbeerGpu.getSharedDouble(t * 8));
          // }
          // }

          // Sync all threads within a block
          RootbeerGpu.syncthreads();

          // do reduction in shared memory
          // 1-bit right shift = divide by two to the power 1
          for (int s = reductionStart; s > 0; s >>= 1) {

            if ((thread_idxx < s) && (thread_idxx + s) < m_N) {
              // sh_mem[tid] += sh_mem[tid + s];
              RootbeerGpu.setSharedDouble(
                  thread_idxx * 8,
                  RootbeerGpu.getSharedDouble(thread_idxx * 8)
                      + RootbeerGpu.getSharedDouble((thread_idxx + s) * 8));
            }

            // Sync all threads within a block
            RootbeerGpu.syncthreads();
          }

          // DEBUG
          // if (RootbeerGpu.getThreadId() == 0) {
          // System.out.println("colId: " + colId + " j: " + j + " sum: "
          // + RootbeerGpu.getSharedDouble(0));
          // }

          if (thread_idxx == 0) {
            m_resultMatrix[colId][j] = RootbeerGpu.getSharedDouble(0);
          }

          // Sync all threads within a block
          RootbeerGpu.syncthreads();
        }
      }

    }

  }

  private int divup(int x, int y) {
    if (x % y != 0) {
      return ((x + y - 1) / y); // round up
    } else {
      return x / y;
    }
  }

  private int roundUpToNextPowerOfTwo(int x) {
    x--;
    x |= x >> 1; // handle 2 bit numbers
    x |= x >> 2; // handle 4 bit numbers
    x |= x >> 4; // handle 8 bit numbers
    x |= x >> 8; // handle 16 bit numbers
    x |= x >> 16; // handle 32 bit numbers
    x++;
    return x;
  }

  public static void main(String[] args) {
    int n = 4;
    int m = 2;
    boolean isDebugging = true;
    int gridSize = 14;
    int blockSize = 256;

    // parse arguments
    if (args.length > 0) {
      if (args.length == 5) {
        gridSize = Integer.parseInt(args[0]);
        blockSize = Integer.parseInt(args[1]);
        n = Integer.parseInt(args[2]);
        m = Integer.parseInt(args[3]);
        isDebugging = Boolean.parseBoolean(args[4]);
      } else {
        System.out.println("Wrong argument size!");
        System.out.println("    Argument1=gridSize");
        System.out.println("    Argument2=blockSize");
        System.out.println("    Argument3=n");
        System.out.println("    Argument4=m");
        System.out.println("    Argument5=debug(true|false)");
        return;
      }
    }

    System.out.println("gridSize: " + gridSize);
    System.out.println("blockSize: " + blockSize);
    System.out.println("n: " + n);
    System.out.println("m: " + m);

    double[][] matrixA = createRandomMatrix(n, m, new Random(42L));
    double[][] transposedMatrixA = transposeMatrix(matrixA);
    double[][] matrixB = createRandomMatrix(m, n, new Random(1337L));
    // double[][] matrixC = createConstantArray(n, n, 0);

    if (isDebugging) {
      System.out.println("MatrixA");
      printMatrix(matrixA);
      System.out.println("TransposedMatrixA");
      printMatrix(transposedMatrixA);
      System.out.println("MatrixB");
      printMatrix(matrixB);
      // System.out.println("MatrixC");
      // printArray(matrixC, n, n);
    }

    // Run GPU Kernels
    MatrixMultiplication3Kernel kernel = new MatrixMultiplication3Kernel(
        transposedMatrixA, matrixB);

    Rootbeer rootbeer = new Rootbeer();
    Context context = rootbeer.createDefaultContext();
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.run(kernel, new ThreadConfig(blockSize, gridSize, blockSize
        * gridSize), context);
    watch.stop();

    // Get GPU Result
    double[][] matrixC = kernel.m_resultMatrix;

    long startTime = System.currentTimeMillis();
    double[][] matrixD = multiply(matrixA, matrixB);
    System.out.println("CPU Time: " + (System.currentTimeMillis() - startTime)
        + "ms");

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
    System.out.println("GPU Time: " + watch.elapsedTimeMillis() + "ms");

    boolean verifyResult = verify(matrixC, matrixD);
    if (verifyResult) {
      System.out.println("Verify PASSED!\n");
    } else {
      System.out.println("Verify FAILED!\n");

    }
    if (isDebugging) {
      System.out.println("MatrixC");
      printMatrix(matrixC);
      System.out.println("MatrixD");
      printMatrix(matrixD);
    }
  }

  static double[][] createRandomMatrix(int n, int m, Random rand) {
    final double matrix[][] = new double[n][m];
    for (int i = 0; i < n; ++i) {
      for (int j = 0; j < m; ++j) {
        // matrix[i][j] = rand.nextDouble();
        matrix[i][j] = rand.nextInt(9) + 1; // between 1 and 10
      }
    }
    return matrix;
  }

  static double[][] transposeMatrix(double[][] matrix) {
    int n = matrix[0].length;
    int m = matrix.length;
    final double transposedMatrix[][] = new double[n][m];
    for (int i = 0; i < n; ++i) {
      for (int j = 0; j < m; ++j) {
        transposedMatrix[i][j] = matrix[j][i];
      }
    }
    return transposedMatrix;
  }

  static void printMatrix(double[][] matrix) {
    int n = matrix.length;
    int m = matrix[0].length;
    for (int i = 0; i < n; ++i) {
      for (int j = 0; j < m; ++j) {
        if (j == m - 1) {
          System.out.println(matrix[i][j] + "]");
        } else if (j == 0) {
          System.out.print("[" + matrix[i][j] + ",");
        } else {
          System.out.print(matrix[i][j] + ",");
        }
      }
    }
    System.out.println();
  }

  static double[][] multiply(double[][] matrixA, double[][] matrixB) {
    int n = matrixA.length;
    int m = matrixA[0].length;
    int l = matrixB[0].length;
    final double matrix[][] = new double[n][l];
    for (int k = 0; k < m; k++) {
      for (int i = 0; i < n; i++) {
        for (int j = 0; j < l; j++) {
          matrix[i][j] += matrixA[i][k] * matrixB[k][j];
        }
      }
    }
    return matrix;
  }

  static boolean verify(double[][] matrixA, double[][] matrixB) {
    int n = matrixA.length;
    int m = matrixA[0].length;
    for (int i = 0; i < n; ++i) {
      for (int j = 0; j < m; ++j) {
        if (matrixA[i][j] != matrixB[i][j]) {
          System.out.println("Verify error at [" + i + "," + j + "]: "
              + matrixA[i][j] + " != " + matrixB[i][j]);
          return false;
        }
      }
    }
    return true;
  }
}
