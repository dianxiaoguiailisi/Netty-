/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;


/**
 * 职责
 * 1.驱动 Java NIO Selector：它是 Selector 的大管家，负责死循环调用 select() 方法，监听来自操作系统的网络事件（如连接建立、数据可读、数据可写）。
 * 2.处理 I/O 事件与分发：当网络事件就绪时，提取数据并触发 ChannelPipeline 的处理链路。
 * 3.精准的时间与任务调度：在处理完网络 I/O 后，它还要负责执行 taskQueue 里的普通异步任务和定时任务，并在两者之间取得完美的时间平衡（通过 ioRatio）。
 * 4.底层 Bug 的“清道夫”：它内部硬编码修复了多个 Java JDK 遗留的严重 NIO Bug（如著名的 Epoll 100% CPU 空转 Bug）。
 * NioEventLoop （专注 NIO 具体的网络读写和 Selector 管理）
 *   ↳ SingleThreadEventLoop （提供 Channel 注册机制和尾部任务管理）
 * ↳ SingleThreadEventExecutor （提供单线程状态机、普通任务队列）
 * ↳ AbstractScheduledEventExecutor （提供优先级队列、处理定时任务）
 * ↳ AbstractEventExecutor （桥接 JDK 线程池与 Netty Future）
 * @author 10567
 * @date 2026/05/09
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);
    //清理间隔。当取消的 SelectionKey（比如断开连接）达到 256 个时，强制清理一次 Selector。防止失效的 Key 一直堆积导致内存泄漏。
    private static final int CLEANUP_INTERVAL = 256;
    //JDK 原生的 Selector 使用 HashSet 存放就绪的事件，遍历效率低且产生对象碎片
    //Netty 默认通过反射把它偷换成了自定义的数组，实现 O(1) 的遍历速度
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION = SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);
    //解决 Epoll 空转 Bug 的核心阈值
    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    static {
        if (PlatformDependent.javaVersion() < 7) {
            final String key = "sun.nio.ch.bugLevel";
            final String bugLevel = SystemPropertyUtil.get(key);
            if (bugLevel == null) {
                try {
                    AccessController.doPrivileged(new PrivilegedAction<Void>() {
                        @Override
                        public Void run() {
                            System.setProperty(key, "");
                            return null;
                        }
                    });
                } catch (final SecurityException e) {
                    logger.debug("Unable to get/set System Property: " + key, e);
                }
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }
    //当前正在工作的高性能多路复用器(可能经过了 Netty 的数组替换优化）。
    private Selector selector;
    //未经过 Netty 包装的、原汁原味的 JDK 原始 Selector
    private Selector unwrappedSelector;
    //表示当前selector上就绪事件的集合
    private SelectedSelectionKeySet selectedKeys;
    //用于创建 Selector 和 Channel 的工厂类
    private final SelectorProvider provider;
//唤醒与并发控制
    //线程当前正处于清醒且忙碌的状态，不需要被唤醒
    private static final long AWAKE = -1L;
    //表示线程当前正在死等（无期限阻塞），必须立刻被唤醒。
    private static final long NONE = Long.MAX_VALUE;
    //记录着下一个定时任务到期的时间
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);
    //选择策略
    private final SelectStrategy selectStrategy;
//事件循环运行状态
    //I/O 比例（0-100）。默认是 50
    private volatile int ioRatio = 50;
    //记录当前累计了多少个被取消的 Key
    private int cancelledKeys;
    //标志位，如果清理了太多的 Key，需要标记为 true，让 Selector 重新做一次 select() 以刷新内部状态
    private boolean needsToSelectAgain;

    /**
     *
     * @param parent 所属的线程组，包含多线程的 Group
     * @param executor 物理线程提供者。NioEventLoop 本身只是一个逻辑上的任务调度器，它没有继承 Thread
     *                 只有当这个 EventLoop 真正需要开始干活时，它才会调用 executor.execute(...)，向这个执行器申请一个真实的系统物理线程来跑自己的死循环。
     * @param selectorProvider NIO 基础服务提供者
     * @param strategy 选择策略
     * @param rejectedExecutionHandler 拒绝策略
     * @param taskQueueFactory 普通任务队列工厂
     * @param tailTaskQueueFactory 尾部任务队列工厂
     */
    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider, SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory taskQueueFactory, EventLoopTaskQueueFactory tailTaskQueueFactory) {
        //调用父类构造函数
        super(parent, executor, false, newTaskQueue(taskQueueFactory), newTaskQueue(tailTaskQueueFactory), rejectedExecutionHandler);
        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");
        //下面三行创建出selector实例
        final SelectorTuple selectorTuple = openSelector();
        this.selector = selectorTuple.selector;
        this.unwrappedSelector = selectorTuple.unwrappedSelector;
    }

    private static Queue<Runnable> newTaskQueue(EventLoopTaskQueueFactory queueFactory) {
        if (queueFactory == null) {
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    /**
     * 创建selector实例
     * @return {@link SelectorTuple }
     */
    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName("sun.nio.ch.SelectorImpl", false, PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        if (!(maybeSelectorImplClass instanceof Class) ||
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        //创建一个SelectedSelectionKeySet
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                    }

                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector, new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        ObjectUtil.checkNotNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        ObjectUtil.checkNotNull(task, "task");

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    @Override
    public Iterator<Channel> registeredChannelsIterator() {
        assert inEventLoop();
        final Set<SelectionKey> keys = selector.keys();
        if (keys.isEmpty()) {
            return ChannelsReadOnlyIterator.empty();
        }
        return new Iterator<Channel>() {
            final Iterator<SelectionKey> selectionKeyIterator =
                    ObjectUtil.checkNotNull(keys, "selectionKeys")
                            .iterator();
            Channel next;
            boolean isDone;

            @Override
            public boolean hasNext() {
                if (isDone) {
                    return false;
                }
                Channel cur = next;
                if (cur == null) {
                    cur = next = nextOrDone();
                    return cur != null;
                }
                return true;
            }

            @Override
            public Channel next() {
                if (isDone) {
                    throw new NoSuchElementException();
                }
                Channel cur = next;
                if (cur == null) {
                    cur = nextOrDone();
                    if (cur == null) {
                        throw new NoSuchElementException();
                    }
                }
                next = nextOrDone();
                return cur;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove");
            }

            private Channel nextOrDone() {
                Iterator<SelectionKey> it = selectionKeyIterator;
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    if (key.isValid()) {
                        Object attachment = key.attachment();
                        if (attachment instanceof AbstractNioChannel) {
                            return (AbstractNioChannel) attachment;
                        }
                    }
                }
                isDone = true;
                return null;
            }
        };
    }

    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel();
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /**
     * 核心方法：永不停止的死循环（直到被关闭）。它完美地展现了 Netty 是如何在一个单线程中，精准地协调 网络 I/O 读写、普通异步任务 和 定时任务，并且巧妙地规避底层 JDK Bug 的。
     */
    @Override
    protected void run() {
        int selectCnt = 0;// 记录 Selector 连续空轮询的次数，用于检测 Epoll Bug
        // EventLoop 的死循环，引擎启动后就一直在里面转
        for (;;) {
            try {
                //>=0,表示selector的返回值，注册在多路复用器上就绪的个数
                //<0:常量状态:SELECT(NioEventLoop只有-1)
                int strategy;
                try {
                    // 本次循环的策略：
                    // 如果普通任务队列里有任务，selectStrategy会直接调用 selectNow() 获取当前就绪的网络事件，并且返回事件数，防止线程阻塞导致任务饿死。
                    // 如果队列没任务，会返回 SelectStrategy.SELECT，指示线程阻塞等待网络事件。
                    strategy = selectStrategy.calculateStrategy(selectNowSupplier, hasTasks());
                    switch (strategy) {
                    case SelectStrategy.CONTINUE:
                        continue;// 重新开始下一轮循环

                    case SelectStrategy.BUSY_WAIT:// NIO 不支持 CPU 忙等待(Spin-wait)策略
                    case SelectStrategy.SELECT:
                        // 获取优先队列定时任务的截至时间
                        long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
                        if (curDeadlineNanos == -1L) {
                            curDeadlineNanos = NONE;
                        }
                        //唤醒核心：告诉外部线程，我打算一直睡到 curDeadlineNanos 这个时间。如果外部线程在该时间之前提交了新任务，外部线程就会调用 selector.wakeup() 叫醒我。
                        nextWakeupNanos.set(curDeadlineNanos);
                        try {
                            // 再次确认有没有任务，防止在计算时间差的间隙有新任务进来。
                            if (!hasTasks()) {
                                // 物理线程阻塞到 curDeadlineNanos，除非有网络事件发生或被外部 wakeup() 唤醒。
                                strategy = select(curDeadlineNanos);//strategy是网络事件的个数
                            }
                        } finally {
                            // 5. 醒来后的第一件事：把唤醒时间设为 AWAKE (-1)。
                            // lazySet 是一个开销极小的 CAS 操作。这告诉外部线程：“我已经醒了，如果你再投递任务，别再去调昂贵的底层系统调用 wakeup() 叫我了！”
                            nextWakeupNanos.lazySet(AWAKE);
                        }
                    default:
                    }
                } catch (IOException e) {
                    rebuildSelector0();// 如果在 select 期间发生了底层 I/O 异常，重建 Selector，重置空转计数器，记录日志，然后继续下一轮循环
                    selectCnt = 0;
                    handleLoopException(e);
                    continue;
                }
                //最终strategy表示就绪的channel个数
                selectCnt++;// 轮询计数器 +1
                cancelledKeys = 0;// 重置取消键计数器
                needsToSelectAgain = false;
                //线程处理I/O任务的比例 默认50%
                final int ioRatio = this.ioRatio;
                //表示本轮线程是否处理过本地任务
                boolean ranTasks;
                // I/O 比例 100%说明I/O优先，IO处理完后，再处理本地任务
                if (ioRatio == 100) {
                    try {
                        //条件成立：当前NioEventLoop内的selector上有就绪的事件
                        if (strategy > 0) {
                            processSelectedKeys();// 处理所有的网络 I/O 事件（读/写/连接）
                        }
                    } finally {
                        //执行本地队列的任务
                        ranTasks = runAllTasks();
                    }
                } else if (strategy > 0) {
                    //条件成立：当前NioEventLoop内的selector上有就绪的事件
                    final long ioStartTime = System.nanoTime();// 记录开始处理 I/O 的时间
                    try {
                        // 处理网络 I/O 事件
                        processSelectedKeys();
                    } finally {
                        //IO事件的处理总耗时
                        final long ioTime = System.nanoTime() - ioStartTime;
                        //根据IO处理时间，计算处理本地任务的最大时间
                        ranTasks = runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                } else {
                    // 条件成立：当前NioEventLoop内的selector上没有就绪的事件，是被唤醒的，只处理本地任务
                    //执行最少数量的本地任务
                    ranTasks = runAllTasks(0);
                }
                //Epoll Bug 检测与优雅停机
                if (selectReturnPrematurely(selectCnt, ranTasks, strategy)) {
                    //selectReturnPrematurely 内部逻辑：如果刚才既没有跑任务，也没有网络事件，却异常醒来了，就认为是一次“过早返回”。
                    //当连续发生 512 次时，它会在内部调用 rebuildSelector() 重建多路复用器，然后返回 true。
                    selectCnt = 0;// 如果触发了 Bug 并重建了，或者正常跑了任务，计数器清零
                } else if (unexpectedSelectorWakeup(selectCnt)) {
                    selectCnt = 0;// 处理极其罕见的异常唤醒情况
                }
            } catch (CancelledKeyException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?", selector, e);
                }
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                handleLoopException(t);
            } finally {
                // 这是整个死循环唯一的【出口】
                try {
                    if (isShuttingDown()) {// 如果收到了 shutdownGracefully() 停机指令
                        closeAll();// 关闭挂在这个 EventLoop 上的所有 Channel
                        if (confirmShutdown()) {// 检查静默期，执行完最后残留的任务
                            return;
                        }
                    }
                } catch (Error e) {
                    throw e;
                } catch (Throwable t) {
                    handleLoopException(t);
                }
            }
        }
    }
    
    private boolean selectReturnPrematurely(int selectCnt, boolean ranTasks, int strategy) {
        if (ranTasks || strategy > 0) {
            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS && logger.isDebugEnabled()) {
                logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                    selectCnt - 1, selector);
            }
            return true;
        }
        return false;
    }

    private boolean unexpectedSelectorWakeup(int selectCnt) {
        if (Thread.interrupted()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Selector.select() returned prematurely because " + "Thread.currentThread().interrupt() was called. Use " + "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
            }
            return true;
        }
        if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            // The selector returned prematurely many times in a row.
            // Rebuild the selector to work around the problem.
            logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                    selectCnt, selector);
            rebuildSelector();
            return true;
        }
        return false;
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
        }
    }

    /**
     * 处理IO事件人口函数
     */
    private void processSelectedKeys() {
        if (selectedKeys != null) {
            processSelectedKeysOptimized();
        } else {//替换JDK 内部的 HashSet 失败
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        if (selectedKeys.isEmpty()) {
            return;
        }
        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }


    private void processSelectedKeysOptimized() {
        //遍历就绪事件集合
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            selectedKeys.keys[i] = null;
            //获取注册时候向selector提供的channel附件对象，NioSocketChannel 或 NioServerSocketChannel
            final Object a = k.attachment();
            //大部分成立,调用具体的 processSelectedKey
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                //允许开发者越过 Netty 的 Channel 体系，直接向 EventLoop 提交自定义的底层 JDK 原生 NIO 处理任务
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }
            if (needsToSelectAgain) {
                selectedKeys.reset(i + 1);
                selectAgain();
                i = -1;
            }
        }
    }

    /**
     * @param k
     * @param ch
     */
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        //通过附件(Channel)拿到unsafe对象：
        //1.NioServerSocketChannel->NioMessageUnsafe
        //2.NioSocketChannel->NioByteUnsafe
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                return;
            }
            if (eventLoop == this) {
                unsafe.close(unsafe.voidPromise());
            }
            return;
        }
        try {
            int readyOps = k.readyOps();
            //处理连接建立事件:仅发生在客户端发起非阻塞 connect() 调用，且操作系统内核完成 TCP 三次握手后。
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                unsafe.finishConnect();
            }
            //处理可写事件 (OP_WRITE)
            //底层的 Socket 发送缓冲区（Send Buffer）从满载状态变为有空闲空间时触发。
            // 通常发生在之前写入大量数据导致缓冲区写满，Netty 注册了 OP_WRITE 监听，系统缓冲区释放出空间后通知 Netty。
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
               unsafe.forceFlush();
            }
            //处理可读与接入事件 (OP_READ | OP_ACCEPT)
            //OP_READ：对端发送了数据，当前系统的 Socket 接收缓冲区有数据可读；或者对端关闭了连接wad，触发可读事件，读取结果为 -1。
            //socketChannel->NioByteUnsafe.read()-->读取缓冲区数据，并将数据相应到pipeline中
            //OP_ACCEPT：仅针对ServerSocketChannel，表示有新的客户端发起了 TCP 握手请求,此时创建客户端socketChannel
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            default:
                 break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            selector.wakeup();
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        return selector.selectNow();
    }

    /**
     * 带截至时间的
     * @param deadlineNanos
     * @return int
     * @throws IOException
     */
    private int select(long deadlineNanos) throws IOException {
        //当任务队列为空，且没有任何定时任务时（deadlineNanos 被设置为了 Long.MAX_VALUE）。
        if (deadlineNanos == NONE) {
            //物理线程无限期阻塞，直到有新的网络事件发生，或者被其他线程强制调用 selector.wakeup() 唤醒。
            return selector.select();
        }
        //5 微秒的极速容忍度
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
