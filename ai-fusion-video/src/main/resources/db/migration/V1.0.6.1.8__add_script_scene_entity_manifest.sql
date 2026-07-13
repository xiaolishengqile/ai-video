ALTER TABLE afv_script_scene_item
  ADD COLUMN entity_manifest json NULL COMMENT '场次实体清单（含已解析资产ID）'
  AFTER prop_asset_ids;
