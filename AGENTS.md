# 对话执行与完成规则

## 1 独立判断与正确性核验

每次对话开始时，执行以下独立判断与正确性核验要求：

1. 不要一味迎合我的想法。
2. 正式作答前先核查问题，若存在错误前提、逻辑跳跃、信息缺失，不要顺着错误思路执行，保持独立判0断。
3. 严格区分客观事实、合理推测与个人主观观点；涉及数字、关键结论时尽可能核验信息来源。
4. 当问题涉及方案策略、潜在风险时，主动提醒我遗漏的变量、潜在成本与认知偏差。
5. 如果你不认同我的观点，请直接指出，附带对应的依据。

## 2 AI自检

每次给出答案或完成修改后，必须调用 [self-check](C:/Users/Administrator/.codex/skills/self-check/SKILL.md)，按照该技能完成最终收口检查。

## 3 回复

每次回复都应说明修改或输出内容、已完成的核对、未验证或待确认事项。

## 4 已确认规则

每次回复末尾都应列出“已确认规则”：记录具体的要求；不使用笼统总结，不擅自扩展。没有明确确认要求时，写：“本次修改过程中暂无明确提出的修改要求。”

## 5 文档格式规则

Markdown 文档的一级标题必须与文档名称保持一致；比较时忽略文件名的 `.md` 扩展名。

新建文档时，如果编号与现有文档冲突，应将原占用该编号的文档及后续文档按原有顺序顺延，直到编号连续且不重复；同步更新文件名、一级标题和关联引用，不覆盖或删除原文档内容。`999` 编号文档不参与顺延。

## 6 文件修改与远程提交

每次修改 `D:/000/sources/eas` 或 `C:/Users/Administrator/.codex/skills` 下的文件，完成必要验证后，都要在变更文件所属的 Git 仓库提交本次修改。推送前，核对该仓库的远程地址和当前分支状态，再将提交推送到对应的远程仓库。

## 7 面试材料文档复核

每次修改面试材料文档后，在提交前，对本次修改的每篇面试材料文档执行一遍 `java-interview-doc-workflow` 的[单篇面试文档复核步骤](C:/Users/Administrator/.codex/skills/java-interview-doc-workflow/references/review-checks.md)。

# 技术栈与版本

发行日期按下列官方发布记录的日期记载，不按项目接入时间或网页最近更新时间计算。Java 8 使用首次正式发布（GA）日期，不代表某个 8u 更新版本；GitHub 来源使用 Release 发布时间，不使用后续编辑或评论时间。

| 技术栈                                         | 项目版本    | 发行日期       | 官方来源与日期口径                                                                                                                        |
| ------------------------------------------- | ------- | ---------- | -------------------------------------------------------------------------------------------------------------------------------- |
| Java                                        | 8       | 2014-03-18 | [OpenJDK JDK 8](https://openjdk.org/projects/jdk8/)，首次 GA                                                                        |
| Spring Boot                                 | 2.7.18  | 2023-11-23 | [Spring 官方发布公告](https://spring.io/blog/2023/11/23/spring-boot-2-7-18-available-now)                                              |
| Redis 服务端                                   | 6.2.14  | 2023-10-18 | [官方 GitHub Release](https://github.com/redis/redis/releases/tag/6.2.14)，UTC 发布日期                                                 |
| MySQL                                       | 8.0.33  | 2023-04-18 | [MySQL 官方版本说明](https://docs.oracle.com/cd/E17952_01/mysql-8.0-relnotes-en/news-8-0-33.html)，版本标题所列日期                             |
| Elasticsearch 服务端                           | 7.17.15 | 2023-11-13 | [官方 GitHub Release](https://github.com/elastic/elasticsearch/releases/tag/v7.17.15)，UTC 发布日期                                     |
| `rocketmq-spring-boot-starter`              | 2.3.0   | 2024-02-19 | [RocketMQ Spring 2.3.0 Release](https://github.com/apache/rocketmq-spring/releases/tag/rocketmq-spring-all-2.3.0)，所属项目的 UTC 发布日期 |
| `org.apache.rocketmq:rocketmq-client`       | 5.2.0   | 2024-02-11 | [RocketMQ 5.2.0 Release](https://github.com/apache/rocketmq/releases/tag/rocketmq-all-5.2.0)，所属项目的 UTC 发布日期，不等同于 Maven 制品上传时间    |
| Alibaba Canal Server                        | 1.1.7   | 2023-10-09 | [官方 GitHub Release](https://github.com/alibaba/canal/releases/tag/canal-1.1.7)，UTC 发布日期                                          |
| `org.redisson:redisson-spring-boot-starter` | 3.17.7  | 2022-10-03 | [官方 GitHub Release](https://github.com/redisson/redisson/releases/tag/redisson-3.17.7)，UTC 发布日期                                  |

- Alibaba Canal Server 单机部署，不使用 ZooKeeper 高可用。
- Redis 服务端以 6.2.14 作为项目文档版本基线；该版本独立于 Lettuce、Redisson 等客户端版本，不能由 Spring Boot 或客户端版本号推导。版本选型不代表已核实实际部署版本，也不代表已完成客户端兼容性验证；实际部署、连接及加解锁行为仍待验证。
- Redisson 使用 `redisson-spring-boot-starter` 3.17.7 实现批次启动锁。该版本支持 Java 8，Starter 的 Spring Boot 基线为 2.7.2，默认引入 `redisson-spring-data-27`，与当前 Spring Boot 2.7.18 所属的 Spring Data Redis 2.7 系列对应，无需替换适配模块。通过 `spring.redis.*` 配置连接并使用自动配置的 `RedissonClient`，无需另行声明 Redisson 核心包依赖或手工创建客户端。选择依据：[Starter 3.17.7 官方 POM](https://repo.maven.apache.org/maven2/org/redisson/redisson-spring-boot-starter/3.17.7/redisson-spring-boot-starter-3.17.7.pom)、[父 POM](https://repo.maven.apache.org/maven2/org/redisson/redisson-parent/3.17.7/redisson-parent-3.17.7.pom)。
- Redisson 版本选型不代表实际接入已经验证。先沿用 Spring Boot 2.7.18 的依赖管理，不主动覆盖 Netty、Jackson 版本；核对最终依赖树并完成项目启动、Redis 连接及加解锁验证后，再确认实际兼容性。上述项目验证尚未执行，不能承诺零依赖冲突。
- 涉及以上技术时，按本节版本为准，并说明：“以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。”
- 涉及未列出的技术时，先确认使用版本；不得擅自修改已确认的版本。
