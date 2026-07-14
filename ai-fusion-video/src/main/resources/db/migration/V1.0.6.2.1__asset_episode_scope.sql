ALTER TABLE `afv_asset`
    ADD COLUMN `episode_number` INT NULL COMMENT '所属剧集序号；NULL 为历史未归集资产' AFTER `project_id`;

ALTER TABLE `afv_asset`
    DROP INDEX `uk_asset_project_type_normalized_deleted`,
    ADD UNIQUE INDEX `uk_asset_project_episode_type_normalized_deleted`
        (`project_id`, `episode_number`, `type`, `normalized_name`, `deleted`);
