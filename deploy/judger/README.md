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

## 2. 接入新节点

1. 部署并启动节点，保留宿主机共享的 `JUDGE_HOST_IDENTITY_FILE`、每个实例独立的 `JUDGE_IDENTITY_FILE` 和 `JUDGE_CREDENTIAL_FILE`。同机节点共用 `hostId`，但各自有独立 `instanceId` 和密钥。
2. 确认后端能访问节点的 API 端口。生产脚本 `deploy/sync_judger.py` 会打印每个专池的完整 API 地址；Compose 模板使用各自环境文件的 `JUDGE_HEALTH_PORT`。前端只要求输入 API 地址，不要求节点证书或同一局域网。
3. 登录管理员后台 `/admin/judgers`，点击「接入评测机」，粘贴一个地址并提交。后端自动发现池型、宿主身份和槽位，创建接入记录；节点随后自动绑定、心跳、自检和启用。每个独立专池节点各提交一次地址。
4. 管理页显示接入进度、错误和最近记录。宿主机总槽位初始值由后端 `kece.judging.new-host-slots` 控制，首次接入后可在宿主机分组中调整，避免多池超卖。

## 3. 启动与自检启用
同机控制面可使用回环 HTTP；跨机器的节点到控制面凭据通道使用 HTTPS。节点发现 API 可使用 HTTP 或 HTTPS，不要求证书受后端信任。每个 Compose 项目自动创建自己的网络。

预先准备本池镜像（如 Python 池的 `docker pull python:3.12-slim`）。使用不同项目名、API 端口和本机工作路径启动不同节点。把环境文件及数据库凭据保存在仓库之外：
```bash
# 代码专池示例（Python）
sudo install -d -m 0700 /etc/kece/judger
sudo install -m 0600 deploy/judger/node.python.env.example /etc/kece/judger/python-1.env
# 编辑控制面 URL、API 端口及本地工作路径；创建共享身份目录。
sudo install -d -m 0700 /var/lib/kece-judger/identity
sudo docker compose -f deploy/judger/compose.pool.yml --env-file /etc/kece/judger/python-1.env -p kece-judger-py1 up -d

# 数据库专池示例（MySQL，无 docker.sock 挂载）
sudo install -m 0600 deploy/judger/node.mysql.env.example /etc/kece/judger/mysql-1.env
# 编辑控制面 URL、API 端口、独立判题库地址与凭据。
sudo docker compose -f deploy/judger/compose.mysql.yml --env-file /etc/kece/judger/mysql-1.env -p kece-judger-sql1 up -d
```

启动流程：
1. 未绑定节点常驻暴露签名的 `POST /v1/node/info`，等待管理员提交其 API 地址。
2. 后端完成身份发现和接入批准后，节点用实例私钥签名请求绑定，把仅属于自己的凭据写入持久卷；已有凭据的旧节点继续恢复原会话并通过认证心跳迁移身份。
3. 节点认证心跳上报 Docker 镜像或专用 MySQL 的依赖健康，后端自动发起当前会话自检并启用。需要诊断时仍可使用：
   ```bash
   ADMIN_TOKEN="eyJ..." ./deploy/judger/verify-node.sh <nodeId> http://127.0.0.1:8080/api/v1
   ```
4. 接入记录达到 `SUCCEEDED`，节点且处于 `ENABLED` 后开始领取本池任务。停用或排空的旧节点不会因为重复提交 API 地址而自动恢复接单。

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
- 节点 API 可由反向代理转发；如果使用 `deploy/nginx/judger-health.example.conf`，需确保 `POST /v1/node/info` 可达。节点 API 的证书和地址网段不是接入条件。

### Linux 宿主机验收

- 以 `systemd-analyze verify /etc/systemd/system/kece-judger@.service` 检查模板，逐节点执行 `systemctl start`、自检、排空与 `systemctl stop`。确认停机后无本节点租约关联的沙箱，并验证重新启用节点可继续领单。
- 有宿主总额限制时，先通过 `docker info --format '{{.CgroupDriver}}'` 确认 Docker 的 cgroup 驱动，在宿主机配置相应的 slice 或 cgroup 路径，再设置 `JUDGE_CGROUP_PARENT`。用 `docker inspect --format '{{.HostConfig.CgroupParent}}' <sandbox-id>` 确认编译与运行容器归属。用隔离样例验证超时和内存限制，并记录强杀 worker 后的任务重领及旧会话沙箱清理结果。
