# 标准化排查预案

# <font style="color:rgb(0, 0, 0);">功能描述</font>
<font style="color:rgb(0, 0, 0);">系统持续巡检优化，通过发现、定位并解决 OOM、数据库死锁、索引失效、恶意访问请求等问题，提升系统的稳定性和健壮性，同时将处理流程沉淀为标准化排查预案，使团队整体问题排查效率提升约 80%。</font>

# <font style="color:rgb(0, 0, 0);">面试回答</font>
<font style="color:rgb(0, 0, 0);">这个功能我理解为 E账通系统中的稳定性治理和标准化故障排查体系。</font>

<font style="color:rgb(0, 0, 0);">我们系统的监控不是业务研发自己搭建的，而是由数据中心统一负责。数据中心会监控 JVM 内存、数据库锁等待、慢 SQL、接口响应时间、异常访问等指标。一旦发现问题，会通过邮件通知相关研发人员。我们研发侧重点做的是收到告警后的排查闭环，也就是发现问题之后如何快速定位、止血、修复、验证和复盘。</font>

<font style="color:rgb(0, 0, 0);">具体来说，我们把高频问题分成几类来处理。</font>

<font style="color:rgb(0, 0, 0);">第一类是 OOM，我会先确认告警邮件里的服务名、机器 IP、OOM 类型，告警时间和影响范围。</font>

<font style="color:rgb(0, 0, 0);">OOM 常见类型包括 Java heap space、Metaspace、Direct buffer memory 、GC overhead limit exceeded和 unable to create new native thread。不同类型的 OOM 排查方向不一样。</font>

<font style="color:rgb(0, 0, 0);">如果是 Java heap space，说明 Java 堆内存放不下更多对象了，我会用 MAT 分析 heap dump，重点看 Dominator Tree、Histogram 和 Path to GC Roots，定位是否存在大对象、大集合、本地缓存未释放，或者某些对象一直被引用导致 GC 回收不了。</font>

<font style="color:rgb(0, 0, 0);">如果是 GC overhead limit exceeded，GC 花了太多时间却回收不了多少堆内存，所以排查重点是堆内存为什么回收不下来。我会用 jstat -gcutil <pid> 1000 10 观察 Old 区是否长期很高、Full GC 是否频繁增加、Full GC 后 Old 区是否下降。如果 Full GC 后 Old 区仍然降不下来，通常说明堆中有大量对象仍被引用，需要结合 heap dump 继续分析引用链。</pid></font>

<font style="color:rgb(0, 0, 0);">如果是 Metaspace，我会排查是否存在大量动态生成类，比如 CGLIB、动态代理、ByteBuddy，或者频繁创建 ClassLoader 且没有释放。</font>

<font style="color:rgb(0, 0, 0);">如果是 Direct buffer memory，我会重点排查 NIO、Netty、文件上传下载、网络通信等场景，确认 ByteBuffer、Netty ByteBuf、文件流、网络连接是否及时释放。</font>

<font style="color:rgb(0, 0, 0);">如果是 unable to create new native thread，我会通过 jstack 统计线程数量和线程状态，重点看是否存在线程池最大线程数过大、每个请求都 new Thread、任务堆积等问题。</font>

<font style="color:rgb(0, 0, 0);">临时止血上，可以先切流、重启异常实例，先恢复业务可用性。但修复还是要回到代码和设计上，比如大查询改分页查询，大导出改成分批查询加流式写入，超大导出改异步任务并使用有界线程池控制并发；对账清算任务按日期、商户、渠道拆分并分批处理；本地缓存增加最大容量和过期时间；线程池使用有界队列并合理设置最大线程数；ThreadLocal 放到 finally 中 remove；文件流、网络连接和直接内存对象及时释放。</font>

<font style="color:rgb(0, 0, 0);">最后我会通过 JVM 监控、GC 日志、接口响应时间、线程数量、系统资源和业务结果验证问题是否已修复。我的理解是，OOM 排查不是简单重启，而是先根据 OOM 类型分流排查，最后结合具体业务代码做修复。</font>

<font style="color:rgb(0, 0, 0);">第二类是数据库死锁或锁等待。我们会通过 show engine innodb status 查看死锁日志，定位互相等待的事务 SQL，再分析加锁顺序、事务范围和索引使用情况。常见优化方式包括统一加锁顺序、缩小事务范围、避免事务内调用外部接口，以及增加幂等和有限重试。</font>

<font style="color:rgb(0, 0, 0);">第三类是慢 SQL 或索引失效。这里我不会一上来就直接看 EXPLAIN，而是先判断这个查询本来应该走 MySQL 还是 ES。如果是后台列表、多条件搜索、模糊查询、深分页查询，并且系统已经同步了 ES 查询副本，就应该优先排查 ES，而不是把查询压力压到 MySQL。如果确认应该走 MySQL，还要看表是否分库分表。如果分库分表，就先看 SQL 是否带分片键，是否路由到单个物理分片。确认路由正确后，再对真实 SQL 执行 EXPLAIN，重点看 type、key、rows、filtered 和 Extra，判断是否索引失效、扫描行数过大、是否出现 Using filesort 或 Using temporary。</font>

<font style="color:rgb(0, 0, 0);">这个方案的价值不只是解决单次故障，而是把每次处理过程沉淀成标准化预案。预案中会包含触发条件、影响范围、排查步骤、常用命令、临时止血、根因修复和验证方式。这样后续遇到类似问题时，新人也能按流程完成基础排查，团队整体问题定位效率会明显提升，系统稳定性也会更好。</font>

<font style="color:rgb(0, 0, 0);">exceed /ɪkˈsiːd/ 超限</font>
