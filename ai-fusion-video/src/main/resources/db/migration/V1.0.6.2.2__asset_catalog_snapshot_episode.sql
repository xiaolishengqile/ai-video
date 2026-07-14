ALTER TABLE `afv_asset_catalog_snapshot`
    ADD COLUMN `script_episode_id` BIGINT NULL COMMENT '关联剧本分集ID' AFTER `script_id`,
    ADD INDEX `idx_asset_catalog_snapshot_episode` (`script_episode_id`);
