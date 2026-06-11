<p align="center">
  <br>
  <h1 align="center">LandGate</h1>
  <p align="center">
    <strong>AI API Gateway for Anthropic, OpenAI, Gemini and Antigravity</strong>
  </p>
  <p align="center">
    将上游 AI 账号、订阅与 API Key 统一接入，提供认证、分组、计费、余额、并发控制与负载均衡能力。
  </p>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4.7-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot">
  <img src="https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white" alt="MySQL">
  <img src="https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white" alt="Redis">
  <img src="https://img.shields.io/badge/Flyway-Migration-CC0200?logo=flyway&logoColor=white" alt="Flyway">
  <img src="https://img.shields.io/badge/Docker-Ready-2496ED?logo=docker&logoColor=white" alt="Docker">
</p>

<p align="center">
  <a href="#功能亮点">功能亮点</a>
  ·
  <a href="#快速开始">快速开始</a>
  ·
  <a href="#接口入口">接口入口</a>
  ·
  <a href="#本地开发">本地开发</a>
  ·
  <a href="#安全建议">安全建议</a>
</p>

---

## 项目简介

LandGate 是一个面向 AI API 运营场景的网关服务。它可以把多平台上游账号和订阅额度统一包装成下游可调用的 API，并围绕 API Key、用户、分组、模型价格、余额、订单和用量日志建立完整的管理链路。

项目采用多模块 Maven + DDD 分层结构，核心网关逻辑与基础设施、领域模型、HTTP 入口解耦，便于扩展新的协议、上游平台和计费规则。

## 功能亮点

| 网关协议 | 账号调度 | 计费结算 |
| --- | --- | --- |
| Anthropic Messages<br>OpenAI Chat / Responses / Images<br>Gemini GenerateContent<br>Antigravity 路由 | 多账号池<br>优先级调度<br>粘性会话<br>失败切换<br>并发槽位控制 | 模型价格<br>Token 用量<br>Redis 原子预扣<br>MySQL 回写<br>用量统计 |

| 用户体系 | 运营管理 | 工程能力 |
| --- | --- | --- |
| 注册登录<br>邮箱验证<br>密码重置<br>API Key 自助管理<br>余额与配额 | 用户管理<br>分组管理<br>账号管理<br>模型价格<br>公告、兑换码、优惠码<br>支付订单和回调 | Spring Security<br>Flyway 迁移<br>MyBatis 持久化<br>Actuator / Prometheus<br>Docker Compose 部署 |

## 架构概览

```mermaid
flowchart LR
    C[Client] --> F[Auth Filters]
    F --> G[Gateway Controller]
    G --> D[Gateway Dispatcher]
    D --> S[Account Selector]
    S --> U[Upstream AI Provider]
    D --> B[Billing & Balance]
    B --> R[(Redis)]
    B --> M[(MySQL)]
    D --> L[Usage Logs]
```

```text
landgate
├── landgate-types           # 通用枚举、异常、响应模型
├── landgate-api             # 服务接口与 DTO
├── landgate-domain          # 领域模型、仓储接口、领域服务
├── landgate-infrastructure  # MyBatis、Redis、Security、HTTP 客户端、支付适配
├── landgate-trigger         # REST Controller、网关入口、定时任务
└── landgate-app             # Spring Boot 启动、配置、Flyway 迁移
```

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.4.7、Spring MVC、Spring Security |
| 架构 | DDD 分层、多模块 Maven、MapStruct、Lombok |
| 存储 | MySQL 8.0、MyBatis、Flyway |
| 缓存与并发 | Redis 7、Redisson、Lua 原子操作 |
| 鉴权 | JWT、API Key、BCrypt |
| 支付与上游 | Stripe SDK、微信支付、支付宝、AWS Bedrock SDK、OAuth |
| 运维 | Docker Compose、Actuator、Micrometer Prometheus |

## 快速开始

### Docker Compose

```bash
git clone https://github.com/Laqcce-cao/LandGate.git
cd LandGate

cp .env.example .env
```

编辑 `.env`，至少修改：

```bash
DB_PASSWORD=change_me_db_password
REDIS_PASSWORD=change_me_redis_password
JWT_SIGNING_KEY=change_me_jwt_signing_key_at_least_32_chars
CREDENTIAL_ENCRYPTION_KEY=change_me_credential_encryption_key_64_hex_chars
LANDGATE_ADMIN_EMAIL=admin@example.com
LANDGATE_ADMIN_PASSWORD=change_me_admin_password
```

启动：

```bash
docker compose up -d
```

| 服务 | 地址 |
| --- | --- |
| Web 管理端 | `http://localhost` |
| 后端 API | `http://localhost:8080` |
| phpMyAdmin | `http://localhost:8899` |
| MySQL | `localhost:3306` |
| Redis | `localhost:6379` |

> 应用镜像由 `.env` 中的 `APP_IMAGE` 控制，数据库结构由 Flyway 自动迁移。

## 本地开发

先启动依赖：

```bash
cp .env.example .env
set -a
source .env
set +a

docker compose up -d mysql redis
```

启动后端：

```bash
mvn -pl landgate-app -am spring-boot:run -Dspring-boot.run.profiles=test
```

打包与测试：

```bash
mvn clean package -DskipTests
mvn test
```

## 接口入口

| 场景 | 路径 |
| --- | --- |
| Anthropic Messages | `POST /v1/messages` |
| OpenAI Chat Completions | `POST /v1/chat/completions` |
| OpenAI Responses | `POST /v1/responses`、`POST /responses`、`POST /backend-api/codex/responses` |
| OpenAI Images | `POST /images/generations`、`POST /images/edits` |
| Gemini | `POST /v1beta/models/{model}:generateContent` |
| Antigravity | `POST /antigravity/v1/messages`、`POST /antigravity/v1/chat/completions` |
| 用量查询 | `GET /v1/usage` |
| 用户 API | `/api/v1/auth/**`、`/api/v1/user/**`、`/api/v1/payment/**` |
| 管理 API | `/api/v1/admin/**` |

### 调用示例

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer YOUR_LANDGATE_API_KEY" \
  -H "content-type: application/json" \
  -d '{
    "model": "gpt-4.1",
    "messages": [
      { "role": "user", "content": "Hello LandGate" }
    ]
  }'
```

## 环境变量

| 变量 | 说明 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | 运行环境，默认 `test`，生产建议 `prod` |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | MySQL 连接配置 |
| `DB_USER` / `DB_PASSWORD` | MySQL 用户名和密码 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接配置 |
| `JWT_SIGNING_KEY` | JWT 签名密钥，至少 32 字符 |
| `CREDENTIAL_ENCRYPTION_KEY` | 上游凭证 AES-256 加密密钥，64 位 Hex |
| `LANDGATE_ADMIN_EMAIL` / `LANDGATE_ADMIN_PASSWORD` | 初始管理员账号 |
| `SMTP_HOST` / `SMTP_PORT` | 邮件验证码 SMTP 配置 |
| `CAPTCHA_ENABLED` / `TURNSTILE_SECRET_KEY` | Cloudflare Turnstile 配置 |
| `OAUTH_REDIRECT_URI` | OAuth 授权回调地址 |

完整模板见 [`.env.example`](.env.example)。

## 管理流程

1. 使用 `.env` 中的管理员账号登录管理端。
2. 添加上游账号，配置平台、账号类型、凭证和可用模型。
3. 创建分组，并把上游账号按优先级绑定到分组。
4. 配置模型价格、用户余额、API Key 配额和过期时间。
5. 用户使用 LandGate API Key 调用兼容接口，系统自动完成调度、透传、记录和扣费。

## 数据与迁移

| 内容 | 位置 |
| --- | --- |
| Flyway 迁移 | [`landgate-app/src/main/resources/db/migration`](landgate-app/src/main/resources/db/migration) |
| MyBatis Mapper | [`landgate-app/src/main/resources/mapper`](landgate-app/src/main/resources/mapper) |
| 应用日志 | `./data/log` |
| Docker MySQL 数据 | `mysql_data` volume |

## 安全建议

- 不要提交真实 `.env`、上游账号凭证、支付密钥和数据库密码。
- 生产环境务必替换 `JWT_SIGNING_KEY` 和 `CREDENTIAL_ENCRYPTION_KEY`。
- 生产环境建议开启 `CAPTCHA_ENABLED=true` 并配置 Turnstile。
- 对外暴露服务时建议通过 HTTPS、反向代理和防火墙限制管理端访问。
- `LANDGATE_ADMIN_RESET_PASSWORD` 仅在需要重置管理员密码时短期开启。

## License

当前仓库尚未声明开源许可证。公开发布前建议根据实际用途补充 `LICENSE` 文件。
