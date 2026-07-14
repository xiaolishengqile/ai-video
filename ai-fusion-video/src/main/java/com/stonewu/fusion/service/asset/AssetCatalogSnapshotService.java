package com.stonewu.fusion.service.asset;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.common.BusinessException;
import com.stonewu.fusion.entity.asset.AssetCatalogSnapshot;
import com.stonewu.fusion.mapper.asset.AssetCatalogSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Creates and reads immutable project asset catalog snapshots for AI workflows. */
@Service
@RequiredArgsConstructor
public class AssetCatalogSnapshotService {

    private final AssetService assetService;
    private final AssetCatalogSnapshotMapper snapshotMapper;

    @Transactional
    public AssetCatalogSnapshot create(Long projectId, Long scriptId, Long scriptEpisodeId, Integer episodeNumber) {
        List<Map<String, Object>> assets = assetService.listWithItemsByProjectEpisode(projectId, episodeNumber);
        AssetCatalogSnapshot snapshot = AssetCatalogSnapshot.builder()
                .projectId(projectId)
                .scriptId(scriptId)
                .scriptEpisodeId(scriptEpisodeId)
                .assetCount(assets.size())
                .catalogJson(JSONUtil.toJsonStr(assets))
                .build();
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    public AssetCatalogSnapshot getById(Long id) {
        AssetCatalogSnapshot snapshot = snapshotMapper.selectById(id);
        if (snapshot == null) {
            throw new BusinessException("资产目录快照不存在: " + id);
        }
        return snapshot;
    }
}
