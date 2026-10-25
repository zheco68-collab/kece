# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

（用户指定选型；部署目标：单机/云服务器直接部署）

- 前端：Vue 3 + Vite + TypeScript（SPA）；编辑器库 Monaco（多 Tab 代码编辑器）；Axios + 原生 EventSource（SSE 状态推送，匹配接口约定）
- 后端：Java + Spring Boot；MySQL（业务数据）+ Redis（评测状态缓存与消息队列，接口约定固定）
- 判题机 Worker：Go；Docker 强隔离沙箱（Cgroups v2 + Namespace + Seccomp）；MVP 与后端同机部署
- 对象存储：MVP 本地磁盘或 MinIO（>5MB 测试数据），后续可换 OSS

未单独问及的默认项：编辑器库 Monaco、对象存储 MinIO；如需改可再调整。

## Users

- 考生/认证用户：日常刷题、参加在线技术笔试或课程期末机考，提交代码并查看评测结果。
- 出题人/教师：题库 CRUD、配置测试用例、管理 AI 评分细则（Prompt）、组卷。
- 系统管理员：判题机节点管理、沙箱资源调度、全局权限分配、数据字典管理。
- 游客：浏览公开题库与公告、注册/登录，无法提交代码。
- 首要市场三者并重：企业招聘笔试、高校教学、技术社区刷题/打榜，三类场景同等重要，按多场景综合平台设计。

## Product Purpose

一个多维度的编程与技能评测平台，将算法 OJ、数据库 OJ、前端 AI 智能评测与标准客观题整合到同一产品中，为开发者、教师和招聘方提供一站式技术能力评估。成功 = 从提交代码到拿到可信评测结果（含前端题的 AI 结构化评语）的完整闭环。

## Positioning

传统 OJ 只覆盖算法与数据结构。本产品以「前端代码 AI 评测」为差异化机制：Playwright 无头浏览器的确定性 DOM/交互断言 + 多模态大模型视觉与代码审计（UI 还原度、代码质量、结构化建议），这是仅做算法判题的同类产品无法直接复制的。

## Operating Context

- 异步评测链路：提交 → 压入消息队列 → 判题机沙箱编译/运行 → 状态写入 Redis → 前端轮询或 SSE 获取结果。耗时从数百毫秒（算法/数据库）到 20–30 秒（前端 AI 评测）。
- 状态机：非终态 PENDING / COMPILING / JUDGING / AI_REVIEWING（需继续轮询）；终态 ACCEPTED / WRONG_ANSWER / TIME_LIMIT_EXCEEDED / MEMORY_LIMIT_EXCEEDED / RUNTIME_ERROR / COMPILE_ERROR / SYSTEM_ERROR（停止轮询）。
- 考试模式：ACM（提交即时反馈、按过题数与罚时排名）与 OI（交卷后统一评测、按用例通过比例给部分分）。
- 防作弊：切屏次数限制（超限自动交卷）、禁用代码区右键与 Ctrl+C/V、（后续迭代）基于 AST 的代码查重。
- 鉴权与约定：JWT Bearer；统一响应包装 `{code, message, data, timestamp}`；业务状态码 200 成功 / 4001 参数错误 / 4003 无权限 / 5000 系统错误。

## Capabilities and Constraints

- 多语言判题：C/C++、Java、Python、Go、Rust 等；SQL 方言 MySQL 8.0 / PostgreSQL；前端 HTML/CSS/JS 与 Vue/React。
- 数据库 OJ：每次评测在独立内存库（DuckDB/SQLite）初始化 Schema；结构比对（列名/列型/行数）、数据比对（浮点精度宽容）、ORDER BY 物理顺序强制校验。
- 前端评测混合管线：阶段一无头浏览器跑 DOM/交互断言；阶段二多模态 LLM 评分（UI 还原度、代码质量、`!important` 滥用等维度）输出 JSON。
- 客观题：单选、多选（支持漏选给一半分配置）、填空（精确 / 忽略大小写 / 正则校验）；富文本 Markdown + 代码高亮 + LaTeX。
- 安全（最高优先级）：Linux 强隔离沙箱、网络隔离（禁公网与内网）、系统调用白名单（拦截 execve/fork）。
- 性能：算法/数据库评测 P95 ≤ 3s；前端 AI 评测异步 ≤ 30s；SLA 99.9%，判题节点宕机自动重投队列；轮询接口不得直连 MySQL，须命中 Redis。
- 分期：Phase1 核心链路（注册/算法题库 CRUD/算法沙箱评测/客观题）→ Phase2 数据库 OJ + 组卷倒计时考试 + 消息队列 → Phase3 前端多文件编辑器 + Playwright + LLM 前端 AI 评测 → Phase4 防作弊 + 数据大屏 + 压测与判题集群扩容。

## Brand Commitments

尚无正式产品名；项目目录/代号为「KECE」，仅作代号，不构成品牌承诺。

## Evidence on Hand

- 需求文档.md（PRD：角色权限、核心模块、非功能需求、分期规划）。
- 全局接口规范与公共约定.md（接口规范、状态机、Redis/SSE 架构建议）。
- 当前无代码、无视觉资产、无真实用户数据、无评测样本。后续工作不得编造测试数据、评测结果、客户案例或指标。

## Product Principles

1. 评测结果可信优先：状态机、沙箱隔离、异步链路都服务于「结果可信」，界面不得弱化状态与进度的可读性。
2. 一站式多题型：算法 / 数据库 / 前端 AI / 客观题在同一平台内体验一致，而非四个割裂子系统。
3. 高并发考试场景优先：面向「几千人同时笔试」设计，状态反馈即时且轻量。
4. 三类用户并重：考生、出题人/教师、管理员的能力不被彼此妥协。
5. 安全是硬边界：沙箱与防作弊约束先于任何体验优化。
