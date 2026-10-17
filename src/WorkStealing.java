import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class WorkStealing {

    enum TaskDistribution {
        UNIFORM,
        PERIODIC,
        PARETO
    }

    interface Shutdownable{
        void shutdown();
    }

    public interface ShutdownableExecutor extends Shutdownable, Executor{}

    // По заданию
    static class FixedThreadPoolExecutor implements ShutdownableExecutor {

        private final ExecutorService executor;

        public FixedThreadPoolExecutor(int nThreads) {
            executor = Executors.newFixedThreadPool(nThreads);
        }

        @Override
        public void execute(Runnable command) {
            executor.submit(command);
        }

        @Override
        public void shutdown() {
            executor.shutdown();
            // надо бы его убить
            try {
                if (!executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                    throw new RuntimeException("FixedThreadPool did not terminate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    static class ThreadPerTaskExecutor implements ShutdownableExecutor{
        List<Thread> threads = new ArrayList<>();
        @Override
        public void execute(Runnable command) {
            var t = new Thread(command);
            threads.add(t);
            t.start();
        }
        public void shutdown(){
            threads.forEach(t -> {try {t.join();} catch (InterruptedException e) {throw new RuntimeException(e);}});
        }
    }

    static class RoundRobinExecutor implements ShutdownableExecutor {

        protected static final Runnable EXIT_TASK = () -> {};

        AtomicInteger counter = new AtomicInteger(0);

        List<Thread> threads;
        Vector<BlockingDeque<Runnable>> tasks;
        volatile boolean isShuttingDown = false;

        RoundRobinExecutor(int threads){
            Supplier<IntStream> stream = () -> IntStream.iterate(0, x->x<threads, x->x+1);
            tasks = new Vector<>(
                    stream.get()
                            .mapToObj(x-> new LinkedBlockingDeque<Runnable>())
                            .toList());
            this.threads = stream.get()
                    .mapToObj(this::spawnThread)
                    .toList();
            this.threads.forEach(Thread::start);
        }

        Thread spawnThread(int id){
            return new Thread(() ->
                {
                    while (true) {
                        Runnable task;
                        try {
                            task = tasks.get(id).takeFirst();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (task == EXIT_TASK)
                            return;
                        task.run();
                    }
                }
            );
        }

        @Override
        public void shutdown() {
            synchronized (this) {
                if (!isShuttingDown) {
                    isShuttingDown = true;
                    tasks.forEach(queue -> queue.addLast(EXIT_TASK));
                }
            }
            threads.forEach(t -> {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public synchronized void execute(Runnable command) {
            if (isShuttingDown)
                throw new RejectedExecutionException("Executor is shutting down");
            tasks.get(counter.addAndGet(1) % threads.size()).addLast(command);
        }
    }

    static class WorkStealingExecutor extends RoundRobinExecutor {

        WorkStealingExecutor(int threads) {
            super(threads);
        }

        @Override
        Thread spawnThread(int id) {
            return new Thread(() -> {
                while (true) {
                    Runnable task = tasks.get(id).pollFirst();
                    if (task == null)
                        task = stealTask(id);

                    if (task == null) {
                        try {
                            task = tasks.get(id).pollFirst(1, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (task == null)
                            continue;
                    }

                    if (task == EXIT_TASK) {
                        task = stealTask(id);
                        if (task == null)
                            return;
                        tasks.get(id).addLast(EXIT_TASK);
                    }

                    task.run();
                }
            });
        }

        private Runnable stealTask(int thiefId) {
            for (int offset = 1; offset < tasks.size(); offset++) {
                var queue = tasks.get((thiefId + offset) % tasks.size());
                var task = queue.pollLast();

                if (task == EXIT_TASK) {
                    task = queue.pollLast();
                    queue.addLast(EXIT_TASK);
                }

                if (task != null)
                    return task;
            }
            return null;
        }
    }

    // Это вещи, которые нам нужны
    static final int THREAD_NUMBER = 10;
    static final int TASK_NUMBER = 100_000;
    static final int TARGET_OPTIMAL_FULL_TIME = 10_000; //ms = 10 s

    // Это всякое вспомогательное побочное
    static final int MEAN_TASK_TIME =  (int) Math.round((TARGET_OPTIMAL_FULL_TIME + 0d) / TASK_NUMBER * THREAD_NUMBER); //ms
    static final int LOWER_TASK_TIME_BOUND = 0;
    static final int HIGHER_TASK_TIME_BOUND = MEAN_TASK_TIME * 2+1;
    static final int ESTIMATED_OPTIMAL_TIME = MEAN_TASK_TIME * TASK_NUMBER / THREAD_NUMBER;
    static final long TARGET_TOTAL_TASK_TIME = (long) TARGET_OPTIMAL_FULL_TIME * THREAD_NUMBER;
    static final long RANDOM_SEED = 42;
    static final double PARETO_SHAPE = 1.5;
    static final int PARETO_RAW_MAX_TASK_TIME = MEAN_TASK_TIME * 100;

    static volatile long blackHoleSink;

    static void blackHole(long difficulty) {
        // генератор псевдослучайных чисел с изначальным сидом 0
        // в жава рандоме используется CAS для изменения сида
        long acc = 0;
        for (long i = 0; i < difficulty; i++) {
            // смотри java.util.Random
            // private static final long multiplier = 0x5DEECE66DL;
            // private static final long addend = 0xBL;

            // эта вещь крутит длинную 64 битную арифметику загружая цп на 100%
            acc = acc * 0x5DEECE66DL + 0xBL + i;
        }
        // чтобы jvm не соптимизировала нам это
        // запишем в поле
        blackHoleSink = acc;
    }

    // 1 мс на этой машине
    static final long ITERS_PER_MS = 950_000;

    static Runnable createTask(int duration){
        return () -> blackHole((long) duration * ITERS_PER_MS);
    }

    static int createTaskDuration(TaskDistribution distribution, int taskId, Random random) {
        return switch (distribution) {
            case UNIFORM -> random.nextInt(LOWER_TASK_TIME_BOUND, HIGHER_TASK_TIME_BOUND);
            case PERIODIC -> taskId % THREAD_NUMBER == 0
                    ? MEAN_TASK_TIME * THREAD_NUMBER
                    : 0;
            case PARETO -> {
                var scale = MEAN_TASK_TIME * (PARETO_SHAPE - 1) / PARETO_SHAPE;
                var duration = scale / Math.pow(1 - random.nextDouble(), 1 / PARETO_SHAPE);
                yield (int) Math.min(Math.round(duration), PARETO_RAW_MAX_TASK_TIME);
            }
        };
    }

    static int[] createTaskDurations(TaskDistribution distribution) {
        var random = new Random(RANDOM_SEED);
        // var random = java.util.concurrent.ThreadLocalRandom.current();
        var durations = new int[TASK_NUMBER];

        long totalDuration = 0;
        for (int taskId = 0; taskId < TASK_NUMBER; taskId++) {
            durations[taskId] = createTaskDuration(distribution, taskId, random);
            totalDuration += durations[taskId];
        }

        double scale = (double) TARGET_TOTAL_TASK_TIME / totalDuration;
        double remainder = 0;
        long normalizedTotal = 0;
        for (int taskId = 0; taskId < TASK_NUMBER; taskId++) {
            double scaledDuration = durations[taskId] * scale + remainder;
            durations[taskId] = (int) scaledDuration;
            remainder = scaledDuration - durations[taskId];
            normalizedTotal += durations[taskId];
        }

        for (int taskId = 0; normalizedTotal < TARGET_TOTAL_TASK_TIME; taskId++) {
            durations[taskId % TASK_NUMBER]++;
            normalizedTotal++;
        }
        for (int taskId = 0; normalizedTotal > TARGET_TOTAL_TASK_TIME; taskId++) {
            int index = taskId % TASK_NUMBER;
            if (durations[index] > 0) {
                durations[index]--;
                normalizedTotal--;
            }
        }

        return durations;
    }

    static Vector<Runnable> createTasks(TaskDistribution distribution){
        var durations = createTaskDurations(distribution);
        return new Vector<>(
                IntStream
                    .iterate(0, x -> x < TASK_NUMBER, x -> x + 1)
                    .mapToObj(x -> createTask(durations[x]))
                    .toList()
        );
    }

    public record Pair<A,B>(A first, B second){};

    public static Pair<Long, Long> measureExecutor(ShutdownableExecutor executor, TaskDistribution distribution){
        var tasks = createTasks(distribution);
        var start = System.nanoTime();
        tasks.forEach(executor::execute);
        var submitionEnd = System.nanoTime();
        executor.shutdown();
        var finish = System.nanoTime();
        return new Pair<>(submitionEnd - start, finish - start);
    }

    public static void main(String[] args) {
        System.out.println("Target optimal time: " + TARGET_OPTIMAL_FULL_TIME);
        System.out.println("Estimated optimal time: " + ESTIMATED_OPTIMAL_TIME);

        // В теории, на винде вот эта строчка может навернуть систему, будьте аккуратны!
        var threadPerTaskExecutorResult = measureExecutor(new ThreadPerTaskExecutor(), TaskDistribution.UNIFORM);

        System.out.println("Thread per task executor submtition time: " + threadPerTaskExecutorResult.first/1_000_000d);
        System.out.println("Thread per task execution time: " + threadPerTaskExecutorResult.second/1_000_000d);

        for (var distribution : TaskDistribution.values()) {
            System.out.println("Task distribution: " + distribution);

            var fixedThreadPoolResult = measureExecutor(new FixedThreadPoolExecutor(THREAD_NUMBER), distribution);

            System.out.println("Fixed Thread Pool submition time: " + fixedThreadPoolResult.first/1_000_000d);
            System.out.println("Fixed Thread Pool execution time: " + fixedThreadPoolResult.second/1_000_000d);

            var roundRobinExecutorResult = measureExecutor(new RoundRobinExecutor(THREAD_NUMBER), distribution);

            System.out.println("Round Robin Executor submition time: " + roundRobinExecutorResult.first/1_000_000d);
            System.out.println("Round Robin Executor execution time: " + roundRobinExecutorResult.second/1_000_000d);

            var workStealingExecutorResult = measureExecutor(new WorkStealingExecutor(THREAD_NUMBER), distribution);

            System.out.println("Work Stealing Executor submition time: " + workStealingExecutorResult.first/1_000_000d);
            System.out.println("Work Stealing Executor execution time: " + workStealingExecutorResult.second/1_000_000d);
        }
    }
}

/* Thread.sleep()

> Task :WorkStealing.main()
Target optimal time: 10000
Estimated optimal time: 10000
Thread per task executor submtition time: 5287.042065
Thread per task execution time: 5294.986375
Task distribution: UNIFORM
Fixed Thread Pool submition time: 21.02973
Fixed Thread Pool execution time: 12316.216906
Round Robin Executor submition time: 21.665127
Round Robin Executor execution time: 11912.027882
Work Stealing Executor submition time: 12.02714
Work Stealing Executor execution time: 11859.468408
Task distribution: PERIODIC
Fixed Thread Pool submition time: 15.916131
Fixed Thread Pool execution time: 11172.526169
Round Robin Executor submition time: 46.74018
Round Robin Executor execution time: 111818.794747
Work Stealing Executor submition time: 12.231049
Work Stealing Executor execution time: 11240.091623
Task distribution: PARETO
Fixed Thread Pool submition time: 9.801845
Fixed Thread Pool execution time: 11799.454853
Round Robin Executor submition time: 8.681832
Round Robin Executor execution time: 12656.467
Work Stealing Executor submition time: 9.333567
Work Stealing Executor execution time: 11987.597354

blackHole(difficulty)

> Task :WorkStealing.main()
Target optimal time: 10000
Estimated optimal time: 10000
Thread per task executor submtition time: 12539.549647
Thread per task execution time: 12548.637827
Task distribution: UNIFORM
Fixed Thread Pool submition time: 93.184324
Fixed Thread Pool execution time: 10995.948139
Round Robin Executor submition time: 47.122983
Round Robin Executor execution time: 10577.430234
Work Stealing Executor submition time: 15.102697
Work Stealing Executor execution time: 10597.966237
Task distribution: PERIODIC
Fixed Thread Pool submition time: 33.343451
Fixed Thread Pool execution time: 10987.990635
Round Robin Executor submition time: 185.495531
Round Robin Executor execution time: 102485.955007
Work Stealing Executor submition time: 76.031684
Work Stealing Executor execution time: 10673.063324
Task distribution: PARETO
Fixed Thread Pool submition time: 9.24489
Fixed Thread Pool execution time: 10825.731985
Round Robin Executor submition time: 9.552456
Round Robin Executor execution time: 10695.32205
Work Stealing Executor submition time: 9.692586
Work Stealing Executor execution time: 10471.885078

прирост от Work Stealing небольшой
Fixed Thread Pool использует общую очередь на все потоки
в то время как WorkStealing каждому потоку отдает свою очередь

LinkedBlockingDeque это очередь с двумя локами - на take и put
поэтому производительность в разы лучше
*/