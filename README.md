<div align="center">

<img src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk" alt="Java">
<img src="https://img.shields.io/badge/Spring_Boot-3.4-6DB33F?logo=springboot" alt="Spring Boot">
<img src="https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql" alt="MySQL">
<img src="https://img.shields.io/badge/Redis-7-DC382D?logo=redis" alt="Redis">
<img src="https://img.shields.io/badge/React-19-61DAFB?logo=react" alt="React">
<img src="https://img.shields.io/badge/Docker-Ready-2496ED?logo=docker" alt="Docker">

</div>

---

## LandGate

AI API Gateway — 将上游 AI 订阅配额分发给终端用户，提供统一的认证、计费与负载均衡。

---

### 支持的协议

Anthropic Messages  ·  OpenAI Chat Completions  ·  Gemini GenerateContent

### 功能

- 统一 API Key 分发，绑定群组配额
- Token 级计费，支持按模型/群组差异化定价
- Redis Lua 原子扣减余额，定期回写 MySQL
- 多账户优先级调度 + 粘性会话
- 用户级 & 账户级并发控制
- 内置支付（支付宝 / 微信 / Stripe / EasyPay）
- 兑换码 & 优惠码
- React 管理后台

---

### 技术栈

| 后端 | 前端 | 数据库 | 缓存 |
|------|------|--------|------|
| Java 17 + Spring Boot 3.4 + MyBatis | React 19 + TypeScript + Vite + TailwindCSS | MySQL 8.0 + Flyway | Redis 7 + Redisson |

---

### 部署

```bash
git clone https://github.com/Laqcce-cao/LandGate.git
cd LandGate
cp .env.example .env
docker compose up -d
```

| 服务 | 端口 |
|------|------|
| Web UI | 80 |
| API | 8080 |
| MySQL | 3306 |
| Redis | 6379 |

浏览器打开 `http://<服务器IP>` 即可访问。

---

### 环境变量

编辑 `.env` 文件，必填项：

```bash
DB_PASSWORD=                    # MySQL root 密码
REDIS_PASSWORD=                 # Redis 密码
JWT_SIGNING_KEY=                # openssl rand -base64 32
CREDENTIAL_ENCRYPTION_KEY=      # openssl rand -hex 32
```

完整变量列表见 `.env.example`。

---

### 本地开发

```bash
mvn clean package -DskipTests
mvn spring-boot:run -Dspring-boot.run.profiles=test
```
