package com.stonewu.fusion.service.asset;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.AssetCatalogSnapshot;
import com.stonewu.fusion.mapper.asset.AssetCatalogSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetCatalogSnapshotServiceTests {

    @Mock
    private AssetService assetService;

    @Mock
    private AssetCatalogSnapshotMapper snapshotMapper;

    @InjectMocks
    private AssetCatalogSnapshotService snapshotService;

    @Test
    void createStoresAnImmutableAssetCatalogForTheScriptRun() {
        when(assetService.listWithItemsByProject(11L)).thenReturn(List.of(
                Map.of("id", 9L, "name", "白雪公主", "type", "character", "items", List.of())));
        doAnswer(invocation -> {
            invocation.<AssetCatalogSnapshot>getArgument(0).setId(31L);
            return 1;
        }).when(snapshotMapper).insert(any(AssetCatalogSnapshot.class));

        AssetCatalogSnapshot snapshot = snapshotService.create(11L, 7L);

        assertThat(snapshot.getId()).isEqualTo(31L);
        assertThat(snapshot.getProjectId()).isEqualTo(11L);
        assertThat(snapshot.getScriptId()).isEqualTo(7L);
        assertThat(snapshot.getAssetCount()).isEqualTo(1);
        assertThat(JSONUtil.parseArray(snapshot.getCatalogJson())).hasSize(1);
        verify(snapshotMapper).insert(any(AssetCatalogSnapshot.class));
    }
}
