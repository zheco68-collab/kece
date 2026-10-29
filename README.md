![KECE 平台横幅](./docs/images/Snipaste_2026-10-14_14-34-59.png)

# KECE（课测）— 多维度全真编程与高并发技能评测系统

[![Frontend](https://img.shields.io/badge/Frontend-Vue%203.5%20%7C%20TypeScript%20%7C%20Vite%208-42b883?style=flat-square)](https://vuejs.org/)
[![Backend](https://img.shields.io/badge/Backend-Spring%20Boot%203.2%20%7C%20Java%2017-6db33f?style=flat-square)](https://spring.io/projects/spring-boot)
[![Judger](https://img.shields.io/badge/Judger-Go%201.22+%20%7C%20Docker%20Cgroups%20v2-00add8?style=flat-square)](https://go.dev/)
[![Database](https://img.shields.io/badge/Database-MySQL%208.0%20(Flyway%20V20)-00758f?style=flat-square)](https://www.mysql.com/)
[![Cache & MQ](https://img.shields.io/badge/Cache%20%26%20MQ-Redis%207.0%20%7C%20BRPOP%20Listener-dc382d?style=flat-square)](https://redis.io/)
[![Design System](https://img.shields.io/badge/UI%20System-0--Radius%20Industrial-111111?style=flat-square)](https://kece.zheco68.asia/)

> **KECE（课测）** 是面向高校计算机实验教学、全真技术机考与高阶算法/SQL实战的一体化现代评测系统。  
> 平台深度融合 **算法编程 OJ**（含多语言与鸿蒙 ArkTS）、**数据库 SQL OJ**（独立临时库对拍）、**精选进阶题集**、**前端 AI 评测**、**客观理论题库**、**随堂课练实训** 与 **ACM/OI 双赛制机考系统**，并依托 Linux Cgroups v2 强隔离沙箱与 Redis 异步流转引擎，提供毫秒级反馈、高反差直角工业美学与确定性的全真工程体验。

- **官方在线体验站点**：[https://kece.zheco68.asia/](https://kece.zheco68.asia/)
- **生产 API 规范基址**：`/api/v1`（集成 OpenAPI / Swagger 接口文档）

---

## 目录

- [平台核心评测与教学模块](#平台核心评测与教学模块)
- [核心架构亮点与高性能工程实践](#核心架构亮点与高性能工程实践)
- [设计系统与交互哲学](#设计系统与交互哲学)
- [界面全景展示](#界面全景展示)
- [技术栈与系统架构拓扑](#技术栈与系统架构拓扑)
- [项目目录结构](#项目目录结构)
- [本地开发与部署指南](#本地开发与部署指南)
- [关联项目规范与文档](#关联项目规范与文档)

---

## 平台核心评测与教学模块

```mermaid
flowchart LR
    Core["KECE 核心评测底座"] --> M1["01. 算法编程 OJ<br/>(C++ / Java / Python / ArkTS)"]
    Core --> M2["02. 数据库 SQL OJ<br/>(3307 独立临时库对拍)"]
    Core --> M3["03. 精选分类题集<br/>(章节编排 / 断点续刷 / 自愈校验)"]
    Core --> M4["04. 前端 AI 智能评测<br/>(Playwright 断言 + 视觉大模型)"]
    Core --> M5["05. 标准客观题库<br/>(LaTeX 公式 / 漏选扣分 / 正则匹配)"]
    Core --> M6["06. 随堂课练实训<br/>(口令准入 / 实时督学 / 历史复盘)"]
    Core --> M7["07. 在线机考与天梯<br/>(ACM 罚时 / OI 快照加权 / 内存榜单)"]
```

| 编号 | 模块标识 | 模块名称 | 核心技术底座 | 关键功能与业务机制 |
| :---: | :---: | :--- | :--- | :--- |
| **01** | `ALGO` | **算法编程 OJ** | `Cgroups v2` + `Docker` | • 支持 **C/C++**、**Java 17**、**Python 3** 与 **ArkTS (鸿蒙)** 多语言在线编译；<br>• 内置多级算法标签体系（`#动态规划`、`#基础语法`、`#最短路`、`#BFS` 等）与折叠筛选条；<br>• 毫秒级 CPU 时间片与内存峰值硬限制，全覆盖 `AC/WA/TLE/MLE/RE/CE` 状态机。 |
| **02** | `SQL` | **数据库 SQL OJ** | `MySQL 8.0` 独立隔离实例 | • 单次评测动态创建 `judge_{subId}_{caseId}` 独占临时库，对拍后即刻 `DROP` 销毁；<br>• 内置交互式表结构预览（`DbSchemaViewer`），支持 `SELECT` 结果集对拍与 `DML` 变更校验；<br>• 支持浮点精度容差、字段类型宽容度与有序/无序比对。 |
| **03** | `SET` | **进阶分类题集** | 预聚合表 + 读时自愈 (`Read-Repair`) | • 结构化章节树编排、题集分类筛选与通关进度环；<br>• 智能推荐断点续刷题目，跨模式自动比对全局提交历史并点亮通关状态。 |
| **04** | `WEB AI` | **前端 AI 智能评测** | `Playwright` + 多模态大模型 | • 基于无头浏览器页面渲染快照、DOM 节点计算样式与交互行为断言；<br>• 结合多模态大模型审计前端代码还原度与语义规范性。 |
| **05** | `QUIZ` | **标准客观题库** | `Markdown` + `KaTeX` | • 涵盖单选、多选题（**漏选得一半分、错选得零分**算法）与填空题（精确/大小写忽略/正则）；<br>• 原生支持 Markdown 代码块高亮与 LaTeX 公式渲染，交卷即刻出分并呈现详细解析。 |
| **06** | `LAB` | **随堂课练实训** | 混合题型编排 + 实时督学 | • 面向高校课堂教学，支持算法题、SQL题与客观题混排编排；<br>• 6 位安全准入口令锁定（`[ 🔒 需口令 ]`），教师端提供实时答题进度雷达看板与历史归档。 |
| **07** | `EXAM` | **在线机考系统** | `ACM / OI` 双赛制 + 防作弊快照 | • 支持 **ACM 罚时赛制**（实时出分 + 20分钟错误罚时）与 **OI 考后结算赛制**（考中存快照）；<br>• 开考即固化试卷快照，集成切屏离焦监测、交卷永久锁定、战报分析与全场天梯榜。 |

---

## 核心架构亮点与高性能工程实践

为保证在高校机考与选拔赛等集中洪峰场景下的极速响应与高吞吐稳定性，系统实施了以下全链路性能加固：

### 1. 评测流转零轮询（BRPOP 异步驱动）
- **改造前**：后台采用 `@Scheduled(fixedDelay = 2000)` 定时轮询，导致判题机 20ms 完成的评测结果必须平均白等 1~2 秒才落库。
- **改造后**：自研 [`JudgeResultBlockingListener`](./backend/src/main/java/com/kece/submission/listener/JudgeResultBlockingListener.java)，采用双独立守护线程长轮询 `BRPOP` 监听 Redis 结果队列。判题机完成推入的 **0.1ms 内被即刻唤醒**，端到端延迟从 **2200ms 骤降至 <80ms**。

### 2. 数据库覆盖索引（Covering Index）
- 在 Flyway `V20__performance_indexes.sql` 中为 `submission` 与 `db_submission` 补全核心复合覆盖索引：
  ```sql
  ALTER TABLE `submission` ADD INDEX `idx_user_status_qid` (`user_id`, `status`, `question_id`);
  ```
- 答题状态核对转化为 **Using index** 纯内存扫描，彻底消除主表回表磁盘 IO。

### 3. 多级缓存体系与精准失效
- **题库列表缓存**：对公共查询结果生成 `question:cache:list:*`，结合 Redis Set (`question:cache:list_keys`) 实现出题、改题、删题时的 **O(1) 批量精准失效**，零 `KEYS *` 阻塞风险，列表查询响应由 70ms 压至 15ms。
- **全域排行榜防击穿**：`RankService` 引入带 0~30s 随机 Jitter 的页面级防雪崩缓存，并单点补齐登录用户实时排名卡片，避免全表 CTE 窗口函数频繁计算击穿 MySQL CPU。

### 4. 传输压缩与连接池调优
- **全站 Gzip 压缩**：Spring Boot 与 Nginx 反代开启动态压缩，长 JSON、Markdown 和测试用例传输体积直降 **70%~75%**。
- **JDBC 预编译缓存**：开启 `cachePrepStmts=true`、`rewriteBatchedStatements=true`，结合 HikariCP 连接池生命周期回收，杜绝长连接陈旧断链。

---

## 设计系统与交互哲学

平台完全遵循 **0-Radius Industrial System（直角工业设计系统）**，摒弃当下泛滥的圆角与高斯模糊，回归直观、硬朗、精确的工程美学：

- **核心几何规范**：全站严格遵循 `--radius: 0` 纯直角定义，消除任何圆角与模糊阴影；
- **色彩层级体系**：`#111111` 工业主墨黑（主文字与主框线）/ `#f4f5f7` 工作区浅灰底色 / `#ced4da` 结构分割线 / `#1d4ed8` 科技蓝高反差语义强调；
- **数码排版字体**：优先采用 `JetBrains Mono` 等宽排盘，辅以 `Inter` 与操作系统原生界面字族。

1. **绝对直角规范**：全站所有卡片、按钮、输入框、模态窗与头像均严格执行 `--radius: 0`。
2. **纯色语义标识体系**：
   - **用户角色**：管理员纯紫（`#7048e8`）、出题教师纯绿（`#2b8a3e`）、答题学生纯黑（`#1e1e1e`）；
   - **题目难度**：简单实心绿（`#2b8a3e`）、中等实心橙（`#e67700`）、困难实心红（`#c92a2a`）；
   - **算法标签**：高反差科技蓝纯色实心标（`#1d4ed8`，文字纯白），默认折叠收起，点击任意标签自动展开筛选条；
   - **SQL 题型**：`SELECT` 查询纯黑（`#1e1e1e`）、`DML` 变更钛金灰（`#495057`）。
3. **官网三大全宽对齐交互模块 (`1072px` 栅格)**：
   - **遥测控制台 (`ClusterConsole`)**：支持 3D 俯仰微倾与镜面高光，直连 Go 判题机心跳注册表，实时显示集群并发槽位与队列负载；
   - **扑克扇形能力矩阵 (`Poker Fan Matrix`)**：`1072px` 5 槽位扇形叠放舞台，从左后方到右前方由灰到白逐级递进，点击流畅切牌直达六大系统；
   - **实时沙箱体验终端 (`Judge Runner #01`)**：基于 `IntersectionObserver` 惰性加载，仅当用户翻阅至视野内触发打字动效，配有逐字打字音律节奏、PrismJS 实时语法高亮、实时行号与判题通过指标弹出（`ACCEPTED (AC)`）。

---

## 界面全景展示

### 1. 官网核心看板与交互矩阵
| 沙箱集群实时遥测控制台 (`ClusterConsole`) | 全栈能力扑克扇形矩阵 (`Poker Fan`) |
| :---: | :---: |
| ![沙箱集群实时遥测控制台](./docs/images/Snipaste_2026-10-26_23-16-30.png) | ![扑克扇形能力评测矩阵](./docs/images/Snipaste_2026-10-26_23-17-38.png) |

| 真实沙箱体验终端 (实时打字机高亮) |
| :---: |
| ![真实评测闭环体验终端](./docs/images/Snipaste_2026-10-26_23-19-36.png) |

---

### 2. 考试系统：锁定战报与作答快照
| 考试归档战报与 Bento 四大指标方块 | 逐题 64×64px 评测状态方块与代码快照抽屉 |
| :---: | :---: |
| ![考试归档战报](./docs/images/Snipaste_2026-10-14_14-13-57.png) | ![逐题评测状态方块与作答快照](./docs/images/Snipaste_2026-10-14_14-15-41.png) |

---

### 3. 在线编程工作台与开发者主页
| 算法编程 Monaco 工作台与测试点诊断 | 数据库 SQL 独立临时库对拍工作台 |
| :---: | :---: |
| ![算法编程提交工作台](./docs/images/Snipaste_2026-10-14_14-18-17.png) | ![数据库 SQL 作答工作台](./docs/images/Snipaste_2026-10-14_00-20-27.png) |

| 客观题作答与解析工作台 | 开发者主页与 365 天代码打卡热力图 |
| :---: | :---: |
| ![客观题作答工作台](./docs/images/Snipaste_2026-10-14_14-21-07.png) | ![开发者个人主页与热力图](./docs/images/Snipaste_2026-10-14_14-23-01.png) |

---

## 技术栈与系统架构拓扑

### 核心技术清单

| 分层领域 | 技术选型 | 版本/规范 | 关键职责 |
| :--- | :--- | :--- | :--- |
| **前端应用** | Vue 3 + TypeScript + Vite | `Vue 3.5`, `Vite 8`, `TS 5.9` | `<script setup>` 组合式架构、0-Radius 样式体系与路由鉴权 |
| **编辑器与高亮** | Monaco Editor + PrismJS + KaTeX | 最新稳定版 | 工业级在线代码编写、终端动态高亮与 LaTeX 渲染 |
| **状态与通信** | Pinia + Vue Router + Axios | `Pinia 3`, `Router 5` | JWT 自动静默续签、考场锁定态与全局状态同步 |
| **后端核心** | Spring Boot + Java 17 | `Spring Boot 3.2.5` | 领域驱动设计、JWT RBAC、评测任务编排与结果回调处理 |
| **持久化与迁移** | MyBatis + Flyway + MySQL 8.0 | `Flyway V20` | 业务数据持久化、自动版本化数据库 Schema 迁移 |
| **缓存与消息中继** | Redis 7.0 (Spring Data Redis) | `Redis 7.0` | `judge:queue` 任务解耦、`BRPOP` 阻塞收集与多级缓存 |
| **沙箱判题引擎** | Go + Docker Cgroups v2 | `Go 1.22+` | 多协程并发认领、容器资源硬限制、信号捕获与优雅退出 |
| **SQL 隔离环境** | 独立 MySQL 8.0 实例 | 端口 `:3307` | 单次评测动态创建 `judge_{subId}_{point}` 临时库，对拍后即焚 |

### 异步评测流转系统架构与拓扑详解

KECE 采用**全异步解耦、强物理隔离、确定性对拍**的分布式高可用架构。从答题者在 Monaco Editor 中点击提交代码或 SQL，到最终在控制台查阅评测报告，系统通过**五层架构拓扑**、**15 步端到端异步时序**与**严谨状态机流转**保证高吞吐与毫秒级反馈：

#### 1. 分布式系统五层协同拓扑 (System Topology Map)

![分布式系统五层协同拓扑全景图](./docs/images/architecture_topology.png)

<details>
<summary>▶ 点击展开查看五层拓扑 Mermaid 源码结构</summary>

```mermaid
flowchart TD
    %% ==========================================
    %% 1. 客户端交互与工作台层
    %% ==========================================
    subgraph Tier_Client ["【1. 客户端交互与工作台层】Web Application Tier (Vue 3.5 + TypeScript 5.9)"]
        direction LR
        UI_Editor["💻 Monaco Editor 代码工作台<br/>• 多语言在线编辑 (C++ / Java / Python / ArkTS)<br/>• 快捷键提交与本地草稿记忆"]
        UI_SqlConsole["🗄️ SQL 数据对拍工作台<br/>• 交互式表结构树 (DbSchemaViewer)<br/>• DDL 建表预览与结果集高亮"]
        UI_Diag["📊 实时用例诊断与抽屉<br/>• 按测试点呈现耗时、内存与退出码<br/>• 期望输出 vs 实际输出智能 Diff 对拍"]
        UI_Console["📡 ClusterConsole 集群遥测台<br/>• 3D 微倾光泽感悬浮看板<br/>• 在线节点心跳探针与队列负载实时透传"]
    end

    %% ==========================================
    %% 2. 接入网关与业务服务核心层
    %% ==========================================
    subgraph Tier_Backend ["【2. 业务调度与后端服务层】Backend Services (Spring Boot 3.2.5 on Java 17)"]
        direction TB
        subgraph Gateway ["接入网关与安全控制区"]
            Nginx["Nginx 高性能反向代理 (:443 / :80)<br/>• HTTP/1.1 Keepalive 长连接复用池<br/>• Gzip 动态响应压缩 (传输体积缩减 75%)<br/>• 静态资产 30 天强缓存与 ETag 协商"]
            AuthGuard["JWT 鉴权拦截器 & 权限切面 (AuthGuard)<br/>• 角色 RBAC 矩阵 (ADMIN / TEACHER / STUDENT)<br/>• 考场防切屏锁定校验与防作弊快照锚定"]
        end

        subgraph CoreServices ["业务领域服务核心编排"]
            SubService["代码提交调度服务 (SubmissionService)<br/>• 语言与题目支持状态校验<br/>• 代码规范化与脱敏清洗 (normalizeCode)<br/>• 任务打包与测试用例轻量装配"]
            DbSubService["SQL 提交调度服务 (DbSubmissionService)<br/>• 数据库方言检查与 DML 分流<br/>• 初始 PENDING 状态持久化"]
            ResultListener["⚡ 评测结果实时阻塞监听器 (JudgeResultBlockingListener)<br/>• 专用守护线程池常驻监听 (Daemon Threads)<br/>• BRPOP 毫秒级阻塞弹出唤醒 (零定时轮询)<br/>• 终态持久化 & 触发打卡与题集点亮自愈"]
            TelemetryService["集群遥测嗅探服务 (SystemStatusService)<br/>• 节点心跳探测与过期实例惰性清理<br/>• 动态槽位并发度与集群压力评估"]
            MultiCache["多级缓存与防击穿防雪崩管理器<br/>• 题库列表 O(1) 批量失效 (Set 键集合追踪)<br/>• 排行榜 120s TTL + 0~30s 随机 Jitter 缓存"]
        end

        Nginx --> AuthGuard
        AuthGuard --> SubService
        AuthGuard --> DbSubService
    end

    %% ==========================================
    %% 3. 分布式缓存与异步消息中间件
    %% ==========================================
    subgraph Tier_Middleware ["【3. 异步消息与分布式状态总线】Redis 7.0 High-Availability Hub"]
        direction TB
        subgraph TaskQueues ["任务调度与可靠传输队列区"]
            Q_Algo["待判算法任务队列<br/>[List] judge:queue"]
            Q_AlgoProc["算法处理中租约列表<br/>[List] judge:processing<br/>(BLMOVE 原子转移，防 Worker 宕机丢任务)"]
            Q_Sql["待判 SQL 任务队列<br/>[List] judge:sql:queue"]
            Q_SqlProc["SQL 处理中租约列表<br/>[List] judge:sql:processing"]
        end

        subgraph ResultChans ["终态回传与实时状态分发区"]
            Res_Algo["算法评测结果就绪队列<br/>[List] judge:results (存放完成的 subId)"]
            Res_Sql["SQL 评测结果就绪队列<br/>[List] judge:sql:results"]
            Cache_Status["提交实时状态与测试点诊断报告<br/>[String/JSON] sub:status:{id} (TTL 1h)"]
            Cache_Owner["提交归属校验防越权键<br/>[String] sub:owner:{id} (TTL 1h)"]
        end

        subgraph RegistryArea ["集群高可用拓扑与心跳注册表"]
            Reg_Workers["活跃评测机节点集合<br/>[Set] judge:workers"]
            Reg_Heartbeat["工作机心跳生命周期键<br/>[String] judge:heartbeat:{workerId} (TTL 10s)"]
        end
    end

    %% ==========================================
    %% 4. 沙箱评测引擎集群
    %% ==========================================
    subgraph Tier_Engine ["【4. 强隔离沙箱评测引擎集群】Go Judger Cluster (Go 1.22+ on Linux Cgroups v2)"]
        direction TB
        subgraph JudgerWorker ["Go 评测守护进程核心 (judger daemon)"]
            Dispatcher["协程池调度器 (Worker Goroutines)<br/>• BLMove 原子认领与执行租约持有<br/>• SIGINT / SIGTERM 信号优雅退出与任务退回"]
            HeartbeatReporter["心跳保活协程 (Heartbeat Reporter)<br/>• 每 3 秒上报一次状态与当前并发数"]
        end

        subgraph AlgoSandbox ["算法沙箱容器隔离区 (Docker + Linux Kernel Cgroups v2)"]
            Sandbox_Init["临时编译隔离目录<br/>/tmp/judge_{subId} (内存盘临时挂载)"]
            Sandbox_Limits["Linux Cgroups v2 资源硬限制:<br/>• cpu.max=100000 100000 (1.0 Core 硬核限额)<br/>• memory.max=Limit_MB (超限即刻 OOM 熔断)<br/>• pids.max=64 (彻底封堵 Fork 炸弹隐患)<br/>• --network none (绝对网络物理断开)<br/>• read-only rootfs (只读根镜像，禁止篡改系统)"]
            Sandbox_Compare["逐点对拍比对器 (compare.go)<br/>• 行尾空白截断 & 空行自适应过滤<br/>• 浮点精度相对/绝对容差比对 (1e-6)<br/>• 纳秒级高精度计时 & 物理内存峰值采样"]
        end

        subgraph SqlSandbox ["SQL 数据库对拍沙箱区 (独立专用评测实例)"]
            MySQL_JudgeInstance[("MySQL 8.0 独立评测实例 :3307<br/>(网络隔离运行，严禁访问业务主库)")]
            Sql_Lifecycle["临时 Schema 完整生命周期编排 (sqljudge.go):<br/>1. 动态建库: CREATE DATABASE judge_{subId}_{caseId};<br/>2. 初始化出题人种子数据 (seedSql)<br/>3. 执行答题者受限查询 (userSql)<br/>4. 执行标准参考语句与 DML 校验 (verifySql)<br/>5. 结果集列名宽容度与有序/无序智能比对<br/>6. 即焚销毁: DROP DATABASE (环境零污染)"]
        end

        Dispatcher --> Sandbox_Init
        Sandbox_Init --> Sandbox_Limits
        Sandbox_Limits --> Sandbox_Compare
        Dispatcher --> MySQL_JudgeInstance
        MySQL_JudgeInstance --> Sql_Lifecycle
    end

    %% ==========================================
    %% 5. 数据持久化存储底座
    %% ==========================================
    subgraph Tier_Storage ["【5. 持久化数据存储层】Enterprise Storage Tier (MySQL 8.0 InnoDB)"]
        DB_Main[("MySQL 8.0 业务主库 :3306 (Flyway V20 版本化管控)<br/>-----------------------------------------<br/>• user (用户档案 / 角色权限 / 365天 AC 统计)<br/>• question / db_question (题库定义 / 时空限制 / 算法标签)<br/>• test_case (测试用例轻量元数据 / 大文件外置磁盘存储)<br/>• submission / db_submission (提交快照 / 最终评测状态机)<br/>-----------------------------------------<br/>🚀 关键覆盖索引: idx_user_status_qid (user_id, status, question_id)<br/>实现题库作答状态判定 Using index 纯内存覆盖扫描，0 次回表磁盘 IO")]
    end

    %% ==========================================
    %% 全链路数据流向连接与步进标记
    %% ==========================================
    %% 1. 提交主链路
    UI_Editor -->|"1a. POST 提交算法代码"| Nginx
    UI_SqlConsole -->|"1b. POST 提交 SQL 语句"| Nginx
    SubService -->|"2a. 持久化 PENDING 记录"| DB_Main
    DbSubService -->|"2b. 持久化 PENDING 记录"| DB_Main
    SubService -->|"3a. 初始化状态缓存为 PENDING"| Cache_Status
    DbSubService -->|"3b. 初始化状态缓存为 PENDING"| Cache_Status
    SubService -->|"4a. RPUSH 压入任务"| Q_Algo
    DbSubService -->|"4b. RPUSH 压入任务"| Q_Sql

    %% 2. 调度与沙箱执行链路
    Q_Algo -->|"5a. BLMOVE 原子认领任务"| Dispatcher
    Q_Sql -->|"5b. BLMOVE 原子认领任务"| Dispatcher
    Dispatcher -->|"6. 持有执行租约防止丢失"| Q_AlgoProc
    Dispatcher -.->|"7. 实时推入编译与测试点进度"| Cache_Status
    Sandbox_Compare -->|"8a. 算法评测完毕推入结果"| Res_Algo
    Sql_Lifecycle -->|"8b. SQL 对拍完毕推入结果"| Res_Sql

    %% 3. 异步监听与结果落库
    Res_Algo -->|"9a. BRPOP 毫秒级唤醒"| ResultListener
    Res_Sql -->|"9b. BRPOP 毫秒级唤醒"| ResultListener
    ResultListener -->|"10. 异步写入终态 ACCEPTED / 分值 / 耗时 / 内存"| DB_Main
    ResultListener -->|"11. 触发打卡累加与题集通关自愈"| MultiCache

    %% 4. 前端状态反馈与遥测闭环
    UI_Diag -.->|"12. 极速轮询直读缓存 15~60ms"| Cache_Status
    HeartbeatReporter -.->|"定期上报节点心跳 TTL 10s"| Reg_Heartbeat
    HeartbeatReporter -.->|"注册节点实例"| Reg_Workers
    TelemetryService -.->|"O(1) 嗅探在线工作机与负载"| Reg_Workers
    UI_Console -.->|"GET /api/v1/system/judgers 遥测透传"| TelemetryService
```

</details>

#### 2. 端到端全异步评测执行时序 (End-to-End Sequence Diagram)

![端到端全异步评测执行时序与数据流转图](./docs/images/async_evaluation_sequence.png)

<details>
<summary>▶ 点击展开查看异步时序 Mermaid 源码结构</summary>

```mermaid
sequenceDiagram
    autonumber
    actor User as 开发者
    participant Web as 前端工作台 (Vue 3)
    participant Server as 业务服务 (Spring Boot)
    participant Redis as 消息与状态 (Redis 7)
    participant Judger as 判题沙箱 (Go Cluster)
    participant DB as 业务主库 (MySQL 8)

    %% 阶段一：提交接入与任务打包
    rect rgb(248, 249, 250)
        Note over User,DB: 【阶段一】提交接入与任务打包
        User->>Web: 点击提交代码 / SQL
        Web->>Server: POST /api/v1/submissions
        Server->>DB: 1. 写入初始 PENDING 提交记录
        Server->>Redis: 2. 初始化 sub:status:{id} 为 PENDING
        Server->>Redis: 3. RPUSH 压入待判任务队列 judge:queue
        Server-->>Web: 返回 200 OK & submissionId
        Web-->>User: Monaco 编辑器锁定，开启加载动效
    end

    %% 阶段二：协程认领与 Cgroups 沙箱评测
    rect rgb(240, 249, 255)
        Note over Redis,Judger: 【阶段二】协程认领与 Cgroups 沙箱评测
        Judger->>Redis: 4. BLMOVE 原子认领任务至 judge:processing
        Redis-->>Judger: 弹出任务 Payload (代码、限制、测试点)
        Judger->>Redis: 5. 更新 sub:status:{id} 为 COMPILING / RUNNING
        Judger->>Judger: 6. 容器隔离编译 + Linux Cgroups v2 硬限制执行
        Judger->>Judger: 7. 逐点输出对拍与浮点容差比对 (compare.go)
        Judger->>Redis: 8. 写入完整测试点诊断报告至 sub:status:{id}
        Judger->>Redis: 9. RPUSH judge:results 结果就绪队列
        Judger->>Redis: 10. LREM 移出 judge:processing 释放处理租约
    end

    %% 阶段三：BRPOP 结果收集与落库自愈
    rect rgb(245, 245, 250)
        Note over Server,DB: 【阶段三】BRPOP 结果收集与落库自愈
        Note over Server: JudgeResultBlockingListener 常驻守护线程
        Server->>Redis: 11. BRPOP judge:results 毫秒级阻塞监听唤醒
        Redis-->>Server: 弹出就绪 submissionId
        Server->>Redis: 12. 读取 sub:status:{id} 终态评测数据
        Server->>DB: 13. UPDATE submission 批量持久化终态指标 (ACCEPTED / WA)
        Server->>DB: 14. 累加 365天 AC 打卡 & 点亮题集进度
    end

    %% 阶段四：极速轮询闭环与用例诊断
    rect rgb(240, 253, 244)
        Note over User,Web: 【阶段四】极速轮询闭环与用例诊断
        Web->>Redis: 15. GET sub:status:{id} (极速轮询 15~60ms)
        Redis-->>Web: 返回终态评测报告与测试点详情
        Web-->>User: 16. Monaco 工作台弹出全绿 AC 徽标
        Web-->>User: 17. 诊断抽屉展开 (输入 vs 输出 Diff 对拍报告)
    end
```

</details>

#### 3. 评测沙箱核心状态机生命周期 (State Machine Lifecycle)

```mermaid
stateDiagram-v2
    [*] --> PENDING: 接口接收 & 初始落库
    PENDING --> COMPILING: Judger 协程认领任务并开始编译
    COMPILING --> COMPILE_ERROR: 编译失败 (CE 终端错误诊断)
    COMPILING --> RUNNING: 编译成功，装配测试用例执行

    state RUNNING {
        [*] --> ExecutingCase: 注入测试点输入
        ExecutingCase --> CheckingDiff: 输出成功捕获
        CheckingDiff --> NextCase: 结果对拍 100% 一致 (继续下一用例)
        CheckingDiff --> WRONG_ANSWER: 文本或数据行存在差异 (WA)
        ExecutingCase --> TIME_LIMIT_EXCEEDED: CPU 时间超限 (TLE)
        ExecutingCase --> MEMORY_LIMIT_EXCEEDED: Cgroups 物理内存熔断 (MLE)
        ExecutingCase --> RUNTIME_ERROR: 非零退出码 / 段错误 (RE)
    }

    RUNNING --> ACCEPTED: 所有测试用例全量通过
    COMPILE_ERROR --> [*]
    WRONG_ANSWER --> [*]
    TIME_LIMIT_EXCEEDED --> [*]
    MEMORY_LIMIT_EXCEEDED --> [*]
    RUNTIME_ERROR --> [*]
    ACCEPTED --> [*]
```

---

## 项目目录结构

### 模块全景与工程划分

| 顶级目录 | 核心分层 / 子模块 | 语言与技术栈 | 职责边界与关键业务编排 |
| :--- | :--- | :--- | :--- |
| **`frontend/`** | `src/views/` | Vue 3.5 + TS 5.9 | 答题工作台、实时诊断抽屉、全真机考、课练督学、开发者主页与管理后台 |
| | `src/components/` | Vite 8 + CSS Vars | 0-Radius 直角工业风组件（`ClusterConsole`, `DbSchemaViewer`, `HeatmapCalendar`） |
| | `src/api/` & `stores/` | Axios + Pinia 3 | 模块化 REST API 通信、JWT 自动静默续签与全局答题草稿缓存 |
| **`backend/`** | `submission/` | Spring Boot 3.2.5 | 提交任务构建调度与常驻守护线程 `JudgeResultBlockingListener`（BRPOP 结果收集） |
| | `database/` | MyBatis 3 + MySQL | SQL OJ 题库管理、测试点元数据内省与独立库对拍生命周期管理 |
| | `question/` & `rank/` | Redis 7 + HikariCP | 算法题库标签检索、测试用例管理、天梯榜防雪崩缓存（120s TTL + Jitter） |
| | `exam/` & `practice/` | Java 17 (LTS) | 考场快照组卷、防作弊切屏监测、ACM 罚时 / OI 快照加权评分、随堂课练准入 |
| | `db/migration/` | Flyway V20 | 生产级数据库版本化迁移脚本（含 `idx_user_status_qid` 复合覆盖索引） |
| **`judger/`** | `cmd/judger/` | Go 1.22+ | 评测守护进程常驻主入口、OS 信号捕获（`SIGINT/SIGTERM` 优雅关机） |
| | `internal/sandbox/` | Docker + Cgroups v2 | 容器硬件限额（`cpu.max`, `memory.max`, `pids.max`）、无网络（`--network none`）与只读镜像 |
| | `internal/sandbox/` | MySQL 8.0 (:3307) | 独立专用评测实例，动态创建 `judge_{subId}_{caseId}` 临时库，执行比对后即焚 |
| | `internal/compare/` | 算法/SQL 对拍器 | 空白过滤对拍、1e-6 浮点容差比对与纳秒级时间/内存峰值精准测量 |
| | `internal/queue/` | go-redis v9 | `BLMOVE` 原子认领防止宕机丢任务、3秒周期节点心跳上报 |
| **`deploy/`** | 协同脚本 | PowerShell / Bash | 跨平台一键环境自检、容器拉起、Go 判题编译与服务协同启停（`start` / `stop`） |
| **`docs/`** | 规范文档 | Markdown | 产品需求规格 (PRD)、设计系统规范、全局接口契约与后端性能优化落地手册 |

---

## 本地开发与部署指南

### 1. 前置依赖环境
- **Node.js**：`20.19+` 或 `22.12+`（推荐使用 `pnpm` 或 `npm`）
- **Java**：`JDK 17+`（推荐 Eclipse Temurin 17 / OpenJDK 17）
- **Go**：`1.22+`
- **Docker**：Docker Desktop（Windows / macOS）或 Docker Engine（Linux）
- **MySQL 8.0**：业务主库（端口 `3306`，首次启动由 Flyway 自动完成 Schema 迁移与种子数据填充）

---

### 2. 一键协同拉起（推荐）

项目在 `deploy/` 目录下提供了跨平台协同自动化脚本，会自动检查环境、启动 Redis 与独立判题 MySQL 容器、编译 Go 判题引擎，并在后台拉起所有服务：

```powershell
# Windows PowerShell
.\deploy\start.ps1

# 停止所有服务
.\deploy\stop.ps1
```

```bash
# Linux / macOS
chmod +x ./deploy/start.sh ./deploy/stop.sh
./deploy/start.sh

# 停止所有服务
./deploy/stop.sh
```

---

### 3. 分步手动启动

#### 步骤一：启动 Redis 与独立判题 MySQL 容器
```bash
# 启动 Redis 7 消息中间件与缓存
docker run -d --name kece-redis --restart unless-stopped \
  -p 6379:6379 redis:7-alpine

# 启动 SQL 评测专用独立临时库实例（注意端口为 3307，切勿占用业务主库 3306）
docker run -d --name kece-judge-mysql --restart unless-stopped \
  -p 3307:3306 -e MYSQL_ROOT_PASSWORD=judge123 mysql:8.0 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

#### 步骤二：启动后端 Spring Boot 核心服务
```powershell
cd backend
$env:SPRING_PROFILES_ACTIVE = "dev"   # Linux/macOS: export SPRING_PROFILES_ACTIVE=dev
.\mvnw.cmd spring-boot:run            # Linux/macOS: ./mvnw spring-boot:run
```
- 后端服务端口：`http://localhost:8080/api/v1`
- 交互式接口文档：`http://localhost:8080/api/v1/swagger-ui.html`
- 预置测试账号（Flyway 自动播种）：
  - **平台管理员**：`admin` / `admin123`
  - **出题教师**：`teacher` / `teacher123`
  - **示例学生**：`student` / `student123`

#### 步骤三：启动 Go 判题沙箱 Worker
```powershell
cd judger
go build -o judger.exe ./cmd/judger

$env:REDIS_ADDR = "127.0.0.1:6379"
$env:JUDGE_MYSQL_HOST = "127.0.0.1"
$env:JUDGE_MYSQL_PORT = "3307"
$env:JUDGE_MYSQL_USER = "root"
$env:JUDGE_MYSQL_PASSWORD = "judge123"
.\judger.exe
```
> **提示**：沙箱首次判题时会自动通过 Docker API 下载对应编译环境镜像（`gcc:13`, `eclipse-temurin:17-jdk`, `python:3.12-slim`），可提前执行 `docker pull` 预热镜像。

#### 步骤四：启动前端开发服务器
```bash
cd frontend
npm install
npm run dev
```
- 前端开发站点：`http://localhost:5173`

---

## 关联项目规范与文档

- [产品需求规格说明书 (PRD)](./docs/需求文档.md) — 平台功能边界、用户角色模型与全场景用例
- [产品规格与设计原则 (PRODUCT.md)](./docs/PRODUCT.md) — 0-Radius 设计系统、视觉规范与技术指标
- [全局接口规范与公共约定](./docs/全局接口规范与公共约定.md) — RESTful API 契约、响应结构与状态机枚举
- [后端性能优化与工程落地手册](./docs/后端性能深度优化与工程落地手册.md) — BRPOP 异步流转、ZSET 排行榜与覆盖索引实战
- [工程推进与里程碑计划 (WBS)](./docs/开发计划.md) — 历史版本迭代规划与里程碑记录
- [版本变更日志 (CHANGELOG.md)](./CHANGELOG.md) — 详细记录版本演进、修复与加固记录

---

## 许可证

本项目为私有工程项目，未经授权许可不得用于商业运营。
