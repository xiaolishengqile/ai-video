CREATE TABLE `afv_asset_catalog_snapshot` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `project_id` bigint NOT NULL COMMENT '项目ID',
    `script_id` bigint NULL DEFAULT NULL COMMENT '关联剧本ID',
    `asset_count` int NOT NULL DEFAULT 0 COMMENT '快照内主资产数',
    `catalog_json` longtext NOT NULL COMMENT '资产和子资产的不可变目录JSON',
    `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除标志',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_asset_catalog_snapshot_project` (`project_id`),
    INDEX `idx_asset_catalog_snapshot_script` (`script_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI流程资产目录快照';
