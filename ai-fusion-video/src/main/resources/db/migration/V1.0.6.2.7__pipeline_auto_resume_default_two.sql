ALTER TABLE `afv_ai_pipeline_run`
    MODIFY COLUMN `max_auto_resume` int NOT NULL DEFAULT 2 COMMENT '最大自动续跑次数';

UPDATE `afv_ai_pipeline_run`
SET `max_auto_resume` = 2
WHERE `max_auto_resume` = 1;
