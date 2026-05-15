package io.netty.util.concurrent;

import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import org.jetbrains.annotations.Async.Execute;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 职责：
 * 1.JDK 与 Netty 的桥梁：继承了 JDK 原生的线程池基类，通过巧妙地重写内部方法，将 JDK 标准的 Runnable/Callable 任务和返回值，无缝偷换（桥接）成了 Netty 特有的 Promise/Future 模型。
 * 2。提供“单体执行器”的默认行为：作为一个单个的 EventExecutor，它定义了一些默认逻辑，比如“它的下一个执行器就是它自己”、“遍历它只会得到它自己”。
 * 3.异步结果的工厂：提供了标准的 Netty Future 和 Promise 的实例化工厂方法，确保这些异步对象与当前执行器绑定。
 * @author 10567
 * @date 2026/05/09
 */
public abstract class AbstractEventExecutor extends AbstractExecutorService implements EventExecutor {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractEventExecutor.class);
    //优雅停机时默认的“静默期”为 2 秒
    static final long DEFAULT_SHUTDOWN_QUIET_PERIOD = 2;
    //优雅停机时的默认最大超时时间为 15 秒
    static final long DEFAULT_SHUTDOWN_TIMEOUT = 15;
    //指向它的“父亲”（即创建并管理它的线程池组，比如 NioEventLoopGroup）
    private final EventExecutorGroup parent;
    //
    private final Collection<EventExecutor> selfCollection = Collections.<EventExecutor>singleton(this);

    protected AbstractEventExecutor() {
        this(null);
    }

    protected AbstractEventExecutor(EventExecutorGroup parent) {
        this.parent = parent;
    }
//身份与层级

    @Override
    public EventExecutorGroup parent() {
        return parent;
    }

    /**
     * 返回 this。因为它是单体执行器，没有别的兄弟节点可供路由，“下一个”执行器永远是它自己。
     * @return {@link EventExecutor }
     */
    @Override
    public EventExecutor next() {
        return this;
    }

    @Override
    public boolean inEventLoop() {
        return inEventLoop(Thread.currentThread());
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return selfCollection.iterator();
    }
//生命周期与停机

    /**
     * 无参方法，直接使用顶部的默认常量（2 秒静默，15 秒超时）调用父接口的优雅停机逻辑。
     * @return {@link Future }<{@link ? }>
     */
    @Override
    public Future<?> shutdownGracefully() {
        return shutdownGracefully(DEFAULT_SHUTDOWN_QUIET_PERIOD, DEFAULT_SHUTDOWN_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    @Deprecated
    public abstract void shutdown();

    @Override
    @Deprecated
    public List<Runnable> shutdownNow() {
        shutdown();
        return Collections.emptyList();
    }
//异步结果工厂
    @Override
    public <V> Promise<V> newPromise() {
        return new DefaultPromise<V>(this);
    }

    @Override
    public <V> ProgressivePromise<V> newProgressivePromise() {
        return new DefaultProgressivePromise<V>(this);
    }

    @Override
    public <V> Future<V> newSucceededFuture(V result) {
        return new SucceededFuture<V>(this, result);
    }

    @Override
    public <V> Future<V> newFailedFuture(Throwable cause) {
        return new FailedFuture<V>(this, cause);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return (Future<?>) super.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return (Future<T>) super.submit(task, result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return (Future<T>) super.submit(task);
    }
//如何把 JDK 的 Future 强行变成 Netty 的 Future 的。

    /**
     * JDK 默认把任务包装成 FutureTask，但 Netty 在这里重写了它，把任务包装成了 Netty 自己写的 PromiseTask。
     * @param runnable
     * @param value
     * @return {@link RunnableFuture }<{@link T }>
     */
    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new PromiseTask<T>(this, runnable, value);
    }

    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new PromiseTask<T>(this, callable);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    /**
     * 执行任务，如果任务内部抛出了未捕获的异常，它会把它 catch 住并打印一条警告日志，
     * 而不会导致整个底层的物理线程崩溃（也就是不会让 EventLoop 死掉）。
     * @param task
     */
    protected static void safeExecute(Runnable task) {
        try {
            runTask(task);
        } catch (Throwable t) {
            logger.warn("A task raised an exception. Task: {}", task, t);
        }
    }

    /**
     * @param task
     */
    protected static void runTask(@Execute Runnable task) {
        task.run();
    }

    /**
     * 懒执行。默认直接调用 execute(task)。但在某些特定子类中可以被重写，用于提交那些不紧急的、不需要立刻唤醒休眠线程的任务。
     * @param task
     */
    @UnstableApi
    public void lazyExecute(Runnable task) {
        execute(task);
    }

    @Deprecated
    public interface LazyRunnable extends Runnable { }
}
