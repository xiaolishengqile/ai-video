package com.stonewu.fusion.service.asset;

import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.mapper.asset.AssetItemMapper;
import com.stonewu.fusion.mapper.asset.AssetMapper;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.team.TeamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetServiceBulkDeleteTests {

    @Mock
    private AssetMapper assetMapper;
    @Mock
    private AssetItemMapper assetItemMapper;
    @Mock
    private ProjectService projectService;
    @Mock
    private TeamService teamService;
    @InjectMocks
    private AssetService assetService;

    @Test
    void deleteAccessibleDeletesEverySelectedAssetAfterAccessCheck() {
        Asset first = Asset.builder().id(1L).userId(9L).name("角色一").build();
        Asset second = Asset.builder().id(2L).userId(9L).name("角色二").build();
        when(assetMapper.selectById(1L)).thenReturn(first, first);
        when(assetMapper.selectById(2L)).thenReturn(second, second);

        assertThatCode(() -> assetService.deleteAccessible(List.of(1L, 2L), 9L))
                .doesNotThrowAnyException();

        verify(assetMapper).deleteById(1L);
        verify(assetMapper).deleteById(2L);
    }

    @Test
    void deleteAccessibleRejectsTheWholeBatchWhenAnyAssetIsInaccessible() {
        Asset allowed = Asset.builder().id(1L).userId(9L).name("角色一").build();
        Asset forbidden = Asset.builder().id(2L).userId(10L).projectId(100L).name("角色二").build();
        when(assetMapper.selectById(1L)).thenReturn(allowed);
        when(assetMapper.selectById(2L)).thenReturn(forbidden);
        when(projectService.canAccessProject(100L, 9L)).thenReturn(false);

        assertThatThrownBy(() -> assetService.deleteAccessible(List.of(1L, 2L), 9L))
                .hasMessageContaining("无权删除资产");

        verify(assetMapper, never()).deleteById(1L);
        verify(assetMapper, never()).deleteById(2L);
    }
}
