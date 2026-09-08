package ru.cyberc3dr.pc;

import java.util.Arrays;

public final class Task1 {

    public static final int THREADS = 8;
    public static final int n = 100000000;
    public static final int N_PER_THREAD = n / THREADS;
    public static final double l = 0.0;
    public static final double r = 4.0;
    public static final double d = (r - l) / n;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("d: " + d);

        var start = System.nanoTime();

        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            double x = l + d * i;
            sum += f(x) * d;
        }

        var end = System.nanoTime();

        System.out.println("Sequential");
        System.out.println("Result: " + sum);
        System.out.println("Exact value: " + F(r, l));
        System.out.println("Time 1 (ms): " + (end - start) / 1000000.0);
        System.out.println();

        executeInThread();
    }

    public static void executeInThread() throws InterruptedException {
        var results = new double[THREADS];
        var threads = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            threads[i] = taskThread(i, results);
        }

        var start = System.nanoTime();

        for (int i = 0; i < THREADS; i++) {
            threads[i].start();
        }

        for (int i = 0; i < THREADS; i++) {
            threads[i].join();
        }

        var end = System.nanoTime();

        double sum = Arrays.stream(results).sum();

        System.out.println("Multithreaded");
        System.out.println("Result: " + sum);
        System.out.println("Exact value: " + F(r, l));
        System.out.println("Time 2 (ms): " + (end - start) / 1000000.0);
    }

    public static double f(double x) {
        return 2 * x;
    }

    public static double F(double r, double l) {
        return Math.pow(r, 2) - Math.pow(l, 2);
    }

    public static Thread taskThread(int n, double[] results) {
        return new Thread(() -> {
            int start = n * N_PER_THREAD;
            int end = start + N_PER_THREAD;

            double sum = 0.0;
            for (int i = start; i < end; i++) {
                double x = l + d * i;
                sum += f(x) * d;
            }

            results[n] = sum;
        });
    }
}