package com.stonewu.fusion.service.ai.tool;

import cn.hutool.json.JSONUtil;
import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.service.ai.ToolExecutionContext;
import com.stonewu.fusion.service.asset.AssetService;
import com.stonewu.fusion.service.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryAssetItemsToolExecutorTests {

    @Mock
    private AssetService assetService;
    @Mock
    private ProjectService projectService;

    @InjectMocks
    private QueryAssetItemsToolExecutor executor;

    @Test
    void batchQuerySupportsAProjectSizedAssetList() {
        when(assetService.getById(any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return Asset.builder().id(id).name("资产" + id).type("character").build();
        });
        when(assetService.canAccessAsset(any(Asset.class), anyLong())).thenReturn(true);
        when(assetService.listItems(any())).thenReturn(java.util.List.of(
                AssetItem.builder().id(1L).name("初始设定").itemType("initial").imageUrl("https://example.test/a.png").build()));

        String ids = LongStream.rangeClosed(1, 31)
                .mapToObj(Long::toString)
                .collect(java.util.stream.Collectors.joining(","));

        var result = JSONUtil.parseObj(executor.execute("{\"assetIds\":[" + ids + "]}",
                ToolExecutionContext.builder().userId(9L).build()));

        assertThat(result.getInt("totalAssets")).isEqualTo(31);
        assertThat(result.getJSONArray("assets")).hasSize(31);
        assertThat(executor.getParametersSchema()).contains("\"maxItems\": 50");
    }
}
