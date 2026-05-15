package io.netty.channel;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * 1. 管理网络 I/O 线程：它管理着一组 EventLoop（特殊的 EventExecutor，专门负责轮询和处理网络 I/O 事件）。
 * 2. 连接的注册与绑定 (Channel Registration)：当 Netty 接收到一个新的网络连接（Channel）时，EventLoopGroup
 * 负责从自己管理的池子中挑选出一个 EventLoop，并将这个 Channel 永久绑定到这个 EventLoop 上。这种“一夫一妻制”的绑定是 Netty 实现无锁化、保障处理顺序的核心设计。
 * @author 10567
 * @date 2026/05/09
 */
public interface EventLoopGroup extends EventExecutorGroup {
    /**
     * 重写了父接口的 next() 方法。
     * 父接口返回的是通用的 EventExecutor，而这里强制规定返回的是更具体的子接口 EventLoop。
     * @return {@link EventLoop }
     */
    @Override
    EventLoop next();

    /**
     * 将一个刚建立或刚初始化的网络连接，注册（绑定）到该 Group 里的某一个 EventLoop 线程上。
     * 内部逻辑其实是先调用 next() 选出一个线程，然后把 channel 丢给这个线程去管理。
     * @param channel channel - Netty 对网络连接的抽象表达
     * @return {@link ChannelFuture }
     */
    ChannelFuture register(Channel channel);


    ChannelFuture register(ChannelPromise promise);

    /**
     * @param channel 需要注册的网络连接
     * @param promise 由调用者传入的一个“承诺”对象（可写的 Future）。
     * @return {@link ChannelFuture }
     */
    @Deprecated
    ChannelFuture register(Channel channel, ChannelPromise promise);
}
