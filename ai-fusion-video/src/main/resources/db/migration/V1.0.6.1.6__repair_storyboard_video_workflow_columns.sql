SET @has_grid25_reference_image_urls = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'afv_storyboard_item'
      AND column_name = 'grid25_reference_image_urls'
);

SET @add_grid25_reference_image_urls = IF(
    @has_grid25_reference_image_urls = 0,
    'ALTER TABLE `afv_storyboard_item` ADD COLUMN `grid25_reference_image_urls` json DEFAULT NULL COMMENT ''25宫格生成参考图URL数组'' AFTER `grid25_prompt`',
    'SELECT 1'
);
PREPARE stmt FROM @add_grid25_reference_image_urls;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_video_prompt_mode = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'afv_storyboard_item'
      AND column_name = 'video_prompt_mode'
);

SET @add_video_prompt_mode = IF(
    @has_video_prompt_mode = 0,
    'ALTER TABLE `afv_storyboard_item` ADD COLUMN `video_prompt_mode` varchar(32) DEFAULT NULL COMMENT ''视频提示词生成模式'' AFTER `key_frame_image_urls`',
    'SELECT 1'
);
PREPARE stmt FROM @add_video_prompt_mode;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
