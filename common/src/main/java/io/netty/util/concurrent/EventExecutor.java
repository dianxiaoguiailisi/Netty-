package io.netty.util.concurrent;

/**
 * 1. 提供单一的执行上下文:
 *  “单个工人”（在 Netty 的绝大多数实现中，它严格绑定着唯一的一个系统物理线程）。它的基础职责是接收外部或内部提交的任务（普通的 Runnable 任务或定时的 Schedule 任务），并在自己的专属线程中串行地将这些任务执行完毕。
 *  2.维护线程安全与无锁化设计
 *  3. 充当异步结果的“原产地工厂”:通过 newPromise()、newSucceededFuture() 等方法创建出来的异步对象，天然地与当前这个 EventExecutor 强绑定
 *  4. 维系架构层级关系:通过 parent() 方法，它维持着与创建它的 EventExecutorGroup（即它的父级线程池管理器）的联系。
 * @author 10567
 * @date 2026/05/09
 */
public interface EventExecutor extends EventExecutorGroup {

    /**
     * 重写了父接口的方法。因为自己就是一个单独的执行器，所以它不需要像 Group 那样做路由选择，永远直接返回它自己 (this)。
     * @return {@link EventExecutor }
     */
    @Override
    EventExecutor next();

    /**
     * 返回管理当前这个执行器的父节点（即创建它的 EventExecutorGroup）。
     * 比如，一个具体的 NioEventLoop 线程，它的 parent 就是初始化时包含它的那个 NioEventLoopGroup 线程池。
     * @return {@link EventExecutorGroup }
     */
    EventExecutorGroup parent();


    /**
     * 判断当前正在执行代码的系统线程 (Thread.currentThread())，是不是这个 EventExecutor 专属绑定的那个线程。
     * Netty 中，如果你想修改某个 Channel 的数据，Netty 会先调用这个方法。如果返回 true，说明是自己人在操作，直接无锁修改；
     * 如果返回 false，说明是外部线程在操作，Netty 会把你的修改操作封装成一个任务，
     * 扔进这个执行器的任务队列里，等它自己醒来再去执行，从而完全避免了多线程抢夺资源的锁竞争。
     * @return boolean
     */
    boolean inEventLoop();

    /**
     * 允许你传入一个特定的线程去检查它是不是这个执行器的“宿主”线程。
     * @param thread
     * @return boolean
     */
    boolean inEventLoop(Thread thread);

//异步回调工厂
    /**
     * 创建一个新的、处于“未完成”状态的 Promise
     * @return {@link Promise }<{@link V }>
     */
    <V> Promise<V> newPromise();

    /**
     * 创建一个支持进度汇报的特殊 Promise。通常用于大文件传输等耗时操作，除了能通知最终的成功/失败，还能在中间通过监听器回调传输了多少字节（进度）
     * @return {@link ProgressivePromise }<{@link V }>
     */
    <V> ProgressivePromise<V> newProgressivePromise();
    /**
     * 创建一个一诞生就已经处于“成功”状态的 Future。
     * @param result
     * @return {@link Future }<{@link V }>
     */
    <V> Future<V> newSucceededFuture(V result);


    /**
     * 创建一个一诞生就已经处于“失败”状态的 Future。
     * @param cause
     * @return {@link Future }<{@link V }>
     */
    <V> Future<V> newFailedFuture(Throwable cause);
}
