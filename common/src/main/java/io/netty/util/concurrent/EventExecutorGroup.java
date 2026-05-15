package io.netty.util.concurrent;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * EventExecutorGroup
 *  1. 继承体系：
 *    1.1：继承 ScheduledExecutorService：具备了标准的线程池能力，包括执行异步任务和执行定时/周期性任务。但该接口重写了其中的大部分方法，
 *      主要是将原生的 java.util.concurrent.Future 替换为 Netty 自己实现的 io.netty.util.concurrent.Future，后者支持更加强大的异步回调机制（Listener 模式）。
 *    1.2：继承 Iterable<EventExecutor>：EventExecutorGroup 本质上是一个“组”（Group），内部包含多个 EventExecutor（单线程执行器）。继承 Iterable 意味着你可以使用 for-each 循环来遍历这个组里管理的所有“工人”（执行器线程）。
 * @author 10567
 * @date 2026/05/09
 */
public interface EventExecutorGroup extends ScheduledExecutorService, Iterable<EventExecutor> {
//生命周期与停机管理 (Lifecycle & Shutdown)
    /**
     * 判断当前执行器组是否已经开始进入关闭流程。一旦调用了 shutdownGracefully()，此方法就会返回 true
     * @return boolean
     */
    boolean isShuttingDown();

    /**
     * 无参的优雅停机方法。这是一个快捷方式，它内部会调用带有默认参数的重载方法
     * @return {@link Future }<{@link ? }>
     */
    Future<?> shutdownGracefully();

    /**
     * 核心的优雅停机方法：它不像传统的 shutdownNow() 那样粗暴地中断线程，
     * 而是会等待正在执行的任务完成，并在一段“静默期”内拒绝新连接但处理遗留任务。
     * @param quietPeriod 静默期。在这个时间内如果没有新的任务提交，执行器才会真正关闭。如果在这期间有新任务进来，静默期会重新计时。
     * @param timeout 最大超时时间。无论静默期是否一直被重置，一旦到达这个硬性时间，强制执行关闭。
     * @param unit 前面两个时间参数的时间单位
     * @return {@link Future }<{@link ? }>
     */
    Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit);

    /**
     *  获取一个用于监听执行器组终止状态的 Future。
     *  通常配合 shutdownGracefully() 使用，用于异步等待整个线程池完全销毁
     * @return {@link Future }<{@link ? }>
     */
    Future<?> terminationFuture();


    @Override
    @Deprecated
    void shutdown();

    @Override
    @Deprecated
    List<Runnable> shutdownNow();
//执行器路由

    /**
     * 从这个“组”中挑选并返回一个具体的 EventExecutor
     * @return {@link EventExecutor }
     */
    EventExecutor next();

    /**
     * 重写了 Iterable 的方法，返回一个只读的迭代器
     * @return {@link Iterator }<{@link EventExecutor }>
     */
    @Override
    Iterator<EventExecutor> iterator();
//普通任务提交:对原生 ExecutorService 提交方法的重写，主要改变是返回值变成了 Netty 的 Future。

    /**
     * @param task 异步执行的代码逻辑
     * @return {@link Future }<{@link ? }>
     */
    @Override
    Future<?> submit(Runnable task);

    @Override
    <T> Future<T> submit(Runnable task, T result);

    @Override
    <T> Future<T> submit(Callable<T> task);
    //定时与周期性任务调度:对原生 ScheduledExecutorService 调度方法的重写，同样是为了适配 Netty 的异步模型，返回值变为 ScheduledFuture。
    @Override
    ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);

    @Override
    <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit);

    @Override
    ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);

    @Override
    ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);
}
