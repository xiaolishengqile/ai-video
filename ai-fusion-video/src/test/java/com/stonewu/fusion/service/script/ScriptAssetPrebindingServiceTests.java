package com.stonewu.fusion.service.script;

import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.entity.script.Script;
import com.stonewu.fusion.entity.script.ScriptAssetBinding;
import com.stonewu.fusion.entity.script.ScriptEpisode;
import com.stonewu.fusion.entity.script.ScriptSceneItem;
import com.stonewu.fusion.mapper.script.ScriptAssetBindingMapper;
import com.stonewu.fusion.service.asset.AssetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScriptAssetPrebindingServiceTests {

    @Mock
    private ScriptService scriptService;

    @Mock
    private AssetService assetService;

    @Mock
    private ScriptAssetBindingMapper bindingMapper;

    @InjectMocks
    private ScriptAssetPrebindingService service;

    @Test
    void runEpisodePrebindingMatchesOnlyCurrentEpisodeUploadedAssets() {
        when(scriptService.getById(3L)).thenReturn(Script.builder().id(3L).projectId(1L).build());
        when(scriptService.getEpisodeById(2L)).thenReturn(ScriptEpisode.builder()
                .id(2L).scriptId(3L).episodeNumber(1).rawContent("顾沉舟进入撤离列车站台。").build());
        when(scriptService.listScenesByEpisode(2L)).thenReturn(List.of());
        when(assetService.listByProjectEpisode(1L, 1, null)).thenReturn(List.of(
                Asset.builder().id(10L).projectId(1L).episodeNumber(1).type("character").name("顾沉舟").build(),
                Asset.builder().id(11L).projectId(1L).episodeNumber(1).type("scene").name("撤离列车站台").build(),
                Asset.builder().id(12L).projectId(1L).episodeNumber(1).type("prop").name("星魔脊王玉笛").build()));
        when(assetService.listItems(10L)).thenReturn(List.of(AssetItem.builder().id(101L).imageUrl("/media/gu.png").build()));
        when(assetService.listItems(11L)).thenReturn(List.of(AssetItem.builder().id(111L).imageUrl("/media/station.png").build()));
        when(assetService.listItems(12L)).thenReturn(List.of(AssetItem.builder().id(121L).imageUrl("/media/prop.png").build()));

        ScriptAssetPrebindingService.PrebindingSummary summary = service.runEpisodePrebinding(1L, 3L, 2L);

        assertThat(summary.matched()).isEqualTo(2);
        assertThat(summary.uploadedUnused()).isEqualTo(1);
        ArgumentCaptor<ScriptAssetBinding> captor = ArgumentCaptor.forClass(ScriptAssetBinding.class);
        verify(bindingMapper).delete(any());
        verify(bindingMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ScriptAssetBinding::getEntityName, ScriptAssetBinding::getAssetId,
                        ScriptAssetBinding::getAssetItemId, ScriptAssetBinding::getMatchStatus,
                        ScriptAssetBinding::getMatchSource)
                .containsExactlyInAnyOrder(
                        tuple("顾沉舟", 10L, 101L, "matched", "exact_name"),
                        tuple("撤离列车站台", 11L, 111L, "matched", "exact_name"),
                        tuple("星魔脊王玉笛", 12L, 121L, "uploaded_unused", "none"));
    }

    @Test
    void runEpisodePrebindingUsesCleanDisplayNameForDescriptiveUploadedAssets() {
        when(scriptService.getById(3L)).thenReturn(Script.builder().id(3L).projectId(1L).build());
        when(scriptService.getEpisodeById(2L)).thenReturn(ScriptEpisode.builder()
                .id(2L).scriptId(3L).episodeNumber(1).rawContent("老人抓住防爆门。").build());
        when(scriptService.listScenesByEpisode(2L)).thenReturn(List.of(
                ScriptSceneItem.builder().sceneDescription("撤离列车内部拥挤。").build()));
        when(assetService.listByProjectEpisode(1L, 1, null)).thenReturn(List.of(
                Asset.builder().id(20L).projectId(1L).episodeNumber(1).type("character")
                        .name("撤离列车内部｜抓住防爆门的老人").build()));
        when(assetService.listItems(20L)).thenReturn(List.of(AssetItem.builder().id(201L).imageUrl("/media/old.png").build()));

        ScriptAssetPrebindingService.PrebindingSummary summary = service.runEpisodePrebinding(1L, 3L, 2L);

        assertThat(summary.matched()).isEqualTo(1);
        ArgumentCaptor<ScriptAssetBinding> captor = ArgumentCaptor.forClass(ScriptAssetBinding.class);
        verify(bindingMapper).insert(captor.capture());
        assertThat(captor.getValue()).satisfies(binding -> {
            assertThat(binding.getEntityName()).isEqualTo("抓住防爆门的老人");
            assertThat(binding.getAssetId()).isEqualTo(20L);
            assertThat(binding.getMatchSource()).isEqualTo("display_name");
        });
    }

    @Test
    void runEpisodePrebindingMatchesCommonVisualAssetSuffixes() {
        when(scriptService.getById(3L)).thenReturn(Script.builder().id(3L).projectId(1L).build());
        when(scriptService.getEpisodeById(2L)).thenReturn(ScriptEpisode.builder()
                .id(2L).scriptId(3L).episodeNumber(1).rawContent("凌炽与矿区难民进入撤离列车站台。").build());
        when(scriptService.listScenesByEpisode(2L)).thenReturn(List.of());
        when(assetService.listByProjectEpisode(1L, 1, null)).thenReturn(List.of(
                Asset.builder().id(30L).projectId(1L).episodeNumber(1).type("character").name("凌炽三视图").build(),
                Asset.builder().id(31L).projectId(1L).episodeNumber(1).type("character").name("矿区难民群像设定图").build()));
        when(assetService.listItems(30L)).thenReturn(List.of(AssetItem.builder().id(301L).imageUrl("/media/ling.png").build()));
        when(assetService.listItems(31L)).thenReturn(List.of(AssetItem.builder().id(311L).imageUrl("/media/refugees.png").build()));

        ScriptAssetPrebindingService.PrebindingSummary summary = service.runEpisodePrebinding(1L, 3L, 2L);

        assertThat(summary.matched()).isEqualTo(2);
        ArgumentCaptor<ScriptAssetBinding> captor = ArgumentCaptor.forClass(ScriptAssetBinding.class);
        verify(bindingMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ScriptAssetBinding::getEntityName, ScriptAssetBinding::getMatchSource)
                .containsExactlyInAnyOrder(
                        tuple("凌炽", "visual_alias"),
                        tuple("矿区难民", "visual_alias"));
    }

    @Test
    void runEpisodePrebindingSerializesWritesForTheSameEpisode() throws Exception {
        when(scriptService.getById(3L)).thenReturn(Script.builder().id(3L).projectId(1L).build());
        when(scriptService.getEpisodeById(2L)).thenReturn(ScriptEpisode.builder()
                .id(2L).scriptId(3L).episodeNumber(1).rawContent("顾沉舟进入撤离列车站台。").build());
        when(scriptService.listScenesByEpisode(2L)).thenReturn(List.of());
        when(assetService.listByProjectEpisode(1L, 1, null)).thenReturn(List.of());

        AtomicInteger activeDeletes = new AtomicInteger();
        AtomicInteger maxActiveDeletes = new AtomicInteger();
        doAnswer(invocation -> {
            int active = activeDeletes.incrementAndGet();
            maxActiveDeletes.updateAndGet(previous -> Math.max(previous, active));
            Thread.sleep(80);
            activeDeletes.decrementAndGet();
            return 0;
        }).when(bindingMapper).delete(any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> first = executor.submit(() -> runAfter(start));
        Future<?> second = executor.submit(() -> runAfter(start));
        start.countDown();

        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(maxActiveDeletes).hasValue(1);
    }

    private void runAfter(CountDownLatch start) {
        try {
            start.await();
            service.runEpisodePrebinding(1L, 3L, 2L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
