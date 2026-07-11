ALTER TABLE `afv_storyboard_item`
    ADD COLUMN `video_workflow_mode` varchar(32) NOT NULL DEFAULT 'auto' COMMENT '视频工作流模式：auto-自动 narrative-剧情 action-战斗' AFTER `video_prompt`,
    ADD COLUMN `video_workflow_resolved_mode` varchar(32) DEFAULT NULL COMMENT '自动判断后的实际视频工作流模式' AFTER `video_workflow_mode`,
    ADD COLUMN `video_workflow_reason` text DEFAULT NULL COMMENT '视频工作流模式判断原因' AFTER `video_workflow_resolved_mode`,
    ADD COLUMN `storyboard_image_url` varchar(1024) DEFAULT NULL COMMENT '故事板图URL' AFTER `video_workflow_reason`,
    ADD COLUMN `grid25_image_url` varchar(1024) DEFAULT NULL COMMENT '25宫格剧情故事板图URL' AFTER `storyboard_image_url`,
    ADD COLUMN `grid25_prompt` text DEFAULT NULL COMMENT '25宫格剧情故事板提示词' AFTER `grid25_image_url`,
    ADD COLUMN `action_storyboard_image_url` varchar(1024) DEFAULT NULL COMMENT '动作故事板图URL' AFTER `grid25_prompt`,
    ADD COLUMN `action_storyboard_prompt` text DEFAULT NULL COMMENT '动作故事板提示词' AFTER `action_storyboard_image_url`,
    ADD COLUMN `motion_plan` text DEFAULT NULL COMMENT '战斗身位调度与动作规划' AFTER `action_storyboard_prompt`,
    ADD COLUMN `key_frame_image_urls` json DEFAULT NULL COMMENT '关键帧URL数组' AFTER `motion_plan`,
    ADD COLUMN `video_prompt_mode` varchar(32) DEFAULT NULL COMMENT '视频提示词生成模式' AFTER `key_frame_image_urls`,
    ADD COLUMN `quality_check_status` tinyint DEFAULT 0 COMMENT '质检状态：0未质检 1质检中 2通过 3失败' AFTER `video_prompt_mode`,
    ADD COLUMN `quality_check_result` text DEFAULT NULL COMMENT '质检结果' AFTER `quality_check_status`;
