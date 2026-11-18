# 009_Spring Boot如何接入Redisson

## 1 说明

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准：Java 8、Spring Boot 2.7.18、Redis 服务端 6.2.14、`redisson-spring-boot-starter` 3.17.7。版本基线不代表实际部署或依赖兼容性已验证。

前一篇见 [008 为什么使用Redisson实现分布式锁](<D:/000/sources/eas/000_Redis/008_为什么使用Redisson实现分布式锁.md>)。

接下来读 [010 Redisson的等待时间与租期怎么选](<D:/000/sources/eas/000_Redis/010_Redisson的等待时间与租期怎么选.md>)。

## 2 问题

Spring Boot 项目怎样接入 Redisson？

## 3 高级 Java 面试核心考点

1. Starter 与 Spring Data Redis 版本
2. 连接配置与 RedissonClient 自动配置

## 4 面试口述版本

口述内容字符数：212

在 Spring Boot 2.7.18 项目中，引入 `redisson-spring-boot-starter` 3.17.7。它默认带入与 Spring Data Redis 2.7 对应的适配模块。然后通过 `spring.redis.*` 配置 Redis 连接。满足自动配置条件且项目里没有现成的 `RedissonClient` 时，Starter 会创建客户端，业务类通过构造方法注入即可。接入后再用依赖树、启动和连接结果确认实际兼容性。[详细说明（第 28 行）](<D:/000/sources/eas/000_Redis/009_Spring Boot如何接入Redisson.md:28>)

## 5 详细方案

### 5.1 Starter 与 Spring Data Redis 版本

项目基线使用 `redisson-spring-boot-starter` 3.17.7，Spring Boot 为 2.7.18。在已有 Spring Boot 工程中添加：

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.17.7</version>
</dependency>
```

该 Starter 默认引入 `redisson-spring-data-27`，对应 Spring Data Redis 2.7 系列。按项目规则使用 Boot 的依赖管理，不主动覆盖 Netty、Jackson 版本，也不另行重复声明 Redisson 核心包或手工创建客户端。实际依赖冲突仍须用项目最终依赖树和启动结果核验。

下面只是单节点连接配置的结构示例，不代表项目拓扑。通过环境变量填入实际地址和端口；认证、TLS、哨兵或集群配置必须按实际部署另行确认，不能用这段示例代替。

```yaml
spring:
  redis:
    host: ${REDIS_HOST}
    port: ${REDIS_PORT}
```

`REDIS_HOST` 是服务地址；`REDIS_PORT` 是实际监听端口的整数值。项目值尚未确认，因此不填写猜测值。Starter 根据配置提供 `RedissonClient`，业务类通过构造方法注入。[011 Java中如何使用Redisson实现分布式锁](<D:/000/sources/eas/000_Redis/011_Java中如何使用Redisson实现分布式锁.md>) 中的 `LockedExecutor` 用 `@Component` 声明为组件，并需放在 Spring Boot 的组件扫描范围内，才能由 Spring 创建对象并完成构造方法注入。

依据：[Starter 3.17.7 官方 POM](https://repo.maven.apache.org/maven2/org/redisson/redisson-spring-boot-starter/3.17.7/redisson-spring-boot-starter-3.17.7.pom)、[RedissonAutoConfiguration 3.17.7](https://github.com/redisson/redisson/blob/redisson-3.17.7/redisson-spring-boot-starter/src/main/java/org/redisson/spring/starter/RedissonAutoConfiguration.java)。自动配置使用 `spring.redis.*`，并在没有现成 `RedissonClient` Bean 时创建客户端。

### 5.2 项目版本基线与实现状态

本项目 `AGENTS.md` 将 Redisson 3.17.7 列为批次启动锁的客户端基线，但关联的 [015 批次启动实现方案（原 013）](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/015_不停机数据迁移_在线历史迁移_批次状态控制_启动互斥与初始化事务_实现方案.md>) 仍记载“客户端及版本尚未确认”，并选用 15 分钟固定过期时间。两处客户端状态记载不一致；本组文档按当前 `AGENTS.md` 说明版本，不将看门狗示例写成批次启动的已采用实现，也不改变该方案的固定过期时间。关联文档不在本次修改范围内。

## 6 已确认规则

1. 按理由、结论、实现的依赖顺序编排，保留理解当前回答所需的前提和边界。
2. 面试口述尽量控制在 300 字符以内，详细示例、源码与待验证事项随主题迁移。

## 7 待确认或待验证事项

1. **待确认：** 项目实际 Redis 服务端版本和部署拓扑；文档版本基线不代表实际部署。
2. **待确认：** 项目的 Redis 连接配置。
3. **待验证：** 项目最终依赖树及应用启动兼容性；源码核验不能代替项目验证。
4. **待确认：** 015 方案（原 013）与 `AGENTS.md` 对客户端状态的记载不一致，需由该方案维护时对齐客户端及实际实现；不能从本文示例推断项目已切换续期策略。
5. **待验证：** Codex 阅读器中的实际跳转效果。

复核完成时间：2026-11-18 20:43。已执行 [$java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。
