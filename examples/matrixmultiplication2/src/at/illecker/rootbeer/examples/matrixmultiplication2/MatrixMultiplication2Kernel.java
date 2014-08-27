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
package at.illecker.rootbeer.examples.matrixmultiplication2;

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

public class MatrixMultiplication2Kernel implements Kernel {

  // input
  private double[] m_matrixA;
  private double[] m_matrixB;
  private int m_matrixACols;
  private int m_matrixBCols;
  private int m_blocksSizeX;
  private int m_blocksSizeY;
  private int m_gridSizeX;
  private int m_gridSizeY;
  private int m_blockSize;

  // output
  public ResultMatrix resultMatrix;

  public MatrixMultiplication2Kernel(double[] matrixA, double[] matrixB,
      int matrixACols, int matrixBCols, int blocksSizeX, int blocksSizeY,
      int gridSizeX, int gridSizeY, int blockSize) {

    m_matrixA = matrixA;
    m_matrixB = matrixB;
    m_matrixACols = matrixACols; // wA
    m_matrixBCols = matrixBCols; // wB
    m_blocksSizeX = blocksSizeX;
    m_blocksSizeY = blocksSizeY;
    m_gridSizeX = gridSizeX;
    m_gridSizeY = gridSizeY;
    m_blockSize = blockSize; // BLOCK_SIZE

    int matrixARows = matrixA.length / m_matrixACols;
    resultMatrix = new ResultMatrix(matrixARows, m_matrixBCols);
  }

  public void gpuMethod() {

    int threadId = RootbeerGpu.getThreadIdxx();
    int blockId = RootbeerGpu.getBlockIdxx();

    // Thread index
    int tx = threadId / m_blocksSizeX;
    int ty = threadId % m_blocksSizeY;

    // Block index
    int bx = blockId / m_gridSizeX;
    int by = blockId % m_gridSizeY;

    // Index of the first sub-matrix of A processed by the block
    int aBegin = m_matrixACols * m_blockSize * by;

    // Index of the last sub-matrix of A processed by the block
    int aEnd = aBegin + m_matrixACols - 1;

    // Step size used to iterate through the sub-matrices of A
    int aStep = m_blockSize;

    // Index of the first sub-matrix of B processed by the block
    int bBegin = m_blockSize * bx;

    // Step size used to iterate through the sub-matrices of B
    int bStep = m_blockSize * m_matrixBCols;

    // Csub is used to store the element of the block sub-matrix
    // that is computed by the thread
    double Csub = 0;

    // Loop over all the sub-matrices of A and B
    // required to compute the block sub-matrix
    for (int a = aBegin, b = bBegin; a <= aEnd; a += aStep, b += bStep) {

      // Declaration of the shared memory array As used to
      // store the sub-matrix of A
      // __shared__ float As[BLOCK_SIZE][BLOCK_SIZE];
      int AsStartIndex = 0;

      // Declaration of the shared memory array Bs used to
      // store the sub-matrix of B
      // __shared__ float Bs[BLOCK_SIZE][BLOCK_SIZE];
      int BsStartIndex = m_blockSize * m_blockSize * 8;

      // Load the matrices from device memory
      // to shared memory; each thread loads
      // one element of each matrix
      RootbeerGpu.setSharedDouble(AsStartIndex + (ty * m_blockSize) + tx,
          m_matrixA[a + m_matrixACols * ty + tx]);
      RootbeerGpu.setSharedDouble(BsStartIndex + (ty * m_blockSize) + tx,
          m_matrixB[b + m_matrixBCols * ty + tx]);

      // Synchronize to make sure the matrices are loaded
      RootbeerGpu.syncthreads();

      // Multiply the two matrices together;
      // each thread computes one element
      // of the block sub-matrix
      // #pragma unroll

      for (int k = 0; k < m_blockSize; ++k) {
        Csub += RootbeerGpu.getSharedDouble(AsStartIndex + (ty * m_blockSize)
            + k)
            * RootbeerGpu
                .getSharedDouble(BsStartIndex + (k * m_blockSize) + tx);
      }

      // Synchronize to make sure that the preceding
      // computation is done before loading two new
      // sub-matrices of A and B in the next iteration
      RootbeerGpu.syncthreads();
    }

    // Write the block sub-matrix to device memory;
    // each thread writes one element
    int c = m_matrixBCols * m_blockSize * by + m_blockSize * bx;
    resultMatrix.set(c + m_matrixBCols * ty + tx, Csub);
  }

  public static void main(String[] args) {
    // nvcc ~/.rootbeer/generated.cu --ptxas-options=-v -arch sm_35
    // ptxas info : Used 31 registers, 8228 bytes smem, 380 bytes cmem[0]

    // using -maxrregcount 32
    // using -shared-mem-size 32*32 + 32*32 + 12 = 8192 + 12 = 8204
    // BlockSize = 1024
    // GridSize = 14

    boolean isDebugging = false;
    int matrix_block_size = 32;
    int n = 4 * 4 * matrix_block_size;

    // parse arguments
    if ((args.length > 0) && (args.length == 2)) {
      n = Integer.parseInt(args[0]) * matrix_block_size;
      isDebugging = Boolean.parseBoolean(args[1]);
    } else {
      System.out.println("Wrong argument size!");
      System.out.println("    Argument1=n");
      System.out.println("    Argument2=debug(true|false=default)");
      return;
    }

    double[] matrixA = createRandomArray(n, n, new Random(42L));
    double[] matrixB = createRandomArray(n, n, new Random(1337L));
    // double[][] matrixC = createConstantArray(n, n, 0);

    if (isDebugging) {
      System.out.println("MatrixA");
      printArray(matrixA, n, n);
      System.out.println("MatrixB");
      printArray(matrixB, n, n);
      // System.out.println("MatrixC");
      // printArray(matrixC, n, n);
    }

    int blocksSizeX = matrix_block_size; // 32
    int blocksSizeY = matrix_block_size; // 32
    int blockSize = blocksSizeX * blocksSizeY; // 1024

    int gridSizeX = n / blocksSizeX; // dimsB.x / threads.x
    int gridSizeY = n / blocksSizeY; // dimsA.y / threads.y
    int gridSize = gridSizeX * gridSizeY;

    MatrixMultiplication2Kernel kernel = new MatrixMultiplication2Kernel(
        matrixA, matrixB, n, n, blocksSizeX, blocksSizeY, gridSizeX, gridSizeY,
        matrix_block_size);

    // Run GPU Kernels
    Rootbeer rootbeer = new Rootbeer();
    Context context = rootbeer.createDefaultContext();
    Stopwatch watch = new Stopwatch();
    watch.start();
    rootbeer.run(kernel, new ThreadConfig(blockSize, gridSize, (long) blockSize
        * gridSize), context);
    watch.stop();

    // Get GPU Result
    double[] matrixC = kernel.resultMatrix.matrix;
    double[] matrixD = multiply(matrixA, matrixB, n, n, n);

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

  static double[] createConstantArray(int n, int m, double value) {
    final double data[] = new double[n * m];
    for (int j = 0; j < n; ++j) {
      for (int i = 0; i < m; ++i) {
        data[j * m + i] = value;
      }
    }
    return data;
  }

  static double[] createRandomArray(int n, int m, Random rand) {
    final double data[] = new double[n * m];
    for (int j = 0; j < n; ++j) {
      for (int i = 0; i < m; ++i) {
        // matrix[i][j] = rand.nextDouble();
        data[j * m + j] = rand.nextInt(9) + 1; // between 1 and 10
      }
    }
    return data;
  }

  static void printArray(double[] data, int n, int m) {
    for (int j = 0; j < n; ++j) {
      for (int i = 0; i < m; ++i) {
        if (i == m - 1) {
          System.out.println(data[j * m + i] + "]");
        } else if (i == 0) {
          System.out.print("[" + data[j * m + i] + ",");
        } else {
          System.out.print(data[j * m + i] + ",");
        }
      }
    }
    System.out.println();
  }

  static double[] multiply(double[] matrixA, double[] matrixB, int a_rows,
      int a_cols, int b_cols) {
    final double data[] = new double[a_rows * b_cols];

    for (int k = 0; k < a_cols; k++) {
      for (int i = 0; i < a_rows; i++) {
        for (int j = 0; j < b_cols; j++) {
          data[i * b_cols + j] += matrixA[i * b_cols + k]
              * matrixB[k * a_rows + j];
        }
      }
    }
    return data;
  }

  static boolean verify(double[] matrixA, double[] matrixB, int n, int m) {
    for (int j = 0; j < n; ++j) {
      for (int i = 0; i < m; ++i) {
        if (matrixA[j * m + i] != matrixB[j * m + i]) {
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
