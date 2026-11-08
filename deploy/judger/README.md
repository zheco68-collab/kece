# KECE 评测机专池节点运维手册

本文档指导如何部署与运维 C++、Java、Python、MySQL、ArkTS 五大独立评测专池节点。

## 1. 专池规划与运行时规格

| 专池代码 | 运行时规格 (`JUDGE_RUNTIME_SPEC`) | 默认沙箱镜像 / 外部依赖 | 推荐初始槽位 | 内存 / 编译限制 | 配置文件模板 |
| --- | --- | --- | --- | --- | --- |
| `cpp` | `cpp-v1` | `gcc:13` | 2 槽 | 1024 MB 编译 / cgroup 隔离 | `node.cpp.env.example` |
| `java` | `java-v1` | `eclipse-temurin:17-jdk` | 2 槽 | 1024 MB 编译 / 运行时堆限额 | `node.java.env.example` |
| `python` | `python-v1` | `python:3.12-slim` | 2 槽 | 免编译 / 256 MB cgroup 限制 | `node.python.env.example` |
| `mysql` | `mysql-v1` | 专用独立 MySQL 8.0 实例 (`3307` 端口) | 2 槽 | 临时库 + 临时受限账号 + 孤儿清理 | `node.mysql.env.example` |
| `arkts` | `arkts-v1` | `kece-sandbox-arkts:latest` | 2 槽 | 1024 MB 编译 / 512 MB 运行 | `node.arkts.env.example` |

> **安全红线**：
> 1. MySQL 评测池节点**必须**指向专用的判题 MySQL 实例，严禁指向业务 MySQL，且部署时使用 `compose.mysql.yml`（**不挂载** `/var/run/docker.sock`）。
> 2. 代码专池节点通过宿主机 `/var/run/docker.sock` 拉起沙箱容器（禁止给 worker 容器开启 `privileged: true`），`JUDGE_HOST_WORKSPACE` 在宿主机与容器内采用同名路径挂载，确保 `docker run -v` 挂载源路径一致。

## 2. 注册新节点
1. 登录管理员后台，进入「评测与内容」->「评测机管理」（`/admin/judgers`）。
2. 若尚未创建宿主机分组，先创建「宿主机分组」设定该物理机总并发槽位上限（防多池并发超卖）。
3. 点击「登记评测节点」，填写：
   - 节点名称（如 `worker-py-01`、`worker-sql-01`）
   - 评测池（`python` / `cpp` / `java` / `mysql` / `arkts`）
   - 宿主机分组（选择对应的物理机预算分组）
   - 本地最大槽位（建议先按 `2` 建立基线）
   - 节点内网 HTTPS 探测地址（可选）。填写后，自检前由后端访问固定的 `GET /healthz`，校验 HTTPS 证书、节点 ID、池、运行时版本和依赖健康。留空的拉取式节点使用认证心跳和运行时自检。
4. 复制一次性接入码（10 分钟有效，只展示一次）。

## 3. 启动与自检启用
同机和跨机的独立专池都通过可路由的内网 HTTPS 控制面接单。每个 Compose 项目自动创建自己的网络。先确认工作机内能解析控制面域名，且信任签发证书的 CA。应用后端的内网监听和公网阻断配置见第 5 节。

预先准备本池镜像（如 Python 池的 `docker pull python:3.12-slim`）。使用不同项目名和本机工作路径启动不同节点。把含接入码和数据库凭据的文件保存在仓库之外：
```bash
# 代码专池示例（Python）
sudo install -d -m 0700 /etc/kece/judger
sudo install -m 0600 deploy/judger/node.python.env.example /etc/kece/judger/python-1.env
# 编辑控制面 URL、接入码及本地工作路径。
sudo docker compose -f deploy/judger/compose.pool.yml --env-file /etc/kece/judger/python-1.env -p kece-judger-py1 up -d

# 数据库专池示例（MySQL，无 docker.sock 挂载）
sudo install -m 0600 deploy/judger/node.mysql.env.example /etc/kece/judger/mysql-1.env
# 编辑控制面 URL、接入码、独立判题库地址与凭据。
sudo docker compose -f deploy/judger/compose.mysql.yml --env-file /etc/kece/judger/mysql-1.env -p kece-judger-sql1 up -d
```

启动流程：
1. 节点首次启动用接入码调用 `POST /api/v1/internal/judgers/join` 换取单节点长期凭据并落盘至 `credential.json`；后续重启自动通过 `/session` 递增会话代次。
2. 节点通过认证心跳上报 Docker 镜像或专用 MySQL 的依赖健康。Compose 把健康端口映射到宿主机回环。使用节点 HTTPS 探测时，为每个实例设置唯一的 `JUDGE_HEALTH_PORT`，例如 `19091`，并让节点端 TLS 代理转发到该端口。
3. 在后台发起节点自检，或使用命令行核验脚本：
   ```bash
   ADMIN_TOKEN="eyJ..." ./deploy/judger/verify-node.sh <nodeId> http://127.0.0.1:8080/api/v1
   ```
4. 脚本等待本次自检的 ID 到达 `PASSED`。通过后在后台将节点置为 `ENABLED`，节点即开始领取本池任务。

MySQL 位于节点宿主机时，可以把 `JUDGE_MYSQL_HOST` 设置为 `host.docker.internal`。专用库应监听 Docker 桥接网关可达的地址，并通过防火墙和账号授权限制来源。

## 4. 优雅排空、扩缩容与停机
1. 在管理后台将节点状态调整为 `DRAINING`（排空中）。
2. 节点立即停止领取新任务，已在途有效租约继续续租并回传结算。
3. 当管理后台显示该节点的正在执行任务数（Running）归零后，安全升级或停机：
```bash
sudo docker compose -f deploy/judger/compose.pool.yml --env-file /etc/kece/judger/python-1.env -p kece-judger-py1 down
```

## 5. 原生 systemd 实例与内网反向代理
- 若在 Linux 物理机使用 systemd 管理多实例，可将 `deploy/judger/kece-judger@.service.example` 安装至 `/etc/systemd/system/kece-judger@.service`，按 `/etc/kece/judger/<instance>.env` 独立启停（如 `systemctl enable --now kece-judger@python-1`）。**切勿对同一节点同时启用 Docker Compose 与 systemd**。
- 现有实例可从 `DRAINING` 改回 `ENABLED` 恢复领取。直接发出 `SIGTERM` 时，节点停止新领取，最多等待在途任务 30 秒，再留 5 秒清理。
- 在控制面宿主机启用 `deploy/nginx/internal-judgers.example.conf`，将允许来源收紧为实际评测机网段。在每个公网 API vhost 内引入 `deploy/nginx/public-deny-judgers.example.conf`。确保公网无法直连后端端口，并分别从内网和公网验证允许和拒绝路径。
- 使用节点 HTTPS 探测时，在节点宿主机参考 `deploy/nginx/judger-health.example.conf` 启用内网 TLS，允许后端源地址。后端须配置 `KECE_JUDGING_ALLOWED_NODE_HOSTS` 与 `KECE_JUDGING_ALLOWED_NODE_NETWORKS`（逗号分隔主机名及 CIDR），证书链须被后端 JVM 信任。升级后，已登记 HTTPS 地址的节点需要重新自检。

### Linux 宿主机验收

- 以 `systemd-analyze verify /etc/systemd/system/kece-judger@.service` 检查模板，逐节点执行 `systemctl start`、自检、排空与 `systemctl stop`。确认停机后无本节点租约关联的沙箱，并验证重新启用节点可继续领单。
- 有宿主总额限制时，先通过 `docker info --format '{{.CgroupDriver}}'` 确认 Docker 的 cgroup 驱动，在宿主机配置相应的 slice 或 cgroup 路径，再设置 `JUDGE_CGROUP_PARENT`。用 `docker inspect --format '{{.HostConfig.CgroupParent}}' <sandbox-id>` 确认编译与运行容器归属。用隔离样例验证超时和内存限制，并记录强杀 worker 后的任务重领及旧会话沙箱清理结果。
