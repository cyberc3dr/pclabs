import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/*
 * Заготовки к вводной лекции по синхронизации.
 *
 * Компиляция:
 *   javac SynchronizationExercises.java
 *
 * Запуск отдельной задачи, например:
 *   java MutexBankAccountTask
 *
 * В одном файле намеренно находится несколько package-private классов:
 * благодаря этому у каждой задачи есть собственный main, но компилировать
 * студентам нужно только один файл.
 */

class MutexBankAccountTask {
    /*
     * ЗАДАЧА 1. Mutex / Lock
     *
     * Несколько потоков одновременно пытаются снять деньги с одного счёта.
     * Реализуйте withdraw с помощью поля lock так, чтобы проверка баланса и
     * списание были одной критической секцией.
     *
     * Метод должен:
     *   1) вернуть false и не менять баланс, если денег недостаточно;
     *   2) иначе списать amount и вернуть true;
     *   3) обязательно освобождать lock, даже если внутри метода возникнет
     *      исключение.
     *
     * Поле balance можно читать и изменять только под этим lock.
     */
    private static final class BankAccount {
        private final Lock lock = new ReentrantLock();
        private int balance;

        private BankAccount(int initialBalance) {
            this.balance = initialBalance;
        }

        boolean withdraw(int amount) {
            lock.lock();
            final var success = balance >= amount;
            if(success) balance -= amount;
            lock.unlock();
            return success;
        }

        int balance() {
            lock.lock();
            final var bal = balance;
            lock.unlock();
            return bal;
        }
    }

    public static void main(String[] args) throws Exception {
        BankAccount account = new BankAccount(100);
        int callers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();

        try {
            for (int i = 0; i < callers; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    return account.withdraw(80);
                }));
            }

            start.countDown();
            int successfulWithdrawals = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get(3, TimeUnit.SECONDS)) {
                    successfulWithdrawals++;
                }
            }

            int balance = account.balance();
            if (successfulWithdrawals != 1 || balance != 20) {
                throw new AssertionError(
                        "Expected one successful withdrawal and balance 20, got "
                                + successfulWithdrawals + " and " + balance);
            }

            System.out.println("OK: balance=20, successful withdrawals=1");
        } finally {
            pool.shutdownNow();
        }
    }

    /*
     * Вывод, означающий, что задача, скорее всего, решена правильно:
     * OK: balance=20, successful withdrawals=1
     */
}


class MonitorOneElementBufferTask {
    /*
     * ЗАДАЧА 2. Monitor: synchronized + wait/notifyAll
     *
     * Реализуйте потокобезопасный буфер вместимостью ровно в один элемент.
     *
     * put(value) должен ждать, пока буфер занят.
     * take() должен ждать, пока буфер пуст.
     * Ожидание необходимо реализовать через monitor, wait и notifyAll:
     * активное ожидание (busy waiting) и sleep запрещены.
     *
     * Учтите, что после пробуждения условие необходимо проверить заново.
     */
    private static final class OneElementBuffer<T> {
        private T element;
        private boolean full;

        synchronized void put(T value) throws InterruptedException {
            // потоки разбудили, условие проверяется еще раз
            // если выполняется - еще раз ложится спать
            while (full) wait(); // важно - while
            full = true;
            element = value;
            notifyAll();
        }

        synchronized T take() throws InterruptedException {
            // аналогично
            while (!full) wait();
            full = false;
            var taken = element;
            element = null;
            notifyAll();
            return taken;
        }
    }

    public static void main(String[] args) throws Exception {
        int producers = 2;
        int consumers = 2;
        int valuesPerProducer = 1_000;
        int totalValues = producers * valuesPerProducer;

        OneElementBuffer<Integer> buffer = new OneElementBuffer<>();
        ExecutorService pool = Executors.newFixedThreadPool(producers + consumers);
        List<Future<Set<Integer>>> consumerResults = new ArrayList<>();
        List<Future<?>> producerResults = new ArrayList<>();

        try {
            for (int consumer = 0; consumer < consumers; consumer++) {
                consumerResults.add(pool.submit(() -> {
                    Set<Integer> received = new HashSet<>();
                    for (int i = 0; i < totalValues / consumers; i++) {
                        received.add(buffer.take());
                    }
                    return received;
                }));
            }

            for (int producer = 0; producer < producers; producer++) {
                int firstValue = producer * valuesPerProducer;
                producerResults.add(pool.submit(() -> {
                    for (int i = 0; i < valuesPerProducer; i++) {
                        try {
                            buffer.put(firstValue + i);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }));
            }

            for (Future<?> producerResult : producerResults) {
                producerResult.get(5, TimeUnit.SECONDS);
            }

            Set<Integer> allReceived = new HashSet<>();
            for (Future<Set<Integer>> consumerResult : consumerResults) {
                Set<Integer> received = consumerResult.get(5, TimeUnit.SECONDS);
                if (!allReceived.addAll(received)) {
                    throw new AssertionError("Some values were received more than once");
                }
            }

            if (allReceived.size() != totalValues) {
                throw new AssertionError(
                        "Expected " + totalValues + " different values, got "
                                + allReceived.size());
            }
            for (int expected = 0; expected < totalValues; expected++) {
                if (!allReceived.contains(expected)) {
                    throw new AssertionError("Lost value: " + expected);
                }
            }

            System.out.println("OK: transferred 2000 distinct values without loss");
        } catch (TimeoutException e) {
            throw new AssertionError("The program hung: check the waiting conditions", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /*
     * Вывод, означающий, что задача, скорее всего, решена правильно:
     * OK: transferred 2000 distinct values without loss
     */
}


class SemaphoreLimitTask {
    /*
     * ЗАДАЧА 3. Semaphore
     *
     * Дорогой внешний сервис разрешает выполнять не более трёх операций
     * одновременно. Реализуйте runLimited с помощью permits.
     *
     * Метод должен дождаться свободного permit, выполнить action и вернуть
     * permit при любом завершении action, в том числе при исключении.
     */
    private static final class LimitedService {
        private final Semaphore permits = new Semaphore(3);

        void runLimited(Runnable action) throws InterruptedException {
            permits.acquire();
            action.run();
            permits.release();
        }
    }

    public static void main(String[] args) throws Exception {
        int workers = 20;
        LimitedService service = new LimitedService();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger runningNow = new AtomicInteger();
        AtomicInteger maximumRunning = new AtomicInteger();
        List<Future<?>> results = new ArrayList<>();

        try {
            for (int i = 0; i < workers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    service.runLimited(() -> {
                        int current = runningNow.incrementAndGet();
                        maximumRunning.accumulateAndGet(current, Math::max);
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            runningNow.decrementAndGet();
                        }
                    });
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> result : results) {
                result.get(5, TimeUnit.SECONDS);
            }

            int observedMaximum = maximumRunning.get();
            if (observedMaximum != 3) {
                throw new AssertionError(
                        "Expected exactly 3 simultaneous operations, got " + observedMaximum);
            }

            System.out.println("OK: maximum simultaneous operations=3");
        } catch (TimeoutException e) {
            throw new AssertionError("The program did not finish in time", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /*
     * Вывод, означающий, что задача, скорее всего, решена правильно:
     * OK: maximum simultaneous operations=3
     */
}


class CasMaximumTask {
    /*
     * ЗАДАЧА 4. Compare-And-Set (CAS)
     *
     * Несколько потоков находят локальные значения и обновляют общий максимум.
     * Реализуйте updateMaximum как CAS-loop с помощью compareAndSet.
     *
     * Готовые методы updateAndGet, accumulateAndGet и getAndAccumulate в самой
     * реализации использовать нельзя: цель задачи — самостоятельно написать
     * цикл "прочитал -> вычислил -> попытался заменить -> повторил при неудаче".
     */
    private static final class ConcurrentMaximum {
        private final AtomicInteger maximum = new AtomicInteger(Integer.MIN_VALUE);

        void updateMaximum(int candidate) {
            int x;
            int newValue = candidate;
            do {
                x = maximum.get();
                if(x > candidate) newValue = x;
            } while (!maximum.compareAndSet(x, newValue));
        }

        int get() {
            return maximum.get();
        }
    }

    public static void main(String[] args) throws Exception {
        int[] values = {
                17, -5, 100, 42, 996, 13, 0, 741,
                88, 501, 997, 32, -100, 256, 900, 3
        };

        ConcurrentMaximum maximum = new ConcurrentMaximum();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> results = new ArrayList<>();

        try {
            for (int value : values) {
                results.add(pool.submit(() -> maximum.updateMaximum(value)));
            }
            for (Future<?> result : results) {
                result.get(3, TimeUnit.SECONDS);
            }

            if (maximum.get() != 997) {
                throw new AssertionError("Expected maximum 997, got " + maximum.get());
            }

            System.out.println("OK: maximum=997");
        } finally {
            pool.shutdownNow();
        }
    }

    /*
     * Вывод, означающий, что задача, скорее всего, решена правильно:
     * OK: maximum=997
     */
}


class FutureCombinationTask {
    /*
     * ЗАДАЧА 5. Future
     *
     * functions f и g независимы и выполняются долго. Реализуйте parallelSum:
     * запустите обе функции через переданный ExecutorService, получите два
     * Future и верните сумму результатов.
     *
     * Функции должны исполняться параллельно. Не вызывайте f() или g() напрямую
     * из parallelSum и не создавайте внутри метода новые потоки или новый pool.
     */
    static int parallelSum(ExecutorService pool) throws Exception {
        // TODO: запустите f и g, затем получите и сложите их результаты.
        throw new UnsupportedOperationException("parallelSum is not implemented");
    }

    private static int f() throws InterruptedException {
        Thread.sleep(700);
        return 20;
    }

    private static int g() throws InterruptedException {
        Thread.sleep(700);
        return 22;
    }

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long startedAt = System.nanoTime();

        try {
            int sum = parallelSum(pool);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAt);

            if (sum != 42) {
                throw new AssertionError("Expected sum 42, got " + sum);
            }
            if (elapsedMillis >= 1_200) {
                throw new AssertionError(
                        "The result is correct, but f and g probably ran sequentially ("
                                + elapsedMillis + " ms)");
            }

            System.out.println("OK: sum=42, both computations ran in parallel");
        } finally {
            pool.shutdownNow();
        }
    }

    /*
     * Вывод, означающий, что задача, скорее всего, решена правильно:
     * OK: sum=42, both computations ran in parallel
     */
}
