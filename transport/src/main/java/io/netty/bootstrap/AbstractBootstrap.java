package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 引导类的公共抽象基类
 * @author 10567
 * @date 2026/05/15
 */
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {

    private static final boolean CLOSE_ON_SET_OPTION_FAILURE = SystemPropertyUtil.getBoolean("io.netty.bootstrap.closeOnSetOptionFailure", true);
    @SuppressWarnings("unchecked")
    private static final Map.Entry<ChannelOption<?>, Object>[] EMPTY_OPTION_ARRAY = new Map.Entry[0];
    @SuppressWarnings("unchecked")
    private static final Map.Entry<AttributeKey<?>, Object>[] EMPTY_ATTRIBUTE_ARRAY = new Map.Entry[0];
    // 核心线程组：
    //客户端：负责所有连接和读写操作；服务端：Boss 线程组，专门负责接收（Accept）客户端的连接
    volatile EventLoopGroup group;
    //通道工厂。用于实例化底层的网络通道类
    @SuppressWarnings("deprecation")
    private volatile ChannelFactory<? extends C> channelFactory;
    //本地需要绑定的网络地址（IP 和端口）服务端启动时绑定此地址，客户端可以通过它指定绑定的本地端口
    private volatile SocketAddress localAddress;
    //父通道的 TCP/IP 网络参数字典。例如 SO_BACKLOG（连接请求队列大小）等底层 Socket 参数
    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<ChannelOption<?>, Object>();
    //父通道的自定义属性字典。允许你在运行时为这个通道附带一些自定义的上下文数据。
    private final Map<AttributeKey<?>, Object> attrs = new ConcurrentHashMap<AttributeKey<?>, Object>();
    //父通道的处理器。服务端通常用它来添加诸如 LoggingHandler 之类的日志记录器，以监控连接的建立过程
    private volatile ChannelHandler handler;
    //类加载器
    private volatile ClassLoader extensionsClassLoader;

    AbstractBootstrap() {
    }

    AbstractBootstrap(AbstractBootstrap<B, C> bootstrap) {
        group = bootstrap.group;
        channelFactory = bootstrap.channelFactory;
        handler = bootstrap.handler;
        localAddress = bootstrap.localAddress;
        synchronized (bootstrap.options) {
            options.putAll(bootstrap.options);
        }
        attrs.putAll(bootstrap.attrs);
        extensionsClassLoader = bootstrap.extensionsClassLoader;
    }

    public B group(EventLoopGroup group) {
        ObjectUtil.checkNotNull(group, "group");
        if (this.group != null) {
            throw new IllegalStateException("group set already");
        }
        this.group = group;
        return self();
    }

    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this;
    }


    public B channel(Class<? extends C> channelClass) {
        // 这里会new 一个默认的 ReflectiveChannelFactory。
        // 在这个工厂的构造方法内部，它会去获取 NioSocketChannel.class 的无参构造函数并缓存下来。
        //执行完这行代码后，内存中并没有多出一个 NioSocketChannel 对象，只有一个知道“如何去制造 NioSocketChannel”的工厂。
        return channelFactory(new ReflectiveChannelFactory<C>(
                ObjectUtil.checkNotNull(channelClass, "channelClass")));//确保传递的channel不是null
    }

    /**
     * @deprecated Use {@link #channelFactory(io.netty.channel.ChannelFactory)} instead.
     */
    @Deprecated
    public B channelFactory(ChannelFactory<? extends C> channelFactory) {
        ObjectUtil.checkNotNull(channelFactory, "channelFactory");
        if (this.channelFactory != null) {
            throw new IllegalStateException("channelFactory set already");
        }

        this.channelFactory = channelFactory;
        return self();
    }


    @SuppressWarnings({ "unchecked", "deprecation" })
    public B channelFactory(io.netty.channel.ChannelFactory<? extends C> channelFactory) {
        return channelFactory((ChannelFactory<C>) channelFactory);
    }

    /**
     * The {@link SocketAddress} which is used to bind the local "end" to.
     */
    public B localAddress(SocketAddress localAddress) {
        this.localAddress = localAddress;
        return self();
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(int inetPort) {
        return localAddress(new InetSocketAddress(inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(String inetHost, int inetPort) {
        return localAddress(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(InetAddress inetHost, int inetPort) {
        return localAddress(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they got
     * created. Use a value of {@code null} to remove a previous set {@link ChannelOption}.
     */
    public <T> B option(ChannelOption<T> option, T value) {
        ObjectUtil.checkNotNull(option, "option");
        synchronized (options) {
            if (value == null) {
                options.remove(option);
            } else {
                options.put(option, value);
            }
        }
        return self();
    }

    /**
     * Allow to specify an initial attribute of the newly created {@link Channel}.  If the {@code value} is
     * {@code null}, the attribute of the specified {@code key} is removed.
     */
    public <T> B attr(AttributeKey<T> key, T value) {
        ObjectUtil.checkNotNull(key, "key");
        if (value == null) {
            attrs.remove(key);
        } else {
            attrs.put(key, value);
        }
        return self();
    }

    /**
     * Load {@link ChannelInitializerExtension}s using the given class loader.
     * <p>
     * By default, the extensions will be loaded by the same class loader that loaded this bootstrap class.
     *
     * @param classLoader The class loader to use for loading {@link ChannelInitializerExtension}s.
     * @return This bootstrap.
     */
    public B extensionsClassLoader(ClassLoader classLoader) {
        extensionsClassLoader = classLoader;
        return self();
    }


    public B validate() {
        if (group == null) {
            throw new IllegalStateException("group not set");
        }
        if (channelFactory == null) {
            throw new IllegalStateException("channel or channelFactory not set");
        }
        return self();
    }

    /**
     * Returns a deep clone of this bootstrap which has the identical configuration.  This method is useful when making
     * multiple {@link Channel}s with similar settings.  Please note that this method does not clone the
     * {@link EventLoopGroup} deeply but shallowly, making the group a shared resource.
     */
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public abstract B clone();

    /**
     * Create a new {@link Channel} and register it with an {@link EventLoop}.
     */
    public ChannelFuture register() {
        validate();
        return initAndRegister();
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind() {
        validate();
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            throw new IllegalStateException("localAddress not set");
        }
        return doBind(localAddress);
    }


    /**
     * 绑定一个给定的端口号
     * @param inetPort
     * @return {@link ChannelFuture }
     */
    public ChannelFuture bind(int inetPort) {
        return bind(new InetSocketAddress(inetPort));
    }

    public ChannelFuture bind(String inetHost, int inetPort) {
        return bind(SocketUtils.socketAddress(inetHost, inetPort));
    }

    public ChannelFuture bind(InetAddress inetHost, int inetPort) {
        return bind(new InetSocketAddress(inetHost, inetPort));
    }


    /**
     *
     * @param localAddress
     * @return {@link ChannelFuture }
     */
    public ChannelFuture bind(SocketAddress localAddress) {
        //校验服务端的boss线程组或客户端的线程组、ChannelFactory是否舒适化
        validate();
        //执行真正绑定操作
        return doBind(ObjectUtil.checkNotNull(localAddress, "localAddress"));
    }

    /**
     * 先把 Channel 准备好并注册到线程池（EventLoop）中，等注册成功后，再去真正地绑定底层的物理端口
     * @param localAddress 本地网络地址（通常包含 IP 地址和端口号）
     * @return {@link ChannelFuture } 异步操作的结果凭证
     */
    private ChannelFuture doBind(final SocketAddress localAddress) {
        //(1)利用反射工厂ReflectiveChannelFactory创建Channel实例，并(2)初始化该Channel,(3)最后将Channel注册到Boss的EventLoopGroup中的某一个EventLoop的某个Selector上
        //服务端创建的是NioServerSocketChannel;客户端创建的是NioSocketChannel
        final ChannelFuture regFuture = initAndRegister();
        final Channel channel = regFuture.channel();
        if (regFuture.cause() != null) {
            return regFuture;
        }
        //若此时，将Channel注册到Boss的EventLoopGroup中的某一个EventLoop的某个Selector上已经完成
        if (regFuture.isDone()) {
            ChannelPromise promise = channel.newPromise();//创建新的 Promise，代表接下来的“绑定端口”操作的结果。
            //绑定操作
            doBind0(regFuture, channel, localAddress, promise);
            return promise;
        } else {
        //注册还在另外一个线程里排队进行中
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            //给注册凭证挂上一个监听器回调
            regFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    Throwable cause = future.cause();
                    if (cause != null) {
                        promise.setFailure(cause);
                    } else {
                        promise.registered();
                        //注册完了，执行绑定操作
                        doBind0(regFuture, channel, localAddress, promise);
                    }
                }
            });
            return promise;
        }
    }


    /**
     * 实例化 ：通过反射工厂，在内存中真正 new 出一个网络通道对象（比如服务端的 NioServerSocketChannel 或客户端的 NioSocketChannel）。
     * 初始化 ：将用户在引导类中配置好的 TCP 参数（Options）、自定义属性（Attributes）以及业务处理器（Handlers）全部组装到这个新通道中。
     * 注册 ：将这个准备就绪的通道，移交给 Netty 的事件循环线程池（EventLoopGroup），让其与底层的多路复用器（Selector）绑定，正式开始接管 I/O 事件。
     * @return {@link ChannelFuture } 异步操作的结果凭证
     */
    final ChannelFuture initAndRegister() {
        Channel channel = null;
        try {
            //通过反射工厂调用Channel的无参构造函数，创建对应的Channel实例（）
            //服务端：NioServerSocketChannel,（1）创建Pipeline，内部存在两个处理器head 和tail;（2）创建Unsafe对象，类型NioMessageUnsafe （3）设置Channel是非阻塞状态
            //客户端：NioSocketChannel:
            channel = channelFactory.newChannel();
            //初始化Channel（子类实现）
            //服务端：pipeline: head<--->CI（zip）<--->taile
            init(channel);
        } catch (Throwable t) {
            if (channel != null) {
                channel.unsafe().closeForcibly();
                return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
            }
            return new DefaultChannelPromise(new FailedChannel(), GlobalEventExecutor.INSTANCE).setFailure(t);
        }
        //将Channel注册到线程组的Selector上，group():
        //服务端返回的是boss线程组NioEventLoopGroup
        //客户端只有唯一的线程组
        ChannelFuture regFuture = config().group().register(channel);
        if (regFuture.cause() != null) {
            if (channel.isRegistered()) {
                channel.close();
            } else {
                channel.unsafe().closeForcibly();
            }
        }
        return regFuture;
    }

    abstract void init(Channel channel) throws Throwable;

    Collection<ChannelInitializerExtension> getInitializerExtensions() {
        ClassLoader loader = extensionsClassLoader;
        if (loader == null) {
            loader = getClass().getClassLoader();
        }
        return ChannelInitializerExtensions.getExtensions().extensions(loader);
    }

    private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) {

        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (regFuture.isSuccess()) {
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else {
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }

    /**
     * the {@link ChannelHandler} to use for serving the requests.
     */
    public B handler(ChannelHandler handler) {
        this.handler = ObjectUtil.checkNotNull(handler, "handler");
        return self();
    }

    /**
     * Returns the configured {@link EventLoopGroup} or {@code null} if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public final EventLoopGroup group() {
        return group;
    }

    /**
     * Returns the {@link AbstractBootstrapConfig} object that can be used to obtain the current config
     * of the bootstrap.
     */
    public abstract AbstractBootstrapConfig<B, C> config();

    final Map.Entry<ChannelOption<?>, Object>[] newOptionsArray() {
        return newOptionsArray(options);
    }

    static Map.Entry<ChannelOption<?>, Object>[] newOptionsArray(Map<ChannelOption<?>, Object> options) {
        synchronized (options) {
            return new LinkedHashMap<ChannelOption<?>, Object>(options).entrySet().toArray(EMPTY_OPTION_ARRAY);
        }
    }

    final Map.Entry<AttributeKey<?>, Object>[] newAttributesArray() {
        return newAttributesArray(attrs0());
    }

    static Map.Entry<AttributeKey<?>, Object>[] newAttributesArray(Map<AttributeKey<?>, Object> attributes) {
        return attributes.entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY);
    }

    final Map<ChannelOption<?>, Object> options0() {
        return options;
    }

    final Map<AttributeKey<?>, Object> attrs0() {
        return attrs;
    }

    final SocketAddress localAddress() {
        return localAddress;
    }

    @SuppressWarnings("deprecation")
    final ChannelFactory<? extends C> channelFactory() {
        return channelFactory;
    }

    final ChannelHandler handler() {
        return handler;
    }

    final Map<ChannelOption<?>, Object> options() {
        synchronized (options) {
            return copiedMap(options);
        }
    }

    final Map<AttributeKey<?>, Object> attrs() {
        return copiedMap(attrs);
    }

    static <K, V> Map<K, V> copiedMap(Map<K, V> map) {
        if (map.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<K, V>(map));
    }

    static void setAttributes(Channel channel, Map.Entry<AttributeKey<?>, Object>[] attrs) {
        for (Map.Entry<AttributeKey<?>, Object> e: attrs) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            channel.attr(key).set(e.getValue());
        }
    }

    static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options, InternalLogger logger) throws Throwable {
        for (Map.Entry<ChannelOption<?>, Object> e: options) {
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value, InternalLogger logger) throws Throwable {
        try {
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) {
                logger.warn("Unknown channel option '{}' for channel '{}' of type '{}'",
                        option, channel, channel.getClass());
            }
        } catch (Throwable t) {
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}' of type '{}'",
                    option, value, channel, channel.getClass(), t);
            if (CLOSE_ON_SET_OPTION_FAILURE) {
                // Only rethrow if we want to close the channel in case of a failure.
                throw t;
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append('(').append(config()).append(')');
        return buf.toString();
    }

    static final class PendingRegistrationPromise extends DefaultChannelPromise {

        // Is set to the correct EventExecutor once the registration was successful. Otherwise it will
        // stay null and so the GlobalEventExecutor.INSTANCE will be used for notifications.
        private volatile boolean registered;

        PendingRegistrationPromise(Channel channel) {
            super(channel);
        }

        void registered() {
            registered = true;
        }

        @Override
        protected EventExecutor executor() {
            if (registered) {
                // If the registration was a success executor is set.
                //
                // See https://github.com/netty/netty/issues/2586
                return super.executor();
            }
            // The registration failed so we can only use the GlobalEventExecutor as last resort to notify.
            return GlobalEventExecutor.INSTANCE;
        }
    }
}
