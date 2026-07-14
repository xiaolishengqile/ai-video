ALTER TABLE `afv_storyboard_item`
    ADD COLUMN `scene_asset_item_ids` JSON NULL COMMENT '场景子资产ID列表，首项为主场景 (List<Long> of AssetItem.id)' AFTER `scene_asset_item_id`;
