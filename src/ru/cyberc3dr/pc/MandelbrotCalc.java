package ru.cyberc3dr.pc;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.ImageIcon;
import java.awt.image.BufferedImage;

public class MandelbrotCalc {

    private static final int N_THREADS = 6;

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 800;
    private static final int MAX_ITERATIONS = 500;

    // Диапазон комплексной плоскости
    private static final double X_MIN = -2.0;
    private static final double X_MAX = 1.0;
    private static final double Y_MIN = -1.5;
    private static final double Y_MAX = 1.5;

    // Шаг по плоскости, соответствующий одному пикселю
    // Посчитан заранее чтобы не нагружать потоки
    private static final double DX = (X_MAX - X_MIN) / WIDTH;
    private static final double DY = (Y_MAX - Y_MIN) / HEIGHT;

    public static void main(String[] args) throws InterruptedException {
        // Подсчет последовательно (для сравнения времени)
        int[] pixels = new int[WIDTH * HEIGHT];

        long start = System.nanoTime();

        for (int y = 0; y < HEIGHT; ++y) {
            computeRow(y, pixels);
        }

        long end = System.nanoTime();

        System.out.println("Sequential");
        System.out.println("Time 1 (ms): " + (end - start) / 1000000.0);
        System.out.println();

        int[] pixelsParallel = executeInThreads();

        render(pixelsParallel);
    }

    /*
        Для точки c = ca + cb*i считаем z = z^2 + c, начиная с z = 0.
        При |z| > 2, точка не входит в множество
        MAX_ITERATIONS означает, что точка входит в множество.
     */
    private static int calcIterations(double ca, double cb) {
        double za = 0.0;
        double zb = 0.0;

        int iter = 0;
        while (iter < MAX_ITERATIONS && Math.pow(za, 2) + Math.pow(zb, 2) <= 4.0) {
            double newZa = Math.pow(za, 2) - Math.pow(zb, 2) + ca;
            zb = 2 * za * zb + cb;
            za = newZa;
            ++iter;
        }

        return iter;
    }

    // Считаем одну строчку картинки
    private static void computeRow(int y, int[] pixels) {
        double cb = Y_MIN + DY * y;

        for (int x = 0; x < WIDTH; x++) {
            double ca = X_MIN + DX * x;
            int iter = calcIterations(ca, cb);

            if (iter == MAX_ITERATIONS) { // Входит в множество
                pixels[y * WIDTH + x] = 0x000000; // черный
            } else {
                // не входит - красим в зависимости от количества итераций
                int red = (iter * 7) % 256;
                int green = (iter * 3) % 256;
                int blue = (iter * 15) % 256;
                pixels[y * WIDTH + x] = (red << 16) | (green << 8) | blue;
            }
        }
    }

    // Подсчет параллельно
    private static int[] executeInThreads() throws InterruptedException {
        // Общий массив пикселей. Одномерный
        // Строки записываются по смещению.
        // Синхронизация не требуется. Потоки пишут в разные строки
        int[] pixels = new int[WIDTH * HEIGHT];

        Thread[] threads = new Thread[N_THREADS];

        for (int t = 0; t < N_THREADS; t++) {
            threads[t] = linesThread(t, pixels);
        }

        long start = System.nanoTime();

        for (int t = 0; t < N_THREADS; t++) {
            threads[t].start();
        }
        for (int t = 0; t < N_THREADS; t++) {
            threads[t].join();
        }

        long end = System.nanoTime();

        System.out.println("Multithreaded");
        System.out.println("Time (ms): " + (end - start) / 1000000.0);

        return pixels;
    }

    /*
        Подсчет строк чередованием.
        id, id + threads, id + threads * 2

        Необходимо для повышения эффективности рассчета.
     */
    private static Thread linesThread(int id, int[] pixels) {
        return new Thread(() -> {
            for (int y = id; y < HEIGHT; y += N_THREADS) {
                computeRow(y, pixels);
            }
        });
    }

    // Показываем результат в JFrame
    private static void render(int[] pixels) {
        // Выбран RGB из-за своей простоты.
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);

        JFrame frame = new JFrame("Множество Мандельброта");
        frame.add(new JLabel(new ImageIcon(image)));
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
    }
}
