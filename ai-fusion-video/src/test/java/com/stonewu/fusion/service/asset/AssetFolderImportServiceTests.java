package com.stonewu.fusion.service.asset;

import com.stonewu.fusion.entity.asset.Asset;
import com.stonewu.fusion.entity.asset.AssetItem;
import com.stonewu.fusion.controller.asset.vo.AssetFolderImportResultVO;
import com.stonewu.fusion.service.asset.model.AssetFolderImportFile;
import com.stonewu.fusion.service.asset.model.AssetFolderImportPreviewItem;
import com.stonewu.fusion.service.project.ProjectService;
import com.stonewu.fusion.service.storage.MediaStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetFolderImportServiceTests {

    @Mock
    private AssetService assetService;
    @Mock
    private ProjectService projectService;
    @Mock
    private MediaStorageService mediaStorageService;

    private AssetFolderImportService service;

    @BeforeEach
    void setUp() {
        service = new AssetFolderImportService(new AssetFolderImportNameService(), assetService, projectService, mediaStorageService);
        lenient().when(projectService.canAccessProject(1L, 9L)).thenReturn(true);
    }

    @Test
    void importsRootImageIntoTheAutoCreatedInitialItem() {
        Asset created = Asset.builder().id(100L).name("地表实训发布厅").build();
        when(assetService.findByProjectEpisodeTypeAndName(1L, 1, "scene", "地表实训发布厅")).thenReturn(null);
        when(assetService.findOrCreate(any())).thenReturn(new AssetService.FindOrCreateResult(created, true));
        when(assetService.listItems(100L)).thenReturn(List.of(AssetItem.builder().id(101L).assetId(100L).itemType("initial").build()));
        when(mediaStorageService.storeBytes(any(), eq("images"), eq("png"))).thenReturn("/media/room.png");

        AssetFolderImportResultVO result = service.importFiles(1L, 9L, "scene",
                List.of(png("A-15 地表实训发布厅.png")), List.of("场景图/第一集场景图/A-15 地表实训发布厅.png"));

        assertThat(result.results()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("success");
            assertThat(item.assetName()).isEqualTo("地表实训发布厅");
        });
        ArgumentCaptor<AssetItem> item = ArgumentCaptor.forClass(AssetItem.class);
        verify(assetService).updateItem(item.capture());
        assertThat(item.getValue()).extracting(AssetItem::getItemType, AssetItem::getImageUrl)
                .containsExactly("initial", "/media/room.png");
    }

    @Test
    void continuesAfterStorageFailureAndReturnsFailedPath() {
        Asset created = Asset.builder().id(100L).name("第二张").build();
        when(assetService.findByProjectEpisodeTypeAndName(1L, 1, "prop", "第一张")).thenReturn(null);
        when(assetService.findByProjectEpisodeTypeAndName(1L, 1, "prop", "第二张")).thenReturn(null);
        when(assetService.findOrCreate(any())).thenReturn(new AssetService.FindOrCreateResult(created, true));
        when(assetService.listItems(100L)).thenReturn(List.of(AssetItem.builder().id(101L).assetId(100L).itemType("initial").build()));
        when(mediaStorageService.storeBytes(any(), eq("images"), eq("png")))
                .thenThrow(new RuntimeException("storage unavailable"))
                .thenReturn("/media/ok.png");

        AssetFolderImportResultVO result = service.importFiles(1L, 9L, "prop",
                List.of(png("第一张.png"), png("第二张.png")), List.of("道具图/第一集道具图/第一张.png", "道具图/第一集道具图/第二张.png"));

        assertThat(result.results()).extracting(AssetFolderImportResultVO.Item::status)
                .containsExactly("failed", "success");
        assertThat(result.results().getFirst().relativePath()).isEqualTo("道具图/第一集道具图/第一张.png");
    }

    @Test
    void attachesARecognizedVariantToAnExistingParent() {
        Asset parent = Asset.builder().id(6L).name("秦炽川").build();
        when(assetService.findByProjectEpisodeTypeAndName(1L, 1, "character", "秦炽川")).thenReturn(parent);
        when(assetService.listItems(6L)).thenReturn(List.of());
        when(mediaStorageService.storeBytes(any(), eq("images"), eq("png"))).thenReturn("/media/three-view.png");

        AssetFolderImportResultVO result = service.importFiles(1L, 9L, "character",
                List.of(png("秦炽川 三视图.png")), List.of("角色图/第一集角色图/秦炽川 三视图.png"));

        assertThat(result.results()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("success");
            assertThat(item.assetName()).isEqualTo("秦炽川");
            assertThat(item.variantName()).isEqualTo("三视图");
        });
        verify(assetService).createItem(any(AssetItem.class));
    }

    @Test
    void skipsDuplicateRootAndDuplicateVariant() {
        Asset root = Asset.builder().id(5L).name("已有角色").build();
        Asset parent = Asset.builder().id(6L).name("秦炽川").build();
        when(assetService.findByProjectEpisodeTypeAndName(1L, 1, "character", "已有角色")).thenReturn(root);
        when(assetService.findByProjectEpisodeTypeAndName(1L, 1, "character", "秦炽川")).thenReturn(parent);
        when(assetService.listItems(6L)).thenReturn(List.of(AssetItem.builder().name("三视图").build()));

        AssetFolderImportResultVO result = service.importFiles(1L, 9L, "character",
                List.of(png("已有角色.png"), png("秦炽川 三视图.png")), List.of("角色图/第一集角色图/已有角色.png", "角色图/第一集角色图/秦炽川 三视图.png"));

        assertThat(result.results()).extracting(AssetFolderImportResultVO.Item::status)
                .containsExactly("skipped", "skipped");
    }

    @Test
    void turnsAnUnpairedSuffixIntoAnIndependentRootForPreviewAndImport() {
        List<AssetFolderImportPreviewItem> preview = service.preview(1L, 9L, "prop", List.of(
                new AssetFolderImportFile("道具图/第一集道具图/林澈战斗服.png", "林澈战斗服.png")));

        assertThat(preview).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo("root");
            assertThat(item.assetName()).isEqualTo("林澈战斗服");
            assertThat(item.variantName()).isNull();
        });
    }

    @Test
    void previewReadsEpisodeNumberFromChineseFolderName() {
        List<AssetFolderImportPreviewItem> preview = service.preview(1L, 9L, "prop", List.of(
                new AssetFolderImportFile("道具图/第八集道具图/能量核心.png", "能量核心.png")));

        assertThat(preview).singleElement().satisfies(item -> {
            assertThat(item.episodeNumber()).isEqualTo(8);
            assertThat(item.reason()).isNull();
        });
    }

    @Test
    void rejectsAFilePathWithoutAnEpisodeFolder() {
        List<AssetFolderImportPreviewItem> preview = service.preview(1L, 9L, "prop", List.of(
                new AssetFolderImportFile("道具图/能量核心.png", "能量核心.png")));

        assertThat(preview).singleElement().satisfies(item -> {
            assertThat(item.episodeNumber()).isNull();
            assertThat(item.reason()).isEqualTo("路径必须包含一个第 N 集目录");
        });
    }

    @Test
    void keepsAssetsFromDifferentEpisodesIndependentInOneBatch() {
        List<AssetFolderImportPreviewItem> preview = service.preview(1L, 9L, "character", List.of(
                new AssetFolderImportFile("角色图/第一集角色图/秦炽川.png", "秦炽川.png"),
                new AssetFolderImportFile("角色图/第二集角色图/秦炽川 三视图.png", "秦炽川 三视图.png")));

        assertThat(preview).extracting(AssetFolderImportPreviewItem::kind).containsExactly("root", "root");
        assertThat(preview.get(1).assetName()).isEqualTo("秦炽川 三视图");
    }

    private static MockMultipartFile png(String filename) {
        return new MockMultipartFile("files", filename, "image/png", new byte[] {1, 2, 3});
    }
}
