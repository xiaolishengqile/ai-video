-- Keep project assets reusable across concurrent AI workers. Historical rows stay
-- intact: duplicate and deleted names are given a non-canonical lookup key.
ALTER TABLE `afv_asset`
    ADD COLUMN `normalized_name` varchar(160) NOT NULL DEFAULT '' COMMENT '规范化名称，用于项目内去重' AFTER `name`;

UPDATE `afv_asset`
SET `normalized_name` = LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(`name`, ' ', ''), CHAR(9), ''), CHAR(10), ''), CHAR(13), ''), '　', ''));

UPDATE `afv_asset`
SET `normalized_name` = CONCAT(`normalized_name`, '#deleted-', `id`)
WHERE `deleted` = 1;

UPDATE `afv_asset` AS duplicate_asset
JOIN (
    SELECT `id`
    FROM (
        SELECT `id`, ROW_NUMBER() OVER (
            PARTITION BY `project_id`, `type`, `normalized_name`
            ORDER BY `id`
        ) AS row_number
        FROM `afv_asset`
        WHERE `deleted` = 0
    ) AS ranked_assets
    WHERE row_number > 1
) AS duplicates ON duplicates.`id` = duplicate_asset.`id`
SET duplicate_asset.`normalized_name` = CONCAT(duplicate_asset.`normalized_name`, '#legacy-', duplicate_asset.`id`);

ALTER TABLE `afv_asset`
    ADD UNIQUE INDEX `uk_asset_project_type_normalized_deleted` (`project_id`, `type`, `normalized_name`, `deleted`);
