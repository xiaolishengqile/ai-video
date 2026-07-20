CREATE TABLE `afv_ai_pipeline_run` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `run_id` varchar(64) NOT NULL COMMENT '逻辑任务ID',
    `user_id` bigint NOT NULL COMMENT '所属用户ID',
    `project_id` bigint DEFAULT NULL COMMENT '关联项目ID',
    `agent_type` varchar(64) NOT NULL COMMENT 'Pipeline类型',
    `title` varchar(255) DEFAULT NULL COMMENT '展示标题',
    `request_json` mediumtext COMMENT '脱敏后的原始请求',
    `status` varchar(32) NOT NULL COMMENT '逻辑任务状态',
    `auto_resume_count` int NOT NULL DEFAULT 0 COMMENT '自动续跑次数',
    `max_auto_resume` int NOT NULL DEFAULT 2 COMMENT '最大自动续跑次数',
    `active_conversation_id` varchar(64) DEFAULT NULL COMMENT '当前执行会话ID',
    `last_error_category` varchar(64) DEFAULT NULL COMMENT '最近错误分类',
    `last_error_code` varchar(128) DEFAULT NULL COMMENT '最近错误码',
    `last_error_message` text COMMENT '脱敏后的最近根因',
    `version` int NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pipeline_run_id` (`run_id`),
    KEY `idx_pipeline_run_status_time` (`status`, `update_time`),
    KEY `idx_pipeline_run_user` (`user_id`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI Pipeline逻辑任务';

CREATE TABLE `afv_ai_pipeline_checkpoint` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `pipeline_run_id` bigint NOT NULL COMMENT '逻辑任务主键',
    `checkpoint_key` varchar(255) NOT NULL COMMENT '任务内稳定步骤键',
    `tool_name` varchar(100) NOT NULL COMMENT '工具名称',
    `scope_type` varchar(64) DEFAULT NULL COMMENT '业务范围类型',
    `scope_id` varchar(128) DEFAULT NULL COMMENT '业务范围ID',
    `replay_policy` varchar(32) NOT NULL COMMENT '重放策略',
    `status` varchar(32) NOT NULL COMMENT '检查点状态',
    `input_json` mediumtext COMMENT '脱敏裁剪后的输入',
    `output_json` mediumtext COMMENT '恢复所需输出',
    `attempt_count` int NOT NULL DEFAULT 1 COMMENT '执行次数',
    `error_category` varchar(64) DEFAULT NULL COMMENT '最近错误分类',
    `error_code` varchar(128) DEFAULT NULL COMMENT '最近错误码',
    `error_message` text COMMENT '脱敏后的最近根因',
    `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pipeline_checkpoint` (`pipeline_run_id`, `checkpoint_key`),
    KEY `idx_pipeline_checkpoint_status` (`pipeline_run_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI Pipeline工具检查点';

ALTER TABLE `afv_agent_conversation`
    ADD COLUMN `pipeline_run_id` bigint DEFAULT NULL COMMENT '关联Pipeline逻辑任务' AFTER `conversation_id`,
    ADD COLUMN `attempt_number` int NOT NULL DEFAULT 0 COMMENT '任务执行尝试序号' AFTER `pipeline_run_id`,
    ADD COLUMN `resume_type` varchar(16) NOT NULL DEFAULT 'INITIAL' COMMENT '执行类型' AFTER `attempt_number`,
    ADD KEY `idx_conversation_pipeline_run` (`pipeline_run_id`, `attempt_number`);
