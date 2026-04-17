package com.minsbot.skills.matrixops;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MatrixOpsService {

    public double[][] add(double[][] a, double[][] b) {
        check(a, b, true);
        double[][] r = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) r[i][j] = a[i][j] + b[i][j];
        return r;
    }

    public double[][] subtract(double[][] a, double[][] b) {
        check(a, b, true);
        double[][] r = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) r[i][j] = a[i][j] - b[i][j];
        return r;
    }

    public double[][] multiply(double[][] a, double[][] b) {
        if (a[0].length != b.length) throw new IllegalArgumentException("incompatible dims: cols of A must equal rows of B");
        double[][] r = new double[a.length][b[0].length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < b[0].length; j++)
                for (int k = 0; k < b.length; k++) r[i][j] += a[i][k] * b[k][j];
        return r;
    }

    public double[][] transpose(double[][] a) {
        double[][] r = new double[a[0].length][a.length];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a[0].length; j++) r[j][i] = a[i][j];
        return r;
    }

    public double determinant(double[][] a) {
        if (a.length != a[0].length) throw new IllegalArgumentException("determinant requires square matrix");
        return det(a, a.length);
    }

    public double[][] scalarMultiply(double[][] a, double s) {
        double[][] r = new double[a.length][a[0].length];
        for (int i = 0; i < a.length; i++) for (int j = 0; j < a[0].length; j++) r[i][j] = a[i][j] * s;
        return r;
    }

    private double det(double[][] m, int n) {
        if (n == 1) return m[0][0];
        if (n == 2) return m[0][0] * m[1][1] - m[0][1] * m[1][0];
        double sum = 0;
        for (int col = 0; col < n; col++) {
            double[][] minor = new double[n - 1][n - 1];
            for (int i = 1; i < n; i++) {
                int jj = 0;
                for (int j = 0; j < n; j++) {
                    if (j == col) continue;
                    minor[i - 1][jj++] = m[i][j];
                }
            }
            sum += ((col % 2 == 0) ? 1 : -1) * m[0][col] * det(minor, n - 1);
        }
        return sum;
    }

    private static void check(double[][] a, double[][] b, boolean sameShape) {
        if (a == null || b == null || a.length == 0 || b.length == 0) throw new IllegalArgumentException("matrix required");
        if (sameShape && (a.length != b.length || a[0].length != b[0].length)) {
            throw new IllegalArgumentException("shape mismatch");
        }
    }

    public double[][] fromList(List<List<Number>> data) {
        if (data == null || data.isEmpty()) throw new IllegalArgumentException("matrix required");
        int rows = data.size(), cols = data.get(0).size();
        double[][] out = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            if (data.get(i).size() != cols) throw new IllegalArgumentException("rows must have equal length");
            for (int j = 0; j < cols; j++) out[i][j] = data.get(i).get(j).doubleValue();
        }
        return out;
    }
}
