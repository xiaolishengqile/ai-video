package com.stonewu.fusion.entity.script;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.stonewu.fusion.common.BaseEntity;
import com.stonewu.fusion.common.handler.JsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Stores reviewed or pending asset-to-script prebinding results. */
@TableName(value = "afv_script_asset_binding", autoResultMap = true)
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScriptAssetBinding extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long scriptId;
    private Long scriptEpisodeId;
    private Integer episodeNumber;
    private Long scriptSceneItemId;
    private String assetType;
    private String entityName;
    private String entityKey;
    private Long assetId;
    private Long assetItemId;
    private String matchStatus;
    private String matchSource;
    private Integer confidence;
    private String evidenceText;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String candidateJson;

    private Boolean reviewed;
}
