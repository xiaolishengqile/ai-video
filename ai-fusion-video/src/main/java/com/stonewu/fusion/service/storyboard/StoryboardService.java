package com.stonewu.fusion.service.storyboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.storyboard.Storyboard;
import com.stonewu.fusion.entity.storyboard.StoryboardEpisode;
import com.stonewu.fusion.entity.storyboard.StoryboardItem;
import com.stonewu.fusion.entity.storyboard.StoryboardScene;
import com.stonewu.fusion.mapper.storyboard.StoryboardEpisodeMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardItemMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardMapper;
import com.stonewu.fusion.mapper.storyboard.StoryboardSceneMapper;
import com.stonewu.fusion.security.SecurityUtils;
import com.stonewu.fusion.service.team.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 分镜脚本服务（含分镜集、分镜场次、分镜条目管理）
 */
@Service
@RequiredArgsConstructor
public class StoryboardService {

    private final StoryboardMapper storyboardMapper;
    private final StoryboardEpisodeMapper episodeMapper;
    private final StoryboardSceneMapper sceneMapper;
    private final StoryboardItemMapper itemMapper;
    private final TeamService teamService;

    // ========== 分镜脚本 ==========

    @Cacheable(value = "storyboard", key = "#id")
    public Storyboard getById(Long id) {
        Storyboard sb = storyboardMapper.selectById(id);
        if (sb == null) throw new BusinessException("分镜脚本不存在: " + id);
        return sb;
    }

    @Cacheable(value = "storyboard", key = "'project:' + #projectId")
    public List<Storyboard> listByProject(Long projectId) {
        return storyboardMapper.selectList(new LambdaQueryWrapper<Storyboard>()
                .eq(Storyboard::getProjectId, projectId)
                .orderByDesc(Storyboard::getCreateTime));
    }

    @CacheEvict(value = "storyboard", allEntries = true)
    @Transactional
    public Storyboard create(Storyboard storyboard) {
        applyCurrentTeamOwnership(storyboard);
        storyboardMapper.insert(storyboard);
        return storyboard;
    }

    @CacheEvict(value = "storyboard", allEntries = true)
    @Transactional
    public Storyboard update(Storyboard storyboard) {
        getById(storyboard.getId());
        storyboardMapper.updateById(storyboard);
        return storyboardMapper.selectById(storyboard.getId());
    }

    @CacheEvict(value = "storyboard", allEntries = true)
    @Transactional
    public void delete(Long id) {
        storyboardMapper.deleteById(id);
    }

    // ========== 分镜集 ==========

    @Cacheable(value = "storyboardEpisode", key = "#id")
    public StoryboardEpisode getEpisodeById(Long id) {
        StoryboardEpisode ep = episodeMapper.selectById(id);
        if (ep == null) throw new BusinessException("分镜集不存在: " + id);
        return ep;
    }

    @Cacheable(value = "storyboardEpisode", key = "'storyboard:' + #storyboardId")
    public List<StoryboardEpisode> listEpisodes(Long storyboardId) {
        return episodeMapper.selectList(new LambdaQueryWrapper<StoryboardEpisode>()
                .eq(StoryboardEpisode::getStoryboardId, storyboardId)
                .orderByAsc(StoryboardEpisode::getSortOrder)
                .orderByAsc(StoryboardEpisode::getEpisodeNumber));
    }

    @CacheEvict(value = "storyboardEpisode", allEntries = true)
    @Transactional
    public StoryboardEpisode createEpisode(StoryboardEpisode episode) {
        episodeMapper.insert(episode);
        return episode;
    }

    @CacheEvict(value = "storyboardEpisode", allEntries = true)
    @Transactional
    public StoryboardEpisode updateEpisode(StoryboardEpisode episode) {
        getEpisodeById(episode.getId());
        episodeMapper.updateById(episode);
        return episodeMapper.selectById(episode.getId());
    }

    @CacheEvict(value = "storyboardEpisode", allEntries = true)
    @Transactional
    public void deleteEpisode(Long id) {
        episodeMapper.deleteById(id);
    }

    // ========== 分镜场次 ==========

    @Cacheable(value = "storyboardScene", key = "#id")
    public StoryboardScene getSceneById(Long id) {
        StoryboardScene scene = sceneMapper.selectById(id);
        if (scene == null) throw new BusinessException("分镜场次不存在: " + id);
        return scene;
    }

    @Cacheable(value = "storyboardScene", key = "'episode:' + #episodeId")
    public List<StoryboardScene> listScenesByEpisode(Long episodeId) {
        return sceneMapper.selectList(new LambdaQueryWrapper<StoryboardScene>()
                .eq(StoryboardScene::getEpisodeId, episodeId)
                .orderByAsc(StoryboardScene::getSortOrder));
    }

    public List<StoryboardScene> listScenesByStoryboard(Long storyboardId) {
        return sceneMapper.selectList(new LambdaQueryWrapper<StoryboardScene>()
                .eq(StoryboardScene::getStoryboardId, storyboardId)
                .orderByAsc(StoryboardScene::getSortOrder));
    }

    @CacheEvict(value = "storyboardScene", allEntries = true)
    @Transactional
    public StoryboardScene createScene(StoryboardScene scene) {
        sceneMapper.insert(scene);
        return scene;
    }

    @CacheEvict(value = "storyboardScene", allEntries = true)
    @Transactional
    public StoryboardScene updateScene(StoryboardScene scene) {
        getSceneById(scene.getId());
        sceneMapper.updateById(scene);
        return sceneMapper.selectById(scene.getId());
    }

    @CacheEvict(value = "storyboardScene", allEntries = true)
    @Transactional
    public void deleteScene(Long id) {
        sceneMapper.deleteById(id);
    }

    // ========== 分镜条目 ==========

    @Cacheable(value = "storyboardItem", key = "#id")
    public StoryboardItem getItemById(Long id) {
        StoryboardItem item = itemMapper.selectById(id);
        if (item == null) throw new BusinessException("分镜条目不存在: " + id);
        return item;
    }

    @Cacheable(value = "storyboardItem", key = "'storyboard:' + #storyboardId")
    public List<StoryboardItem> listItems(Long storyboardId) {
        return itemMapper.selectList(new LambdaQueryWrapper<StoryboardItem>()
                .eq(StoryboardItem::getStoryboardId, storyboardId)
                .orderByAsc(StoryboardItem::getSortOrder));
    }

    public List<StoryboardItem> listItemsByScene(Long sceneId) {
        return itemMapper.selectList(new LambdaQueryWrapper<StoryboardItem>()
                .eq(StoryboardItem::getStoryboardSceneId, sceneId)
                .orderByAsc(StoryboardItem::getSortOrder));
    }

    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public StoryboardItem createItem(StoryboardItem item) {
        itemMapper.insert(item);
        return item;
    }

    private void applyCurrentTeamOwnership(Storyboard storyboard) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null) {
            return;
        }
        TeamService.OwnerScope ownerScope = teamService.getRequiredCurrentOwnerScopeByUser(currentUserId);
        storyboard.setOwnerType(ownerScope.getOwnerType());
        storyboard.setOwnerId(ownerScope.getOwnerId());
    }

    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public StoryboardItem updateItem(StoryboardItem item) {
        getItemById(item.getId());
        itemMapper.updateById(item);
        return itemMapper.selectById(item.getId());
    }

    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public void deleteItem(Long id) {
        itemMapper.deleteById(id);
    }

    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public void batchCreateItems(List<StoryboardItem> items) {
        for (StoryboardItem item : items) {
            itemMapper.insert(item);
        }
    }

    @CacheEvict(value = "storyboardItem", allEntries = true)
    @Transactional
    public void batchUpdateItemSort(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            StoryboardItem item = new StoryboardItem();
            item.setId(ids.get(i));
            item.setSortOrder(i);
            itemMapper.updateById(item);
        }
    }
}
