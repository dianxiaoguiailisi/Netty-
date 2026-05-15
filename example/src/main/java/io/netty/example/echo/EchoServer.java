package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.example.util.ServerUtil;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;


/**
 * 服务端
 * @author 10567
 * @date 2026/05/15
 */
public final class EchoServer {

    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    public static void main(String[] args) throws Exception {

        final SslContext sslCtx = ServerUtil.buildSslContext();

        // 线程组(EventLoopGroup):这里只创建一个线程组，表明boss和Worker公用一个线程组
        EventLoopGroup group = new NioEventLoopGroup();
        //业务处理器(Handler)
        final EchoServerHandler serverHandler = new EchoServerHandler();
        try {
            //创建服务器引导类
            ServerBootstrap b = new ServerBootstrap();
            //实例化服务器引导类
            b.group(group)//配置线程组
             .channel(NioServerSocketChannel.class)//指明服务端基于NIO的NioServerSocketChannel
             .option(ChannelOption.SO_BACKLOG, 100)//配置服务端底层Socket参数,这里表明服务端接受客户端连接请求最多100
             .handler(new LoggingHandler(LogLevel.INFO))//添加一个日志处理器,针对的是服务端自身
             .childHandler(new ChannelInitializer<SocketChannel>() {//针对已经被服务端接受的客户端连接SocketChannel
                 /**
                  *每当有一个新的客户端连接成功，Netty调用该方法
                  * @param ch
                  * @throws Exception
                  */
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                     //获取当前连接的处理流水线
                     ChannelPipeline p = ch.pipeline();
                     //如果配置了SSL，则在流水线最前端加上SSL握手和加加密处理器
                     if (sslCtx != null) {
                         p.addLast(sslCtx.newHandler(ch.alloc()));
                     }
                     //将实例化的业务处理器加到流水线末尾
                     p.addLast(serverHandler);
                 }
             });
            ChannelFuture f = b.bind(PORT)//调用 bind(PORT) 真正去绑定端口启动服务器。这个操作是异步的，因此会返回一个 ChannelFuture。
                    .sync();//同步阻塞当前线程，直到绑定端口的异步操作真正完成。如果绑定失败（比如端口被占用），这里会抛出异常

            f.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();//优雅关闭所有的EventLoop
        }
    }
}
