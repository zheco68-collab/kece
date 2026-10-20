![背景](./演示图/Snipaste_2026-10-14_14-34-59.png)

# KECE — 综合编程与全真技术技能评测平台

[![Vue 3](https://img.shields.io/badge/Frontend-Vue%203%20%7C%20TypeScript%20%7C%20Vite-42b883?style=flat-square)](https://vuejs.org/)[![Spring Boot](https://img.shields.io/badge/Backend-Spring%20Boot%203%20%7C%20Java%2017-6db33f?style=flat-square)](https://spring.io/projects/spring-boot)[![Go Judger](https://img.shields.io/badge/Judger-Go%20%7C%20Docker%20Sandbox-00add8?style=flat-square)](https://golang.org/)[![Database](https://img.shields.io/badge/Database-MySQL%208%20%7C%20Redis%207-00758f?style=flat-square)](https://www.mysql.com/)

> **全真技术工程机试与在线评测平台**，集 **算法 OJ**、**数据库 SQL OJ**、**客观题自动评测** 与 **全流程组卷考试/竞赛系统** 于一体。服务于企业技术招聘笔试、高校实验教学及专业刷题竞技场景。

---

## 在线体验  https://kece.zheco68.asia/



##  界面全景展示 (Interface Showcase)

KECE 采用**纯粹极简的硬朗直角（0-Radius）与 Bento Grid 工业美学**设计，摒弃多余拟物与圆角修饰，采用高对比度黑白灰、数码排盘以及标志性的**评测直角状态方块矩阵**。

### 1. 全真隔离考场工作台
![](./演示图/Snipaste_2026-10-20_10-20-39.png)

---

### 2. 考试归档战报与 Bento 四大指标方块
* 考卷提交后即刻生成永久锁定归档战报。顶部采用标志性 **四大指标 Bento 直角大方块**（综合总分、全场名次、AC 通过题数、ACM 罚时）。
* 下方聚合全场实时天梯榜，配备冠亚季军纯黑、深灰、中灰直角方块勋章。

![逐题评测状态方块与作答快照](./演示图/Snipaste_2026-10-14_14-13-57.png)

---

### 3. 逐题 64×64px 评测状态方块与作答快照回溯
* 每道试题配备 **64×64px 硬朗直角评测状态方块**（`AC` 翡翠绿、`WA` 猩红、`未答` 石板灰高反差），直观呈现得分折算。
* 点击卡片即可丝滑展开**原始提交内容快照抽屉**，支持一键代码复制与回溯。

![逐题评测状态方块与作答快照](./演示图/Snipaste_2026-10-14_14-15-41.png)

---

### 4. 考试大厅卡片与外部实时天梯榜
* 考试列表卡片清晰呈现赛制标记（ACM/OI）、起止时间及状态。
* 考生交卷后「进入考场」按钮自动**变灰禁用为 `[已交卷]`**，同步浮现 `[查看战报 →]`。
* 在考试卡片外部可直接点击 **`[实时排名]`**，原地呼出高反差 Bento 实时天梯榜弹窗，支持 10 秒静默轮询。

![考试大厅卡片与交卷状态](./演示图/Snipaste_2026-10-14_14-16-45.png)

---

### 5. 算法编程：作答工作台与测试点评测结果
* 题目规格详述与约束条件；提交后实时呈现用例评测节点方块与执行耗时/内存。

![算法编程提交工作台](./演示图/Snipaste_2026-10-14_14-18-17.png)



![算法评测结果与用例节点](./演示图/Snipaste_2026-10-14_14-18-52.png)



![数据库 SQL 作答工作台](./演示图/Snipaste_2026-10-14_00-20-27.png)

---

### 6. 数据库 SQL：编写工作台与沙箱对拍结果
* 支持建表 DDL 一键复制与查看，一次性沙箱对拍结果实时反馈。

![数据库评测对拍结果](./演示图/Snipaste_2026-10-14_00-21-05.png)

------

![客观题作答工作台](./演示图/Snipaste_2026-10-14_00-21-46.png)

---

### 7. 客观题练习：单选/多选/填空与字母方块矩阵
* 支持 LaTeX 与 Markdown 排版，配备 32×32px 直角字母选项方块矩阵。

![客观题作答工作台](./演示图/Snipaste_2026-10-14_14-21-07.png)



![客观题作答工作台](./演示图/Snipaste_2026-10-14_14-21-31.png)

---

##  核心产品特性

### 1. 三位一体评测引擎
- **算法 OJ**：
  - 支持 **C/C++**、**Java**、**Python** 等多主流语言。
  - 完整评测状态机（`AC` · `WA` · `TLE` · `MLE` · `RE` · `CE` · `SE`）。
- **数据库 SQL OJ**：
  - 原生支持 **MySQL 8.0** 方言，覆盖 `SELECT` 查询与 `DML` 数据修改题型。
  - **物理级判题强隔离**：每次评测在独立判题 MySQL 实例中生成临时库 `judge_{提交号}_{测试点号}`，跑完立即销毁 DROP，绝不污染业务数据。
  - 智能对拍算法：按列名严格/类型宽容度 + 浮点精度宽容度 + 可选严格行序智能比对。
- **客观题测评**：
  - 单选题、多选题（**漏选半分**算法）、填空题（精确/忽略大小写/正则匹配）。
  - 全面支持 Markdown 排版、语法高亮与 LaTeX 公式渲染。

### 2. 考试与竞赛全流程系统
- **双赛制支持**：
  - **ACM 赛制**：考试中代码题即时提交评测，天梯榜按 AC 题数与罚时（首 AC 分钟数 + 20 分钟错误惩罚）实时排序。
  - **OI 赛制**：考试期间只保存作答快照，交卷后由后台评测引擎批量入队判分，支持部分测试点加权分值折算。
- **题目快照组卷机制**：
  - 试卷创建时即刻**固化题面与分值快照**，题库后续修改不会影响历史考卷的公平性。
  - 评测机百分制原始得分自动无缝映射为试卷设定分值（$\text{score} = \text{round}(\frac{\text{raw} \times \text{snapshot}}{100})$）。
- **防作弊系统**：
  - 浏览器窗口切屏检测、次数记录与考场预警，交卷后记录归档于管理端。
- **防重入交卷保护**：
  - 考场交卷后永久锁定作答记录，考场入口安全阻断，考试大厅按钮置灰。

### 3. 开发者主页与公开档案 (Developer Portfolio)
- **硬朗个人名片**：128×128px 直角大头像（支持 5MB 本地文件上传与 10MB 请求优雅容错）、个性签名 Slogan 引用框、UID、加入时间及 GitHub 外链直达。

- **52-周代码打卡热力图**：真实沉淀过去 365 天每日 AC 代码提交量，配备「年度 AC 题数」、「当前连击」与「历史最长连击」三大高反差指标方块。

- **全量评测历史整合**：打破传统割裂，主页深度整合算法题代码、数据库 SQL 与客观题作答三大分类，支持状态精确筛选、代码弹窗高亮回溯与一键复制。

- **全平台公开透明（GitHub / LeetCode 范式）**：开放跨用户主页访问（`/profile/:id`），所有用户可互相查看彼此的技术打卡与实战提交，访客视角安全隔离编辑特权。

  ![客观题作答工作台](./演示图/Snipaste_2026-10-14_14-23-01.png)

- **工业化极客交互与纯色体系**：
  - **纯色设计规范**：角色纯色（管理员紫/教师绿/学生黑）、算法难度（简单绿/中等橙/困难红）、数据库题型（SELECT 纯黑/DML 钛金深灰）；
  - **题库列表工业化**：单列富信息卡片（题号、状态圈、时空限制、支持语言）、做题进度仪表板、随机刷题快捷键；
  - **顶栏交互**：主导航纯净直观，配备鼠标悬浮灰色方块平滑滑动（Hover Slider）动效。

---

## 技术栈与架构

### 技术选型

| 架构层 | 核心技术选型 | 说明 |
| :--- | :--- | :--- |
| **前端 SPA** | **Vue 3** + **Vite** + **TypeScript** | 组合式 API + `<script setup>` |
| **代码编辑** | **Monaco Editor** (`@monaco-editor/vue`) | VS Code 同款内核代码编辑器 |
| **状态 & 路由** | **Pinia** + **Vue Router 4** | 集中状态管理与鉴权守卫 |
| **后端 API** | **Spring Boot 3** + **Java 17** | 模块化分层架构、统一响应封装 |
| **持久化层** | **MyBatis** + **MySQL 8.0** | 业务主库，事务级读写保障 |
| **缓存 & 队列** | **Redis 7** (Spring Data Redis) | 判题任务解耦队列与评测进度缓存 |
| **沙箱判题机** | **Go 1.22+** + **Docker Engine** | Cgroups v2 + Namespace 强隔离沙箱 |
| **数据库判题** | 独立 **MySQL 8.0 容器** (端口 3307) | 一次性临时库隔离运行 |

### 异步评测链路架构

<div style="background: #161b22; border: 1px solid #30363d; padding: 16px 20px; margin: 16px 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; color: #c9d1d9;">
<div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; padding-bottom: 8px; border-bottom: 1px solid #30363d;">
<div style="display: flex; align-items: center; gap: 8px;">
<span style="background: #1e1e1e; border: 1px solid #484f58; color: #ffffff; font-size: 10px; font-weight: 700; padding: 2px 6px; font-family: monospace;">PIPELINE</span>
<strong style="color: #f0f6fc; font-size: 14px;">KECE 异步多模态判题链路拓扑</strong>
</div>
<span style="font-size: 11px; color: #8b949e; font-family: monospace;">High-Concurrency Engine v0.2.1</span>
</div>
<table width="100%" cellpadding="0" cellspacing="8" style="border-collapse: separate; table-layout: fixed;">
<tr>
<td width="25%" valign="top" style="background: #0d1117; border: 1px solid #388bfd; padding: 12px;">
<div style="margin-bottom: 6px;"><span style="background: #1971c2; color: #fff; font-size: 10px; font-weight: 700; padding: 1px 5px;">1. 客户端</span> <code style="font-size: 11px; color: #79c0ff;">Vue 3</code></div>
<div style="font-weight: 600; color: #f0f6fc; font-size: 12px; margin-bottom: 4px;">Monaco Editor</div>
<div style="font-size: 11px; color: #8b949e; line-height: 1.5;">• 代码/SQL 作答与提示<br>• 1s 轮询评测终态<br>• 实时结果比对呈现</div>
<div style="margin-top: 8px; padding-top: 6px; border-top: 1px dashed #30363d; font-size: 11px; color: #58a6ff; font-family: monospace; text-align: center;">POST /submissions ➔</div>
</td>
<td width="25%" valign="top" style="background: #0d1117; border: 1px solid #2ea043; padding: 12px;">
<div style="margin-bottom: 6px;"><span style="background: #2b8a3e; color: #fff; font-size: 10px; font-weight: 700; padding: 1px 5px;">2. 业务调度</span> <code style="font-size: 11px; color: #7ee787;">Java 17</code></div>
<div style="font-weight: 600; color: #f0f6fc; font-size: 12px; margin-bottom: 4px;">Spring Boot 3</div>
<div style="font-size: 11px; color: #8b949e; line-height: 1.5;">• JWT 鉴权与防重校验<br>• 初始状态置 PENDING<br>• MySQL 提交记录持久化</div>
<div style="margin-top: 8px; padding-top: 6px; border-top: 1px dashed #30363d; font-size: 11px; color: #3fb950; font-family: monospace; text-align: center;">LPUSH judge:queue ➔</div>
</td>
<td width="25%" valign="top" style="background: #0d1117; border: 1px solid #f85149; padding: 12px;">
<div style="margin-bottom: 6px;"><span style="background: #c92a2a; color: #fff; font-size: 10px; font-weight: 700; padding: 1px 5px;">3. 异步解耦</span> <code style="font-size: 11px; color: #ff7b72;">Redis 7</code></div>
<div style="font-weight: 600; color: #f0f6fc; font-size: 12px; margin-bottom: 4px;">Broker & Cache</div>
<div style="font-size: 11px; color: #8b949e; line-height: 1.5;">• <code>judge:queue</code> 算法题<br>• <code>judge:sql:queue</code> 数据库<br>• 分布式评测锁与缓存</div>
<div style="margin-top: 8px; padding-top: 6px; border-top: 1px dashed #30363d; font-size: 11px; color: #ff7b72; font-family: monospace; text-align: center;">BRPOP 任务认领 ➔</div>
</td>
<td width="25%" valign="top" style="background: #0d1117; border: 1px solid #a371f7; padding: 12px;">
<div style="margin-bottom: 6px;"><span style="background: #7048e8; color: #fff; font-size: 10px; font-weight: 700; padding: 1px 5px;">4. 判题执行</span> <code style="font-size: 11px; color: #d2a8ff;">Go 1.22+</code></div>
<div style="font-weight: 600; color: #f0f6fc; font-size: 12px; margin-bottom: 4px;">Judger Worker</div>
<div style="font-size: 11px; color: #8b949e; line-height: 1.5;">• 协程池高并发消费<br>• 看门狗超时熔断<br>• 智能比对结果落库</div>
<div style="margin-top: 8px; padding-top: 6px; border-top: 1px dashed #30363d; font-size: 11px; color: #d2a8ff; font-family: monospace; text-align: center;">沙箱隔离执行 ⬇</div>
</td>
</tr>
</table>
<table width="100%" cellpadding="0" cellspacing="8" style="border-collapse: separate; margin-top: 4px; table-layout: fixed;">
<tr>
<td width="50%" valign="top" style="background: #0d1117; border: 1px solid #30363d; border-left: 3px solid #1971c2; padding: 10px 14px;">
<div style="font-size: 12px; font-weight: 700; color: #58a6ff; margin-bottom: 4px;">SANDBOX A：Docker / Linux Cgroups v2 沙箱</div>
<div style="font-size: 11px; color: #8b949e; line-height: 1.5;">严格限制 CPU 限额与 256MB 内存，Seccomp 拦截危险 syscall 并禁用宿主机网络，产出 AC / WA / TLE / MLE / RE。</div>
</td>
<td width="50%" valign="top" style="background: #0d1117; border: 1px solid #30363d; border-left: 3px solid #495057; padding: 10px 14px;">
<div style="font-size: 12px; font-weight: 700; color: #e6edf3; margin-bottom: 4px;">SANDBOX B：独立 MySQL 判题实例 (端口 3307)</div>
<div style="font-size: 11px; color: #8b949e; line-height: 1.5;">动态创建临时库 <code>judge_{subId}_{point}</code>，导入测试数据后执行 SQL 并智能对拍，执行完毕立即 DROP 销毁，物理级强隔离。</div>
</td>
</tr>
</table>
</div>

```mermaid
flowchart LR
    subgraph Client ["客户端"]
        Vue["Vue 3 SPA<br/>(Monaco Editor)"]
    end

    subgraph Backend ["业务核心"]
        Boot["Spring Boot 3<br/>(业务调度)"]
        DB[("MySQL 8.0<br/>(业务主库)")]
    end

    subgraph Middleware ["异步中间件"]
        Redis[("Redis 7<br/>(任务队列)")]
    end

    subgraph Engine ["评测集群"]
        Worker["Go 判题 Worker<br/>(协程池调度)"]
        Docker["Docker 沙箱<br/>(Cgroups 算法评测)"]
        MySQL3307["MySQL 3307<br/>(临时库 SQL 评测)"]
    end

    Vue -->|1. 提交代码/SQL| Boot
    Vue -.->|轮询 1s 查终态| Boot
    Boot -->|2. 记录持久化| DB
    Boot -->|3. LPUSH 任务| Redis
    Redis -->|4. BRPOP 消费| Worker
    Worker -->|5a. 编译运行| Docker
    Worker -->|5b. 临时库对拍| MySQL3307
    Docker -->|结果落库| DB
    MySQL3307 -->|结果落库| DB

    style Vue fill:#1971c2,stroke:#1864ab,color:#fff
    style Boot fill:#2b8a3e,stroke:#237032,color:#fff
    style DB fill:#495057,stroke:#343a40,color:#fff
    style Redis fill:#c92a2a,stroke:#b02525,color:#fff
    style Worker fill:#7048e8,stroke:#5f3dc4,color:#fff
    style Docker fill:#1e1e1e,stroke:#343a40,color:#fff
    style MySQL3307 fill:#1e1e1e,stroke:#343a40,color:#fff
```
---

## 项目目录结构

```
KECE/
├── frontend/               # 前端 Vue 3 + Vite 单页应用
│   ├── src/
│   │   ├── api/            # REST API 接口契约封装
│   │   ├── components/     # Monaco 编辑器、通用导航、消息弹窗
│   │   ├── router/         # 页面路由与权限导航守卫
│   │   ├── stores/         # Pinia 用户认证状态与考场上下文
│   │   ├── views/          # 考场工作台、战报页、题库列表、管理后台
│   │   └── style.css       # 0-Radius 极简硬朗直角设计系统规范
├── backend/                # 后端 Spring Boot 3 工程
│   ├── src/main/java/com/kece/
│   │   ├── admin/          # 管理控制台模块
│   │   ├── common/         # 统一响应包装、异常拦截、安全拦截器
│   │   ├── database/       # 数据库 SQL OJ 题库与提交管理
│   │   ├── exam/           # 考试场次、组卷快照、交卷评分、排名服务
│   │   ├── objective/      # 客观题题库与判题器
│   │   ├── question/       # 算法题目与测试用例管理
│   │   ├── submission/     # 代码提交管理与异步结果收集调度器
│   │   └── user/           # 用户认证、JWT 生成、权限管理
│   └── src/main/resources/
│       ├── db/migration/   # Flyway 数据库版本化迁移脚本
│       └── mapper/         # MyBatis SQL 映射文件
├── judger/                 # Go 语言实现的沙箱判题机服务
│   ├── cmd/judger/         # 判题机入口进程
│   └── internal/           # Redis 队列消费者、Docker 沙箱运行器、SQL 对拍器
├── docs/                   # 架构决策文档 (ADR) 与设计展示资产
│   └── images/             # 界面展示截图
└── deploy/                 # Docker Compose 配置与一键运维启动脚本
```

---

## 本地快速启动

### 前置环境需求
* **Node.js** 20+
* **Java** 17+ (推荐 Eclipse Temurin 或 OpenJDK)
* **Go** 1.22+
* **Docker Desktop** (用于拉起沙箱容器与测试数据库)
* **MySQL 8.0** (本机运行，端口 3306，数据库名 `kece`，密码 `kece123`)

---

### 一键协同启动（推荐）

项目在 `deploy/` 下提供了全自动化服务拉起脚本，会自动检查判题容器、编译判题机并启动前端、后端与 Worker 进程：

```bash
# Windows PowerShell
.\deploy\start.ps1

# Linux / macOS
chmod +x ./deploy/start.sh && ./deploy/start.sh
```

如需终止所有后台进程：
```bash
.\deploy\stop.ps1    # Linux: ./deploy/stop.sh
```

---

### 手动分步启动

#### 1. 启动辅助容器（Redis 与 独立判题库）
```bash
docker run -d --name kece-judge-mysql --restart unless-stopped \
  -p 3307:3306 -e MYSQL_ROOT_PASSWORD=judge123 mysql:8.0 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci

docker run -d --name kece-redis --restart unless-stopped -p 6379:6379 redis:7-alpine
```

#### 2. 启动后端 Spring Boot
```bash
cd backend
$env:SPRING_PROFILES_ACTIVE = "dev"   # Linux/macOS: export SPRING_PROFILES_ACTIVE=dev
.\mvnw.cmd spring-boot:run           # Linux/macOS: ./mvnw spring-boot:run
```
* **API 接口地址**：`http://localhost:8080/api/v1`
* **Swagger 交互文档**：`http://localhost:8080/api/v1/swagger-ui.html`
* 预置种子测试账号：
  * **管理员**：`admin` / `admin123`
  * **出题教师**：`teacher` / `teacher123`

#### 3. 启动前端 Vite 开发服务器
```bash
cd frontend
npm install
npm run dev
```
* 访问主站界面：`http://localhost:5173`

#### 4. 启动 Go 判题引擎
```bash
cd judger
go build -o judger.exe ./cmd/judger

# 启动判题引擎（Windows PowerShell）
$env:REDIS_ADDR = "127.0.0.1:6379"
$env:JUDGE_MYSQL_HOST = "127.0.0.1"
$env:JUDGE_MYSQL_PORT = "3307"
$env:JUDGE_MYSQL_USER = "root"
$env:JUDGE_MYSQL_PASSWORD = "judge123"
.\judger.exe
```

> **提示**：判题机首次运行会自动拉取 `gcc:13`、`eclipse-temurin:17-jdk`、`python:3.12-slim` 基础沙箱镜像。建议提前 `docker pull` 以加速初次运行。

---

## 关联项目文档

* [需求文档 (PRD)](./需求文档.md) — 完整功能规划与用例说明
* [产品规格说明 (PRODUCT.md)](./PRODUCT.md) — 平台设计原则与技术指标
* [全局接口契约规范](./全局接口规范与公共约定.md) — REST 响应规范与状态机定义
* [工程实施计划 (WBS)](./开发计划.md) — 迭代拆解与架构决策记录

---

## 许可证

私有项目，未经授权许可不得用于商业运营。
