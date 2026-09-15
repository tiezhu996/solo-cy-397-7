-- 合同模板版本管理 schema（幂等，随后端启动执行，保证表结构存在）
-- 设计要点：
-- 1. 模板每次修改生成一行新的 template_versions，版本行创建后不再更新内容（不可变）。
-- 2. published_guard = 已发布版本的 template_id（其余为 NULL），配合唯一索引
--    在数据库层面保证同一模板最多一个已发布版本（MySQL/H2 唯一索引均允许多个 NULL）。
-- 3. contracts 固化生成时的 template_version_id / version_no / 渲染后内容 / 提交变量，
--    历史合同永远能对应回它被生成时的版本快照。

CREATE TABLE IF NOT EXISTS contract_templates (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  type VARCHAR(32) NOT NULL,
  title VARCHAR(120) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS template_versions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  content MEDIUMTEXT NOT NULL,
  variables TEXT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  published_guard BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at DATETIME NULL,
  CONSTRAINT uk_template_version UNIQUE (template_id, version_no),
  CONSTRAINT uk_published_guard UNIQUE (published_guard)
);

CREATE TABLE IF NOT EXISTS contracts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  template_version_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  title VARCHAR(120) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  variables TEXT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  signers TEXT,
  signed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS legal_tickets (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT,
  type VARCHAR(32),
  description TEXT,
  status VARCHAR(32),
  attachments TEXT
);

CREATE TABLE IF NOT EXISTS legal_faq (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  category VARCHAR(60),
  question VARCHAR(200),
  answer TEXT
);
