package org.plantuml.idea.rendering;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.ConcurrencyUtil;
import org.jetbrains.annotations.NotNull;
import org.plantuml.idea.preview.ExecutionStatusPanel;

import java.util.Comparator;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


/**
 * This Executor executes Runnables sequentially and is so lazy that it executes only last RenderCommand submitted while
 * previously scheduled RenderCommand is running. Useful when you want to submit a lot of cumulative Runnables without
 * performance impact.
 *
 * 顺序执行 Runnable 对象
 * 如果之前已经有多个 RenderCommand,会通过合并某些属性，执行最后一个提交的 RenderCommand
 * 例如：在一个绘图应用程序中，你可能会频繁地提交绘图命令，而你只需要处理最新的命令。
 */
public class LazyApplicationPoolExecutor {
    public static final Logger logger = Logger.getInstance(LazyApplicationPoolExecutor.class);

    private TreeSet<RenderCommand> queue = new TreeSet<>(new Comparator<RenderCommand>() {
        @Override
        public int compare(RenderCommand t0, RenderCommand t1) {// t0小先返回t0
            int compare = Long.compare(t0.getStartAtNanos(), t1.startAtNanos);
            if (compare == 0) {
                compare = t0.version - t1.version; // 自然排序 从小到大
            }
            return compare;
        }
    });
    /**
     * 守护线程在 JVM 退出时会被自动终止
     */
    private ExecutorService executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), ConcurrencyUtil.newNamedThreadFactory("PlantUML integration plugin executor", true, Thread.NORM_PRIORITY));

    protected final Object POOL_THREAD_STICK = new Object();

    @NotNull
    public static LazyApplicationPoolExecutor getInstance() {
        return ApplicationManager.getApplication().getService(LazyApplicationPoolExecutor.class);
    }


    /**
     * 命令会被排队等待执行，但如果在该命令被执行之前有其他命令提交，则它可能会被新的命令取代（“吞噬”），因为命令提交到真正执行中间有个延迟时间
     * 使用 synchronized，确保了 queue 的一致性
     */
    public synchronized void submit(@NotNull final RenderCommand newCommand) {
        logger.debug("#submit ", newCommand);

        // 首次队列为0  在新命令入队时，会和队列中的所有命令进行比较，可能会取代旧命令
        for (RenderCommand oldCommand : queue) {
            if (oldCommand.isSame(newCommand)) {
                // 如果目标完全相同，跳过重复命令，新命令就不入队了
                if (oldCommand.containsTargets(newCommand.getTargets())) {
                    logger.debug("skipping duplicate ", oldCommand);
                    return;
                } else if (oldCommand.addTargetsIfPossible_blocking(newCommand)) {
                    logger.debug("targets added to ", oldCommand);
                } else {
                    // 新命令可以继续使用旧命令的result、renderRequest、newRenderCacheItem
                    addToQueue(new RenderCommand.DisplayExisting(newCommand, oldCommand));
                }
                return;
            } else if (oldCommand.containsTargets(newCommand.getTargets())) {/// 保留旧命令的target到新命令中
                logger.debug("replacing oldCommand ", oldCommand);
                newCommand.addTargets(oldCommand.getTargets());
                queue.remove(oldCommand);//todo there could be more of them
                addToQueue(newCommand);
                return;
            }
        }
        addToQueue(newCommand);
    }

    protected void addToQueue(@NotNull RenderCommand newCommand) {
        logger.debug("adding to queue ", newCommand);
        boolean add = queue.add(newCommand); /// 新命令入队，唤醒执行命令的线程，因为执行渲染命令的线程是在渲染命令还有很长延迟时间的时候阻塞的，所以被唤醒后重新从队列中获取
        newCommand.updateState(ExecutionStatusPanel.State.WAITING);
        synchronized (POOL_THREAD_STICK) {
            POOL_THREAD_STICK.notifyAll();/// 唤醒在 POOL_THREAD_STICK 对象上等待的线程。即执行渲染命令的线程，继续往下执行
        }
        scheduleNext();
    }

    private synchronized RenderCommand next() {
        Thread.interrupted(); //clear flag
        return queue.first(); // 返回版本号小的，即最先提交的
    }

    /**
     * 渲染线程开始执行
     */
    protected synchronized void scheduleNext() {
        logger.debug("scheduleNext");
        executor.submit(new MyRunnable());
    }

    public enum Delay {
        RESET_DELAY,     // 需要重置延迟的任务 指示插件系统在执行渲染任务之前等待一段时间。这段时间内，如果有新的渲染请求到达，系统将重置延迟计时器
        NOW,             // 立即执行的任务
        MAYBE_WITH_DELAY;// 可能需要延迟执行的任务

    }

    private class MyRunnable implements Runnable {

        @Override
        public void run() {
            RenderCommand command = next(); /// 从queue中获取最先提交的渲染请求
            while (command != null) {
                try {
                    long delayRemaining = command.getRemainingDelayMillis();
                    if (delayRemaining - 5 > 0) {//剩余延迟大于5ms，继续等待，等待的目的是确保任务不被过早执行，在等待过程中，新命令有可能和旧命令合并，因此按照顺序执行的旧命令中可能会含有新命令的渲染信息
                        logger.debug("waiting ", delayRemaining, "ms");
                        synchronized (POOL_THREAD_STICK) {
                            POOL_THREAD_STICK.wait(delayRemaining);/// 等待指定时间，唤醒后继续执行
                        }
                        command = next();// 继续从队列中获取先提交的命令
                        continue;
                    }
                } catch (InterruptedException e) { // 发生中断异常，线程中断标志为True，为确保后续命令正常执行，在next()中需清除中断标志
                    command = next();
                    continue;
                }
                try {
                    logger.debug("running command ", command);
                    long start = System.currentTimeMillis();
                    command.render();      // 核中核
                    command.displayResult();// 核中核
                    removeFromQueue(command);
                    logger.debug("command executed in ", System.currentTimeMillis() - start, "ms");
                } catch (Throwable e) {
                    logger.error(e);
                } finally {
                    queue.remove(command);
                    command = next();
                }
            }
        }


    }

    private synchronized void removeFromQueue(RenderCommand command) {
        logger.debug("removing from queue ", command);
        queue.remove(command);
    }
}
