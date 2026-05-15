package io.netty.channel;

import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.UnstableApi;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 *
 * 职责：
 * 1.连接线程与网络通信的枢纽：它实现了 EventLoop 接口，正式接管了网络通道（Channel）的注册与生命周期管理。
 * 2.管理尾部任务 (Tail Tasks)：这是该类引入的一个全新概念。它维护了一个专门的队列，用于存放那些必须在当前事件循环（I/O 处理和普通任务处理）彻底结束之后、线程准备再次休眠之前执行的收尾任务。
 * 3.提供通道状态监控：提供了初步的 API（带有 @UnstableApi 标记），用于统计和遍历当前这个事件循环上到底绑定了多少个活动的 Channel。
 * 继承
 * 继承自 SingleThreadEventExecutor：继承了底层的单线程状态机、普通任务队列（taskQueue）、定时任务队列调度以及优雅停机的所有能力。
 * 实现 EventLoop 接口：履行网络编程的契约，承诺自己能够接收 Channel 的注册，并将自己定义为一个专属网络事件的循环器。
 * @author 10567
 * @date 2026/05/09
 */
public abstract class SingleThreadEventLoop extends SingleThreadEventExecutor implements EventLoop {
    //定义了任务队列的默认最大容量
    protected static final int DEFAULT_MAX_PENDING_TASKS = Math.max(16, SystemPropertyUtil.getInt("io.netty.eventLoop.maxPendingTasks", Integer.MAX_VALUE));
    //尾部任务队列。这是一个独立于父类 taskQueue 的新队列。存放在这里的任务，只会在每一轮 EventLoop 的末尾（所有 I/O 事件处理完、所有普通/定时任务也执行完之后）才会被执行。
    private final Queue<Runnable> tailTasks;

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, threadFactory, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, threadFactory, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
        tailTasks = newTaskQueue(maxPendingTasks);
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor, boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
        tailTasks = newTaskQueue(maxPendingTasks);
    }

    /**
     *
     * @param parent 所属的“父亲”线程池
     * @param executor 物理线程的提供者（通常是 ThreadPerTaskExecutor）
     * @param addTaskWakesUp 唤醒优化标志
     * @param taskQueue 核心普通任务队列
     * @param tailTaskQueue 尾部任务队列
     * @param rejectedExecutionHandler 拒绝策略
     */
    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor, boolean addTaskWakesUp, Queue<Runnable> taskQueue, Queue<Runnable> tailTaskQueue,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, addTaskWakesUp, taskQueue, rejectedExecutionHandler);
        tailTasks = ObjectUtil.checkNotNull(tailTaskQueue, "tailTaskQueue");
    }

    @Override
    public EventLoopGroup parent() {
        return (EventLoopGroup) super.parent();
    }

    @Override
    public EventLoop next() {
        return (EventLoop) super.next();
    }

    @Override
    public ChannelFuture register(Channel channel) {
        return register(new DefaultChannelPromise(channel, this));
    }

    @Override
    public ChannelFuture register(final ChannelPromise promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        //服务端：调用ServerSocketChannel的unsafe(NioMessageUnsafe)
        //客户端：调用NioSocketChannel的unsafe（NioByteUnsafe）
        promise.channel().unsafe().register(this, promise);
        return promise;
    }

    @Deprecated
    @Override
    public ChannelFuture register(final Channel channel, final ChannelPromise promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        ObjectUtil.checkNotNull(channel, "channel");
        channel.unsafe().register(this, promise);
        return promise;
    }
//尾部任务管理

    /**
     * 向 tailTasks 队列添加一个尾部任务
     * @param task
     */
    public final void executeAfterEventLoopIteration(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        if (isShutdown()) {
            reject();
        }
        if (!tailTasks.offer(task)) {
            reject(task);
        }

        if (wakesUpForTask(task)) {
            wakeup(inEventLoop());
        }
    }


    final boolean removeAfterEventLoopIterationTask(Runnable task) {
        return tailTasks.remove(ObjectUtil.checkNotNull(task, "task"));
    }

    @Override
    protected void afterRunningAllTasks() {
        runAllTasksFrom(tailTasks);
    }

    @Override
    protected boolean hasTasks() {
        return super.hasTasks() || !tailTasks.isEmpty();
    }

    @Override
    public int pendingTasks() {
        return super.pendingTasks() + tailTasks.size();
    }

    @UnstableApi
    public int registeredChannels() {
        return -1;
    }

    @UnstableApi
    public Iterator<Channel> registeredChannelsIterator() {
        throw new UnsupportedOperationException("registeredChannelsIterator");
    }

    protected static final class ChannelsReadOnlyIterator<T extends Channel> implements Iterator<Channel> {
        private final Iterator<T> channelIterator;

        public ChannelsReadOnlyIterator(Iterable<T> channelIterable) {
            this.channelIterator =
                    ObjectUtil.checkNotNull(channelIterable, "channelIterable").iterator();
        }

        @Override
        public boolean hasNext() {
            return channelIterator.hasNext();
        }

        @Override
        public Channel next() {
            return channelIterator.next();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("remove");
        }

        @SuppressWarnings("unchecked")
        public static <T> Iterator<T> empty() {
            return (Iterator<T>) EMPTY;
        }

        private static final Iterator<Object> EMPTY = new Iterator<Object>() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public Object next() {
                throw new NoSuchElementException();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove");
            }
        };
    }
}
