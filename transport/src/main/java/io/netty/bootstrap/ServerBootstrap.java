package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 服务端启动类
 * 除了拥有父类的属性外，它专门增加了针对 Child Channel（子通道，即连入的客户端） 的配置项
 * @author 10567
 * @date 2026/05/15
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);
    //子通道的网络参数字典。当新的客户端连接进来被创建成 Channel 后，Netty 会把这里配置的参数（如 SO_KEEPALIVE, TCP_NODELAY）应用给该客户端通道
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();
    //子通道的属性字典。为每一个新接入的客户端连接附加默认的上下文数据
    private final Map<AttributeKey<?>, Object> childAttrs = new ConcurrentHashMap<AttributeKey<?>, Object>();
    //配置信息的只读视图
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this);
    //服务端的 Worker 线程组
    private volatile EventLoopGroup childGroup;
    //子通道的业务处理器
    private volatile ChannelHandler childHandler;

    public ServerBootstrap() { }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        childAttrs.putAll(bootstrap.childAttrs);
    }

    /**
     * 只传递一个EventLoopGroup，调用group(group, group)重载函数
     * @param group EventLoopGroup线程组
     * @return {@link ServerBootstrap }
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);//重载函数
    }

    /**
     * group初始化重载函数
     * @param parentGroup boss 线程组
     * @param childGroup worker 线程组
     * @return {@link ServerBootstrap }
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        //初始化父类的parentGroup（服务端这里是boss线程组）
        super.group(parentGroup);
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = ObjectUtil.checkNotNull(childGroup, "childGroup");
        return this;
    }

    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        ObjectUtil.checkNotNull(childOption, "childOption");
        synchronized (childOptions) {
            if (value == null) {
                childOptions.remove(childOption);
            } else {
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        ObjectUtil.checkNotNull(childKey, "childKey");
        if (value == null) {
            childAttrs.remove(childKey);
        } else {
            childAttrs.put(childKey, value);
        }
        return this;
    }

    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
        return this;
    }

    /**
     * 服务端：父类bind->dobind->initAndRegister->创建NioServerSocketChannel->调用子类实现的init
     * @param channel NioServerSocketChannel
     * @throws Throwable
     */
    @Override
    void init(Channel channel) throws Throwable {
        //1.初始化NioServerSocketChannel属性
        setChannelOptions(channel, newOptionsArray(), logger);
        setAttributes(channel, newAttributesArray());
        ChannelPipeline p = channel.pipeline();
        final EventLoopGroup currentChildGroup = childGroup;
        final ChannelHandler currentChildHandler = childHandler;
        final Entry<ChannelOption<?>, Object>[] currentChildOptions = newOptionsArray(childOptions);
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs = newAttributesArray(childAttrs);
        final Collection<ChannelInitializerExtension> extensions = getInitializerExtensions();
        //2.向NioServerSocketChannel的pipeline中添加ChannelInitializer处理器,并提交绑定的线程NioEventLoop一个任务（向父pipeline中添加一个ServerBootstrapAcceptor处理器）
        //ChannelInitializer(压缩包):通过适配器实现了handler接口，目的是延迟初始化PipeLine（当Pipeline绑定的Channel激活后初始化）
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) {
                final ChannelPipeline pipeline = ch.pipeline();
                ChannelHandler handler = config.handler();//父通道处理器
                if (handler != null) {
                    pipeline.addLast(handler);//向pipeLine中添加自定义配置的父通道Handler
                }
                //提交任务
                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        //参数1：ch:当前的 NioServerSocketChannel（即服务端的监听通道自身）;参数2：Worker 线程池；参数3：子通道处理器
                        pipeline.addLast(new ServerBootstrapAcceptor(ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs, extensions));
                    }
                });
            }
        });
        if (!extensions.isEmpty() && channel instanceof ServerChannel) {
            ServerChannel serverChannel = (ServerChannel) channel;
            for (ChannelInitializerExtension extension : extensions) {
                try {
                    extension.postInitializeServerListenerChannel(serverChannel);
                } catch (Exception e) {
                    logger.warn("Exception thrown from postInitializeServerListenerChannel", e);
                }
            }
        }
    }

    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

        private final EventLoopGroup childGroup;
        private final ChannelHandler childHandler;
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        private final Runnable enableAutoReadTask;
        private final Collection<ChannelInitializerExtension> extensions;

        ServerBootstrapAcceptor(
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs,
                Collection<ChannelInitializerExtension> extensions) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;
            this.extensions = extensions;
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        /**
         * 处理Read事件
         * 接收刚刚建立的客户端连接（子通道），为它配置相关的参数（Options）、属性（Attributes）、业务处理器（Handler），并最终将它注册到 Worker 线程池（childGroup）中，使其真正开始监听后续的网络读写事件。
         * @param ctx 当前 Handler（即 ServerBootstrapAcceptor）在 Pipeline 中的上下文对象
         * @param msg 上一步骤中包装好的新客户端连接（NioSocketChannel 对象）。
         */
        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            final Channel child = (Channel) msg;
            //把 childHandler 添加到这个新客户端通道的 Pipeline 中
            child.pipeline().addLast(childHandler);
            //设置 TCP 参数配置:将你在 ServerBootstrap.childOption(...) 中配置的 TCP 参数（如 SO_KEEPALIVE, TCP_NODELAY）批量应用到这根新通道上。
            try {
                setChannelOptions(child, childOptions, logger);
            } catch (Throwable cause) {
                forceClose(child, cause);
                return;
            }
            //设置自定义属性:将你在 ServerBootstrap.childAttr(...) 中配置的自定义属性绑定到这个通道上
            setAttributes(child, childAttrs);
            //. 处理扩展点 (Netty 高级特性)
            if (!extensions.isEmpty()) {
                for (ChannelInitializerExtension extension : extensions) {
                    try {
                        extension.postInitializeServerChildChannel(child);
                    } catch (Exception e) {
                        logger.warn("Exception thrown from postInitializeServerChildChannel", e);
                    }
                }
            }
            //注册到 Worker 线程池:
            try {
                //childGroup 是 Worker EventLoopGroup。
                // 从线程池中轮询选出一个 EventLoop（Reactor 线程），然后把当前这个客户端通道绑定到这个线程的 Selector 上。从此以后，这个客户端的所有 I/O 操作都由这根专属线程负责，实现了无锁化。
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    //异步监听注册结果
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        //如果注册成功（future.isSuccess()），之前添加的 ChannelInitializer 就会被触发，完成解码器、业务代码的组装
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());//如果注册失败（例如线程池已经关闭了），则在回调中强制关闭通道，释放资源
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        synchronized (childOptions) {
            return copiedMap(childOptions);
        }
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
