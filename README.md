# 合同模板生成与法律工单 API

```bash
cp .env.example .env
docker compose up -d --build
```

合同模板生成与法律工单 API 提供合同模板**版本化**管理、变量填充生成、签署状态跟踪、法律咨询工单和法律 FAQ 检索能力。

## 项目主要功能

- **模板版本管理**：模板内容与变量定义的每一次修改都会生成一个新版本（`DRAFT`）；版本发布后不可再改，同一模板任意时刻最多一个 `PUBLISHED` 版本（发布新版本时旧版本自动归档为 `ARCHIVED`，事务 + 数据库唯一约束双重保证）。
- **合同生成**：按指定版本（缺省取当前已发布版本）的变量定义严格校验——缺少必填变量、传入未声明变量、渲染后仍残留占位符，都会返回明确错误并拒绝生成；生成的合同固化当时的版本号、渲染内容与提交变量快照，模板后续演进不影响历史合同。
- **合同签署状态管理**：合同状态流转（草稿→待签署→已签署→已过期），记录签署时间和签署方信息。
- **法律工单系统**：用户提交法律咨询工单（劳动纠纷/合同纠纷/房产纠纷/知识产权/其他），状态流转（待处理→处理中→已回复→已关闭），支持回复记录。
- **常见法律知识库**：法律 FAQ 分类维护与关键词搜索。
- **用户合同库**：查看自己创建的所有合同列表，按状态筛选。

## 版本模型

```text
contract_templates        模板元信息（type/title）
template_versions         版本快照：content + variables（变量定义 JSON）
                          status: DRAFT → PUBLISHED → ARCHIVED
                          UNIQUE(template_id, version_no)
                          UNIQUE(published_guard)  -- 仅已发布版本 = template_id，保证唯一已发布
contracts                 合同：template_version_id + version_no + 渲染后 content + 提交变量快照
```

关键行为：

- `POST /api/templates/{id}/versions` 修改模板 → 产生 `version_no` 递增的新草稿版本，历史版本永不更新。
- `POST /api/templates/{id}/versions/{versionNo}/publish` 发布草稿版本；已发布/已归档版本再次发布会返回 `VERSION_IMMUTABLE`。
- 模板内容里出现的 `${placeholder}` 必须在变量定义中声明，否则创建/修改版本时返回 `UNDECLARED_PLACEHOLDER`。
- 生成合同校验失败错误码：`MISSING_REQUIRED_VARIABLES`（缺必填）、`UNDECLARED_VARIABLES`（传了未声明变量）、`UNRESOLVED_PLACEHOLDERS`（渲染后仍有占位符）、`NO_PUBLISHED_VERSION`（未指定版本且没有已发布版本）、`VERSION_NOT_FOUND`（指定版本不存在）。错误响应带 `details` 字段列出具体变量名。
- 数据全部落库（MySQL 命名卷持久化），服务重启后版本、合同与快照原样读回；后端启动时幂等执行 `schema.sql` 保证表结构存在。

## 快速启动（Docker Compose）

```bash
cp .env.example .env
docker compose up -d --build
```

后端监听 `19412` 端口（容器内 8080）。如需重置数据库：`docker compose down -v` 后重新启动。

## 本地开发

```bash
cd backend
mvn spring-boot:run   # 需要本地 MySQL（MYSQL_HOST 等环境变量）
mvn test              # 集成测试使用 H2（MySQL 兼容模式），含重启持久化用例
```

## 技术栈

| 类型 | 技术 |
| --- | --- |
| 后端 | Spring Boot + Java 17 |
| ORM | MyBatis-Plus |
| 数据库 | MySQL 8.0（测试用 H2 MySQL 模式） |
| 认证 | JWT |
| PDF | wkhtmltopdf |

## 目录结构

```text
.
├── backend
│   ├── src/main/java/com/contractapi
│   │   ├── controller/   # 模板/版本/合同/工单/FAQ 接口
│   │   ├── service/      # 版本化与合同生成核心逻辑
│   │   ├── mapper/       # MyBatis-Plus Mapper
│   │   ├── entity/       # 模板/版本/合同实体
│   │   ├── dto/          # 请求与变量定义
│   │   ├── constants/    # 错误码、状态枚举
│   │   ├── exception/    # 统一异常与全局处理器
│   │   └── utils/        # 渲染器、变量校验器
│   ├── src/main/resources
│   │   ├── schema.sql    # 幂等 DDL，随启动执行
│   │   └── application.yml
│   └── src/test          # 版本流程 + 重启持久化集成测试
├── database
│   └── init.sql          # MySQL 容器初始化（与 schema.sql 一致 + 种子数据）
└── docker-compose.yml
```

## 主要 API

- `GET /api/templates` 模板列表（含已发布版本号、版本数）
- `POST /api/templates` 新增模板（同时生成 v1 草稿）
- `GET /api/templates/{id}` 模板详情
- `POST /api/templates/{id}/versions` 修改模板内容/变量定义（生成新版本）
- `GET /api/templates/{id}/versions` 版本列表
- `GET /api/templates/{id}/versions/{versionNo}` 指定版本
- `POST /api/templates/{id}/versions/{versionNo}/publish` 发布版本（旧的已发布版本自动归档）
- `GET /api/templates/{id}/published` 当前已发布版本
- `POST /api/contracts/generate` 合同生成（`versionNo` 可选，缺省用已发布版本）
- `GET /api/contracts/{id}` 合同详情（固化快照）
- `GET /api/contracts` 用户合同库（按用户/状态筛选）
- `PATCH /api/contracts/{id}/status` 更新签署状态
- `POST /api/contracts/{id}/pdf` 导出 PDF
- `POST /api/tickets` 提交法律工单
- `POST /api/tickets/{id}/replies` 添加工单回复
- `GET /api/knowledge` 搜索法律 FAQ

### 合同生成示例

```bash
curl -X POST http://localhost:19412/api/contracts/generate \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": 7,
    "templateId": 1,
    "versionNo": 1,
    "title": "办公室租赁合同",
    "variables": {"partyA": "张三", "partyB": "李四", "amount": "5000", "startDate": "2026-10-01", "endDate": "2027-09-30"}
  }'
```

缺少 `partyB` 时返回：

```json
{"success": false, "code": "MISSING_REQUIRED_VARIABLES", "message": "缺少必填变量: [partyB]", "details": {"missing": ["partyB"]}, "timestamp": "..."}
```

## 环境变量说明

| 变量 | 说明 |
| --- | --- |
| `COMPOSE_PROJECT_NAME` | Compose 项目名，默认 `contractapi` |
| `MYSQL_*` | MySQL 数据库配置 |
| `JWT_SECRET` | JWT 签名密钥 |

## License

MIT
