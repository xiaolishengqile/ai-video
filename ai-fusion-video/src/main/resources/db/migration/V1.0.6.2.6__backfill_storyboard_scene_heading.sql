UPDATE afv_storyboard_scene ss
JOIN afv_storyboard_episode se
  ON se.id = ss.episode_id
  AND se.deleted = 0
JOIN afv_script_scene_item si
  ON si.episode_id = se.script_episode_id
  AND si.scene_number = ss.scene_number
  AND si.deleted = 0
SET ss.scene_heading = si.scene_heading
WHERE ss.deleted = 0
  AND (ss.scene_heading IS NULL OR ss.scene_heading = '')
  AND si.scene_heading IS NOT NULL
  AND si.scene_heading <> '';
