import java.util.Random;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingDeque;

public class WorkStealing {

    static final int TASK_NUMBER = 10000;
    static final Random RANDOM = new Random(0);
    static final int THREAD_NUMBER = 10;

    interface MyTask {
        void run();
    }

    static class MyThreadPool {
        int n = 0;
        private final int threadNumber;
        Thread[] threads;
        Vector<LinkedBlockingDeque<MyTask>> threadTasks;

        public MyThreadPool(int threadNumber) {
            this.threadNumber = threadNumber;
            this.threads = new Thread[threadNumber];
            this.threadTasks = new Vector<>();
            for (int i = 0; i < threadNumber; i++)
                threadTasks.add(new LinkedBlockingDeque<>());

            for (int i = 0; i < threadNumber; i++) {
                final var copy = i;
                threads[i] = new Thread(() -> execute(copy));
            }
        }

        public void execute(int i) {
            while (true) {
                try {
                    threadTasks.get(i).takeFirst().run();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        public void assign(MyTask task) {
            threadTasks.get(n++ % threadNumber).addLast(task);
        }
    }

    public static void main(String[] args) {
        MyTask[] tasks = new MyTask[TASK_NUMBER];
        for (int i = 0; i < TASK_NUMBER; i++) {
            tasks[i] = () -> {
                try {
                    Thread.sleep(RANDOM.nextInt(1, 11));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            };
        }

        MyThreadPool mtp = new MyThreadPool(THREAD_NUMBER);

        for (int i = 0; i < tasks.length; i++) {
            mtp.assign(tasks[i]);
        }
    }
}
