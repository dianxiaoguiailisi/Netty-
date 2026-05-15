package io.netty.util.concurrent;

/**
 * 标记接口:
 * 当一个类实现了 OrderedEventExecutor 接口时，它就在向外界郑重承诺：
 * “只要你把任务交给我，我保证严格按照你提交的顺序（FIFO，先进先出）串行执行这些任务！”
 * @author 10567
 * @date 2026/05/09
 */
public interface OrderedEventExecutor extends EventExecutor {
}
